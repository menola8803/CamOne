package se.snc.camera.update

import android.text.format.DateFormat
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.snc.camera.BuildConfig
import se.snc.camera.R
import se.snc.camera.data.AppSettings
import se.snc.camera.data.SettingsRepository
import se.snc.camera.ui.components.LineDivider
import se.snc.camera.ui.components.NavItem
import se.snc.camera.ui.components.PrimaryButton
import se.snc.camera.ui.components.ScreenHeader
import se.snc.camera.ui.components.SecondaryButton
import se.snc.camera.ui.components.SectionLabel
import se.snc.camera.ui.components.ToggleRow
import se.snc.camera.ui.components.UsbCard
import se.snc.camera.ui.theme.BodyFont
import se.snc.camera.ui.theme.DisplayFont
import se.snc.camera.ui.theme.Usb
import java.util.Date

/**
 * "Uppdateringar" – samma flöde och uppställning som i Direct-appen:
 * senaste versionen (bock) eller NY VERSION med storlek, förlopp, verifiering och ändringar.
 */
@Composable
fun UpdateScreen(
    manager: UpdateManager,
    repository: SettingsRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val state by manager.state.collectAsStateWithLifecycle()
    val settings by repository.settings.collectAsStateWithLifecycle(initialValue = AppSettings())

    // Sök direkt när skärmen öppnas första gången
    LaunchedEffect(Unit) {
        if (state.check == CheckState.Idle) manager.check()
    }
    // Tillbaka från "Tillåt från den här källan": installera direkt om det nu är tillåtet
    LifecycleResumeEffect(state.allowInstallHint) {
        if (state.allowInstallHint && state.download is DownloadState.Ready &&
            context.packageManager.canRequestPackageInstalls()
        ) manager.install()
        onPauseOrDispose { }
    }

    val check = state.check
    val download = state.download
    val available = (check as? CheckState.Available)?.release
    val current = BuildConfig.VERSION_NAME

    Column(
        Modifier
            .fillMaxSize()
            .background(Usb.Bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        ScreenHeader(
            title = stringResource(R.string.update_title),
            subtitle = stringResource(R.string.update_sub),
            backDescription = stringResource(R.string.cd_back),
            onBack = onBack
        )
        Spacer(Modifier.height(14.dp))

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (available != null) {
                NewVersionCard(available, current, download, state.allowInstallHint)
            } else {
                StatusCard(check, current, settings.lastUpdateCheck)
            }

            // Versioner och källa (som Directs informationskort)
            if (available == null) {
                UsbCard {
                    InfoRow(stringResource(R.string.update_installed), current)
                    InfoRow(
                        stringResource(R.string.update_latest_release),
                        when (check) {
                            is CheckState.UpToDate -> check.latest.tag
                            CheckState.NoRelease -> "–"
                            else -> settings.latestKnownVersion.takeIf { it.isNotBlank() }?.let { "v$it" } ?: "–"
                        }
                    )
                    InfoRow(stringResource(R.string.update_source), UpdateManager.UPDATE_REPO.lowercase())
                }
            }

            UsbCard(spacing = 6.dp) {
                ToggleRow(
                    title = stringResource(R.string.update_auto),
                    subtitle = stringResource(R.string.update_auto_sub),
                    checked = settings.autoUpdateCheck,
                    onChange = manager::setAutoCheck
                )
                if (available != null) {
                    NavItem(
                        title = stringResource(R.string.update_source),
                        subtitle = "GitHub · ${UpdateManager.UPDATE_REPO.lowercase()}",
                        onClick = manager::openRepository,
                        modifier = Modifier.padding(top = 2.dp),
                        compact = true
                    )
                }
            }

            NoteCard(
                stringResource(if (available != null) R.string.update_install_note else R.string.update_privacy)
            )
        }

        // Knappar längst ner
        Column(
            Modifier.padding(top = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (available != null && available.apkUrl != null) {
                when (download) {
                    is DownloadState.Ready -> PrimaryButton(
                        text = stringResource(R.string.update_install, available.version),
                        onClick = manager::install,
                        icon = Icons.Outlined.Check,
                        modifier = Modifier.fillMaxWidth()
                    )
                    is DownloadState.Downloading -> PrimaryButton(
                        text = if (download.progress >= 0f) {
                            stringResource(R.string.update_downloading_pct, (download.progress * 100).toInt())
                        } else stringResource(R.string.update_downloading),
                        onClick = {},
                        icon = Icons.Outlined.Download,
                        enabled = false,
                        modifier = Modifier.fillMaxWidth()
                    )
                    is DownloadState.Failed -> if (download.error == UpdateError.NETWORK || download.error == UpdateError.CHECKSUM) {
                        PrimaryButton(
                            text = stringResource(R.string.update_download_install),
                            onClick = manager::downloadAndInstall,
                            icon = Icons.Outlined.Download,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    DownloadState.None -> PrimaryButton(
                        text = stringResource(R.string.update_download_install),
                        onClick = manager::downloadAndInstall,
                        icon = Icons.Outlined.Download,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            if (available != null && (download is DownloadState.Failed || available.apkUrl == null)) {
                SecondaryButton(
                    text = stringResource(R.string.update_open_page),
                    onClick = manager::openReleasePage,
                    icon = Icons.AutoMirrored.Outlined.OpenInNew,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            val checking = check == CheckState.Checking
            if (available == null) {
                PrimaryButton(
                    text = stringResource(if (checking) R.string.update_checking else R.string.update_check),
                    onClick = manager::check,
                    icon = Icons.Outlined.Search,
                    enabled = !checking,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                SecondaryButton(
                    text = stringResource(if (checking) R.string.update_checking else R.string.update_check),
                    onClick = manager::check,
                    enabled = !checking && download !is DownloadState.Downloading,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/** Kortet överst när det inte finns någon ny version: bock, sökning eller fel. */
@Composable
private fun StatusCard(check: CheckState, current: String, lastCheck: Long) {
    val context = LocalContext.current
    val checkedText = if (lastCheck > 0) {
        val time = DateFormat.getTimeFormat(context).format(Date(lastCheck))
        val day = DateUtils.getRelativeTimeSpanString(lastCheck, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS).toString()
            .lowercase()
        stringResource(R.string.update_checked_at, current, "$day $time")
    } else {
        stringResource(R.string.update_never_checked, current)
    }
    UsbCard(spacing = 12.dp, padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 26.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            when (check) {
                CheckState.Checking -> StatusCircle(null, Usb.Gold) {
                    CircularProgressIndicator(color = Usb.Gold, strokeWidth = 3.dp, modifier = Modifier.size(34.dp))
                }
                is CheckState.Failed -> StatusCircle(Icons.Outlined.CloudOff, Usb.Danger)
                CheckState.NoRelease -> StatusCircle(Icons.Outlined.SystemUpdate, Usb.Teal)
                CheckState.Idle -> StatusCircle(Icons.Outlined.SystemUpdate, Usb.Teal)
                else -> StatusCircle(Icons.Outlined.Check, Usb.Gold)
            }
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(
                    when (check) {
                        CheckState.Checking -> R.string.update_checking
                        is CheckState.Failed -> R.string.update_failed
                        CheckState.NoRelease -> R.string.update_no_release
                        CheckState.Idle -> R.string.update_check
                        else -> R.string.update_up_to_date
                    }
                ),
                color = Usb.Text,
                fontFamily = DisplayFont,
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
                lineHeight = 28.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                checkedText,
                color = Usb.Muted,
                fontFamily = BodyFont,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun StatusCircle(icon: ImageVector?, color: androidx.compose.ui.graphics.Color, content: (@Composable () -> Unit)? = null) {
    Box(
        Modifier
            .size(68.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center
    ) {
        if (content != null) content()
        else if (icon != null) Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(34.dp))
    }
}

/** "NY VERSION" – version, storlek, förlopp, verifiering och ändringar (Directs UppdateringNy). */
@Composable
private fun NewVersionCard(release: ReleaseInfo, current: String, download: DownloadState, allowHint: Boolean) {
    val context = LocalContext.current
    UsbCard(spacing = 10.dp, padding = androidx.compose.foundation.layout.PaddingValues(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(stringResource(R.string.update_new), color = Usb.Gold, modifier = Modifier.weight(1f))
            Text(
                stringResource(if (release.prerelease) R.string.update_prerelease else R.string.update_stable),
                color = Usb.Bg,
                fontFamily = BodyFont,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (release.prerelease) Usb.Dim else Usb.Teal)
                    .padding(horizontal = 10.dp, vertical = 3.dp)
            )
        }
        Text(
            "SNC Camera ${release.version}",
            color = Usb.Text,
            fontFamily = DisplayFont,
            fontWeight = FontWeight.Bold,
            fontSize = 32.sp,
            letterSpacing = (-0.5).sp
        )
        val size = if (release.apkSize > 0) Formatter.formatShortFileSize(context, release.apkSize) else "–"
        val released = if (release.publishedAt > 0) {
            DateUtils.getRelativeTimeSpanString(release.publishedAt, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS)
                .toString().lowercase()
        } else "–"
        Text(
            stringResource(R.string.update_meta, current, size, released),
            color = Usb.Muted,
            fontFamily = BodyFont,
            fontSize = 14.sp
        )

        // Förlopp
        val progress = when (download) {
            is DownloadState.Downloading -> download.progress
            is DownloadState.Ready -> 1f
            else -> 0f
        }
        if (download is DownloadState.Downloading && progress < 0f) {
            LinearProgressIndicator(
                color = Usb.Gold,
                trackColor = Usb.Card2,
                strokeCap = StrokeCap.Round,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
            )
        } else {
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                color = if (download is DownloadState.Failed) Usb.Danger else Usb.Gold,
                trackColor = Usb.Card2,
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
                drawStopIndicator = {},
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
            )
        }

        // Status för nedladdningen
        when (download) {
            is DownloadState.Ready -> StatusLine(
                Icons.Outlined.Check,
                stringResource(if (download.verifiedSha256) R.string.update_verified else R.string.update_downloaded),
                Usb.TealL
            )
            is DownloadState.Failed -> StatusLine(
                Icons.Outlined.ErrorOutline,
                stringResource(
                    when (download.error) {
                        UpdateError.NETWORK -> R.string.update_err_network
                        UpdateError.CHECKSUM -> R.string.update_err_checksum
                        UpdateError.WRONG_PACKAGE -> R.string.update_err_package
                        UpdateError.NOT_NEWER -> R.string.update_err_older
                        UpdateError.SIGNATURE -> R.string.update_err_signature
                        UpdateError.INSTALL -> R.string.update_err_install
                    }
                ),
                Usb.Danger
            )
            else -> Unit
        }
        if (allowHint && download is DownloadState.Ready) {
            StatusLine(Icons.Outlined.Shield, stringResource(R.string.update_allow), Usb.GoldL)
        }

        // Ändringar i versionen
        if (release.notes.isNotEmpty()) {
            LineDivider(Modifier.padding(vertical = 4.dp))
            release.notes.forEach { note ->
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        Modifier
                            .padding(top = 8.dp)
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(Usb.Gold)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(note, color = Usb.Muted, fontFamily = BodyFont, fontSize = 15.sp, lineHeight = 21.sp)
                }
            }
        }
    }
}

@Composable
private fun StatusLine(icon: ImageVector, text: String, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, color = color, fontFamily = BodyFont, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 21.sp)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Usb.Dim, fontFamily = BodyFont, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(value, color = Usb.Text, fontFamily = BodyFont, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

/** Liten notis med sköld (som i Direct). */
@Composable
private fun NoteCard(text: String) {
    UsbCard {
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.Shield, contentDescription = null, tint = Usb.Gold, modifier = Modifier.size(18.dp).padding(top = 2.dp))
            Spacer(Modifier.width(12.dp))
            Text(text, color = Usb.Muted, fontFamily = BodyFont, fontSize = 14.sp, lineHeight = 21.sp)
        }
    }
}
