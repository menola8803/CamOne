package se.snc.camera.settings

import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Camera
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import se.snc.camera.BuildConfig
import se.snc.camera.R
import se.snc.camera.data.AppSettings
import se.snc.camera.data.AspectOption
import se.snc.camera.data.GridOption
import se.snc.camera.data.SettingsRepository
import se.snc.camera.data.VideoQualityOption
import se.snc.camera.data.VolumeKeyAction
import se.snc.camera.ui.components.ChoiceGrid
import se.snc.camera.ui.components.FieldLabel
import se.snc.camera.ui.components.GoldSlider
import se.snc.camera.ui.components.NavItem
import se.snc.camera.ui.components.ScreenHeader
import se.snc.camera.ui.components.SecondaryButton
import se.snc.camera.ui.components.SectionLabel
import se.snc.camera.ui.components.Segmented
import se.snc.camera.ui.components.ToggleRow
import se.snc.camera.ui.components.UsbCard
import se.snc.camera.ui.theme.BodyFont
import se.snc.camera.ui.theme.DisplayFont
import se.snc.camera.ui.theme.Usb
import se.snc.camera.update.UpdateManager
import kotlin.math.roundToInt

@StringRes
fun GridOption.labelRes() = when (this) {
    GridOption.OFF -> R.string.grid_off
    GridOption.THIRDS -> R.string.grid_thirds
    GridOption.GOLDEN -> R.string.grid_golden
    GridOption.SQUARE -> R.string.grid_square
}

@StringRes
fun VolumeKeyAction.labelRes() = when (this) {
    VolumeKeyAction.SHUTTER -> R.string.volume_shutter
    VolumeKeyAction.ZOOM -> R.string.volume_zoom
    VolumeKeyAction.VOLUME -> R.string.volume_volume
}

