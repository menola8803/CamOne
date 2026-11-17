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
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Shield
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
import se.snc.camera.ui.components.PrimaryButton
import se.snc.camera.ui.components.ScreenHeader
import se.snc.camera.ui.components.SecondaryButton
import se.snc.camera.ui.components.SectionLabel
import se.snc.camera.ui.components.UsbCard
import se.snc.camera.ui.theme.BodyFont
import se.snc.camera.ui.theme.DisplayFont
import se.snc.camera.ui.theme.Ui
import java.util.Date
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import se.snc.camera.ui.components.UsbSwitch

/**
 * "Uppdateringar" – samma uppställning som i Alif: versionskort med knappen Sök efter uppdatering
 * direkt under, eller NY VERSION med förlopp, kontroll och ändringar, och sist valet att söka automatiskt.
 */
@Composable
fun UpdateScreen(
    manager: UpdateManager,
    repository: SettingsRepository,
    onBack: () -> Unit,
    autoStart: Boolean = false
) {
    val context = LocalContext.current
    val state by manager.state.collectAsStateWithLifecycle()
    val settings by repository.settings.collectAsStateWithLifecycle(initialValue = AppSettings())

    // Före nedladdningen: be en gång om aviseringar, så att "SNC Camera är uppdaterad – tryck för att
    // öppna" kan visas om Android stoppar omstarten. Nedladdningen startar oavsett svar.
    var askedNotifications by rememberSaveable { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        manager.downloadAndInstall()
    }
    val startDownload: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !askedNotifications &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askedNotifications = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            manager.downloadAndInstall()
        }
    }
    // Från kortet "Ny version finns" i Inställningar: ladda ner direkt när versionen är hittad
    var autoStarted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.check, autoStart) {
        if (autoStart && !autoStarted && state.check is CheckState.Available && state.download == DownloadState.None) {
            autoStarted = true
            startDownload()
        }
    }

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

    val checking = check == CheckState.Checking

    // Som Alifs Uppdateringar: rubrik, versionskort, knappen direkt under, sist valet "sök automatiskt"
    Column(
        Modifier
            .fillMaxSize()
            .background(Ui.Bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ScreenHeader(
            title = stringResource(R.string.update_title),
            subtitle = stringResource(R.string.update_sub),
            backDescription = stringResource(R.string.cd_back),
            onBack = onBack
        )

        if (available != null) {
            NewVersionCard(available, current, download, state.allowInstallHint)
            if (available.apkUrl != null) {
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
                            onClick = startDownload,
                            icon = Icons.Outlined.Download,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    DownloadState.None -> PrimaryButton(
                        text = stringResource(R.string.update_download_install),
                        onClick = startDownload,
                        icon = Icons.Outlined.Download,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            if (download is DownloadState.Failed || available.apkUrl == null) {
                SecondaryButton(
                    text = stringResource(R.string.update_open_page),
                    onClick = manager::openReleasePage,
                    icon = Icons.AutoMirrored.Outlined.OpenInNew,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else {
            VersionCard(check, current, settings.lastUpdateCheck)
            PrimaryButton(
                text = stringResource(if (checking) R.string.update_checking else R.string.update_check),
                onClick = manager::check,
                enabled = !checking,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Sök automatiskt – med Alifs förklaring av hela flödet
        UsbCard(spacing = 10.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.update_auto),
                    color = Ui.Text,
                    fontFamily = BodyFont,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(12.dp))
                UsbSwitch(checked = settings.autoUpdateCheck, onCheckedChange = manager::setAutoCheck)
            }
            Text(
                stringResource(R.string.update_auto_sub),
                color = Ui.Muted,
                fontFamily = BodyFont,
                fontSize = 14.sp,
                lineHeight = 21.sp
            )
        }
    }
}

/** Alifs versionskort: "Version 1.5.0" och en rad om senaste sökningen. */
@Composable
private fun VersionCard(check: CheckState, current: String, lastCheck: Long) {
    val context = LocalContext.current
    val checkedText = if (lastCheck > 0) {
        val time = DateFormat.getTimeFormat(context).format(Date(lastCheck))
        val day = DateUtils.getRelativeTimeSpanString(lastCheck, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS).toString()
            .lowercase()
        stringResource(R.string.update_checked_at, current, "$day $time")
    } else {
        stringResource(R.string.update_never_checked, current)
    }
    UsbCard(spacing = 4.dp, padding = PaddingValues(horizontal = 20.dp, vertical = 18.dp)) {
        Text(
            stringResource(R.string.update_version_line, current),
            color = Ui.Text,
            fontFamily = BodyFont,
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp
        )
        when (check) {
            CheckState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = Ui.Gold, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.update_checking), color = Ui.Muted, fontFamily = BodyFont, fontSize = 14.sp)
            }
            is CheckState.Failed -> Text(
                stringResource(R.string.update_failed),
                color = Ui.Danger,
                fontFamily = BodyFont,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
            )
            CheckState.NoRelease -> Text(stringResource(R.string.update_no_release), color = Ui.Muted, fontFamily = BodyFont, fontSize = 14.sp)
            CheckState.Idle -> Unit
            else -> Text(
                stringResource(R.string.update_up_to_date),
                color = Ui.Teal,
                fontFamily = BodyFont,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
            )
        }
        Text(checkedText, color = Ui.Muted, fontFamily = BodyFont, fontSize = 13.sp)
    }
}

/** "NY VERSION" – version, storlek, förlopp, verifiering och ändringar (Directs UppdateringNy). */
@Composable
private fun NewVersionCard(release: ReleaseInfo, current: String, download: DownloadState, allowHint: Boolean) {
    val context = LocalContext.current
    val cardShape = RoundedCornerShape(22.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(Ui.Card)
            .border(1.5.dp, Ui.Gold, cardShape)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(stringResource(R.string.update_new), color = Ui.Gold, modifier = Modifier.weight(1f))
            Text(
                stringResource(if (release.prerelease) R.string.update_prerelease else R.string.update_stable),
                color = Ui.Bg,
                fontFamily = BodyFont,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (release.prerelease) Ui.Dim else Ui.Teal)
                    .padding(horizontal = 10.dp, vertical = 3.dp)
            )
        }
        Text(
            "SNC Camera ${release.version}",
            color = Ui.Text,
            fontFamily = DisplayFont,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            letterSpacing = (-0.4).sp
        )
        val size = if (release.apkSize > 0) Formatter.formatShortFileSize(context, release.apkSize) else "–"
        val released = if (release.publishedAt > 0) {
            DateUtils.getRelativeTimeSpanString(release.publishedAt, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS)
                .toString().lowercase()
        } else "–"
        Text(
            stringResource(R.string.update_meta, current, size, released),
            color = Ui.Muted,
            fontFamily = BodyFont,
            fontSize = 14.sp
        )

        // Förlopp
        val progress = when (download) {
            is DownloadState.Downloading -> download.progress
            is DownloadState.Ready -> 1f
            // Kontrollen efter nedladdningen misslyckades: filen kom fram, visa fullt (i rött)
            is DownloadState.Failed -> if (download.error == UpdateError.NETWORK) 0f else 1f
            else -> 0f
        }
        if (download is DownloadState.Downloading && progress < 0f) {
            LinearProgressIndicator(
                color = Ui.Gold,
                trackColor = Ui.Card2,
                strokeCap = StrokeCap.Round,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
            )
        } else {
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                color = if (download is DownloadState.Failed) Ui.Danger else Ui.Gold,
                trackColor = Ui.Card2,
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
                Ui.TealL
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
                Ui.Danger
            )
            else -> Unit
        }
        if (allowHint && download is DownloadState.Ready) {
            StatusLine(Icons.Outlined.Shield, stringResource(R.string.update_allow), Ui.GoldL)
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
                            .background(Ui.Gold)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(note, color = Ui.Muted, fontFamily = BodyFont, fontSize = 15.sp, lineHeight = 21.sp)
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