@Composable
fun SettingsScreen(
    repository: SettingsRepository,
    onBack: () -> Unit,
    onAbout: () -> Unit,
    onUpdates: () -> Unit,
    onCameras: () -> Unit
) {
    val context = LocalContext.current
    val settings by repository.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val scope = rememberCoroutineScope()
    fun update(transform: (AppSettings) -> AppSettings) {
        scope.launch { repository.update(transform) }
    }
    var confirmReset by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Usb.Bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ScreenHeader(stringResource(R.string.settings_title), stringResource(R.string.cd_back), onBack)

        // ---------------- Språk (som i USB-appen) ----------------
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeManager = remember { context.getSystemService(LocaleManager::class.java) }
            val current = if (LocalConfiguration.current.locales[0].language == "sv") "sv" else "en"
            UsbCard {
                FieldLabel(stringResource(R.string.setting_language))
                Segmented(
                    options = listOf("sv", "en"),
                    selected = current,
                    label = { if (it == "sv") "Svenska" else "English" },
                    onSelect = { tag -> localeManager?.applicationLocales = LocaleList.forLanguageTags(tag) }
                )
            }
        }

        // ---------------- Foto ----------------
        UsbCard {
            SectionLabel(stringResource(R.string.section_photo))
            FieldLabel(stringResource(R.string.setting_aspect))
            ChoiceGrid(
                options = AspectOption.entries,
                selected = settings.aspect,
                label = { it.label },
                onSelect = { v -> update { it.copy(aspect = v) } }
            )
            Spacer(Modifier.height(2.dp))
            ToggleRow(
                title = stringResource(R.string.setting_max_quality),
                subtitle = stringResource(R.string.setting_max_quality_desc),
                checked = settings.maxQuality,
                onChange = { v -> update { it.copy(maxQuality = v) } }
            )
            var jpeg by remember(settings.jpegQuality) { mutableFloatStateOf(settings.jpegQuality.toFloat()) }
            GoldSlider(
                title = stringResource(R.string.setting_jpeg_quality),
                valueText = "${jpeg.roundToInt()}",
                value = jpeg,
                onValueChange = { jpeg = it },
                onValueChangeFinished = { val v = jpeg.roundToInt(); update { it.copy(jpegQuality = v) } },
                valueRange = 70f..100f,
                steps = 5
            )
            ToggleRow(
                title = stringResource(R.string.setting_mirror_front),
                subtitle = stringResource(R.string.setting_mirror_front_desc),
                checked = settings.mirrorFront,
                onChange = { v -> update { it.copy(mirrorFront = v) } }
            )
        }

        // ---------------- Video ----------------
        UsbCard {
            SectionLabel(stringResource(R.string.section_video))
            FieldLabel(stringResource(R.string.setting_video_quality))
            ChoiceGrid(
                options = VideoQualityOption.entries,
                selected = settings.videoQuality,
                label = { it.label },
                onSelect = { v -> update { it.copy(videoQuality = v) } }
            )
            Spacer(Modifier.height(2.dp))
            ToggleRow(
                title = stringResource(R.string.setting_stabilization),
                subtitle = stringResource(R.string.setting_stabilization_desc),
                checked = settings.videoStabilization,
                onChange = { v -> update { it.copy(videoStabilization = v) } }
            )
            ToggleRow(
                title = stringResource(R.string.setting_record_audio),
                checked = settings.recordAudio,
                onChange = { v -> update { it.copy(recordAudio = v) } }
            )
        }

        // ---------------- Sökare ----------------
        UsbCard {
            SectionLabel(stringResource(R.string.section_viewfinder))
            FieldLabel(stringResource(R.string.setting_grid))
            ChoiceGrid(
                options = GridOption.entries,
                selected = settings.grid,
                label = { stringResource(it.labelRes()) },
                onSelect = { v -> update { it.copy(grid = v) } },
                columns = 2
            )
            Spacer(Modifier.height(2.dp))
            ToggleRow(
                title = stringResource(R.string.setting_level),
                subtitle = stringResource(R.string.setting_level_desc),
                checked = settings.levelIndicator,
                onChange = { v -> update { it.copy(levelIndicator = v) } }
            )
        }

        // ---------------- Allmänt ----------------
        UsbCard {
            SectionLabel(stringResource(R.string.section_general))
            ToggleRow(
                title = stringResource(R.string.setting_shutter_sound),
                checked = settings.shutterSound,
                onChange = { v -> update { it.copy(shutterSound = v) } }
            )
            ToggleRow(
                title = stringResource(R.string.setting_haptics),
                checked = settings.haptics,
                onChange = { v -> update { it.copy(haptics = v) } }
            )
            ToggleRow(
                title = stringResource(R.string.setting_keep_mode),
                subtitle = stringResource(R.string.setting_keep_mode_desc),
                checked = settings.keepLastMode,
                onChange = { v -> update { it.copy(keepLastMode = v) } }
            )
            FieldLabel(stringResource(R.string.setting_volume_keys))
            ChoiceGrid(
                options = VolumeKeyAction.entries,
                selected = settings.volumeKeyAction,
                label = { stringResource(it.labelRes()) },
                onSelect = { v -> update { it.copy(volumeKeyAction = v) } }
            )
        }

        // ---------------- Kameror: vad telefonen klarar ----------------
        NavItem(
            title = stringResource(R.string.cams_title),
            subtitle = stringResource(R.string.cams_row_sub),
            onClick = onCameras,
            leading = Icons.Outlined.Camera
        )

        // ---------------- Uppdateringar (som i Direct) ----------------
        val newVersion = settings.latestKnownVersion.takeIf { it.isNotBlank() && UpdateManager.isNewer(it) }
        NavItem(
            title = stringResource(R.string.update_title),
            subtitle = if (newVersion != null) stringResource(R.string.update_row_available, newVersion)
            else stringResource(R.string.update_row_sub),
            subtitleColor = if (newVersion != null) Usb.GoldL else Usb.Dim,
            onClick = onUpdates,
            leading = Icons.Outlined.SystemUpdate,
            dot = newVersion != null
        )

        // ---------------- Om ----------------
        NavItem(
            title = stringResource(R.string.about_title),
            subtitle = stringResource(R.string.about_developer_line),
            onClick = onAbout,
            leading = Icons.Outlined.Info
        )

        SecondaryButton(
            text = stringResource(R.string.setting_reset),
            onClick = { confirmReset = true },
            icon = Icons.Outlined.RestartAlt,
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            stringResource(R.string.version_footer, BuildConfig.VERSION_NAME),
            color = Usb.Dim,
            fontFamily = BodyFont,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp)
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            containerColor = Usb.Card,
            title = { Text(stringResource(R.string.setting_reset), fontFamily = DisplayFont, fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.setting_reset_confirm), color = Usb.Muted) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    update { AppSettings() }
                }) { Text(stringResource(R.string.reset), color = Usb.Gold, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.cancel), color = Usb.Muted) }
            }
        )
    }
}
