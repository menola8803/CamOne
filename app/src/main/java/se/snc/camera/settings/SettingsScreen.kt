package se.snc.camera.settings

import android.Manifest
import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import se.snc.camera.camera.Watermark
import se.snc.camera.camera.WatermarkText
import se.snc.camera.util.isGranted
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
import androidx.compose.material.icons.outlined.RestartAlt
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
import se.snc.camera.ui.theme.Ui
import se.snc.camera.update.UpdateManager
import kotlin.math.roundToInt
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import se.snc.camera.data.ThemeOption
import se.snc.camera.ui.components.PrimaryButton
import se.snc.camera.data.PhotoFormat

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
    onCameras: () -> Unit,
    onManual: () -> Unit,
    onUpdateNow: () -> Unit
) {
    val context = LocalContext.current
    val settings by repository.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val scope = rememberCoroutineScope()
    fun update(transform: (AppSettings) -> AppSettings) {
        scope.launch { repository.update(transform) }
    }
    var confirmReset by remember { mutableStateOf(false) }
    // Plats: behörigheten frågas först när inställningen slås på
    var locationDenied by remember { mutableStateOf(false) }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) {
            locationDenied = false
            update { it.copy(saveLocation = true) }
        } else {
            locationDenied = true
        }
    }
    val darkLabel = stringResource(R.string.theme_dark)
    val beigeLabel = stringResource(R.string.theme_beige)

    Column(
        Modifier
            .fillMaxSize()
            .background(Ui.Bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ScreenHeader(
            stringResource(R.string.settings_title),
            stringResource(R.string.cd_back),
            onBack,
            subtitle = "SNC Camera ${BuildConfig.VERSION_NAME}"
        )

        // ---------------- Ny version (som i Alif): kort överst med Uppdatera ----------------
        val newVersion = settings.latestKnownVersion.takeIf { it.isNotBlank() && UpdateManager.isNewer(it) }
        if (newVersion != null) {
            UpdateAvailableCard(version = newVersion, onUpdate = onUpdateNow)
        }

        // ---------------- Språk (som i USB-appen) ----------------
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeManager = remember { context.getSystemService(LocaleManager::class.java) }
            val current = if (LocalConfiguration.current.locales[0].language == "sv") "sv" else "en"
            UsbCard {
                SectionLabel(stringResource(R.string.setting_language))
                Segmented(
                    options = listOf("sv", "en"),
                    selected = current,
                    label = { if (it == "sv") "Svenska" else "English" },
                    onSelect = { tag -> localeManager?.applicationLocales = LocaleList.forLanguageTags(tag) }
                )
            }
        }

        // ---------------- Tema (som i Alif): Mörk eller Beige ----------------
        UsbCard {
            SectionLabel(stringResource(R.string.setting_theme))
            Segmented(
                options = ThemeOption.entries,
                selected = settings.theme,
                label = { if (it == ThemeOption.BEIGE) beigeLabel else darkLabel },
                onSelect = { v -> update { it.copy(theme = v) } }
            )
            Text(
                stringResource(R.string.setting_theme_desc),
                color = Ui.Muted,
                fontFamily = BodyFont,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
        }

        // ---------------- Foto ----------------
        UsbCard {
            SectionLabel(stringResource(R.string.section_photo))
            FieldLabel(stringResource(R.string.setting_aspect))
            ChoiceGrid(
                options = listOf(
                    AspectOption.RATIO_4_3, AspectOption.RATIO_3_2, AspectOption.RATIO_16_9,
                    AspectOption.RATIO_1_1, AspectOption.FULL
                ),
                selected = settings.aspect,
                label = { if (it == AspectOption.FULL) stringResource(R.string.aspect_full) else it.label },
                onSelect = { v -> update { it.copy(aspect = v) } }
            )
            FieldLabel(stringResource(R.string.setting_format))
            ChoiceGrid(
                options = PhotoFormat.entries,
                selected = settings.photoFormat,
                label = { it.label },
                onSelect = { v -> update { it.copy(photoFormat = v) } }
            )
            Text(
                stringResource(
                    when (settings.photoFormat) {
                        PhotoFormat.JPEG -> R.string.format_desc_jpeg
                        PhotoFormat.HEIF -> R.string.format_desc_heif
                        PhotoFormat.PNG -> R.string.format_desc_png
                        PhotoFormat.RAW -> R.string.format_desc_raw
                        PhotoFormat.RAW_JPEG -> R.string.format_desc_raw_jpeg
                    }
                ),
                color = Ui.Muted,
                fontFamily = BodyFont,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
            Spacer(Modifier.height(2.dp))
            ToggleRow(
                title = stringResource(R.string.setting_max_quality),
                subtitle = stringResource(R.string.setting_max_quality_desc),
                checked = settings.maxQuality,
                onChange = { v -> update { it.copy(maxQuality = v) } }
            )
            ToggleRow(
                title = stringResource(R.string.setting_ultra_hdr),
                subtitle = stringResource(R.string.setting_ultra_hdr_desc),
                checked = settings.ultraHdr,
                onChange = { v -> update { it.copy(ultraHdr = v) } }
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
            // Vattenstämpel: text i hörnet på foton (och i sökaren som förhandsvisning)
            ToggleRow(
                title = stringResource(R.string.setting_watermark),
                subtitle = stringResource(R.string.setting_watermark_desc),
                checked = settings.watermark,
                onChange = { v -> update { it.copy(watermark = v) } }
            )
            if (settings.watermark) {
                var text by rememberSaveable { mutableStateOf<String?>(null) }
                val shown = text ?: settings.watermarkText
                FieldLabel(stringResource(R.string.setting_watermark_text))
                WatermarkField(value = shown, onChange = { v ->
                    val t = v.replace("\n", " ").take(40)
                    text = t
                    update { it.copy(watermarkText = t) }
                })
                ToggleRow(
                    title = stringResource(R.string.setting_watermark_details),
                    subtitle = stringResource(R.string.setting_watermark_details_desc),
                    checked = settings.watermarkDetails,
                    onChange = { v -> update { it.copy(watermarkDetails = v) } }
                )
                FieldLabel(stringResource(R.string.setting_watermark_preview))
                WatermarkPreview(title = WatermarkText.title(shown), details = settings.watermarkDetails)
            }
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
            FieldLabel(stringResource(R.string.setting_video_fps))
            ChoiceGrid(
                options = listOf(30, 60),
                selected = settings.videoFps,
                label = { "$it fps" },
                onSelect = { v -> update { it.copy(videoFps = v) } },
                columns = 2
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
            ToggleRow(
                title = stringResource(R.string.setting_location),
                subtitle = stringResource(if (locationDenied) R.string.setting_location_denied else R.string.setting_location_desc),
                checked = settings.saveLocation,
                onChange = { v ->
                    if (!v) {
                        update { it.copy(saveLocation = false) }
                    } else if (context.isGranted(Manifest.permission.ACCESS_FINE_LOCATION) ||
                        context.isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)
                    ) {
                        locationDenied = false
                        update { it.copy(saveLocation = true) }
                    } else {
                        locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }
                }
            )
            FieldLabel(stringResource(R.string.setting_volume_keys))
            ChoiceGrid(
                options = VolumeKeyAction.entries,
                selected = settings.volumeKeyAction,
                label = { stringResource(it.labelRes()) },
                onSelect = { v -> update { it.copy(volumeKeyAction = v) } }
            )
        }

        // ---------------- Rader som i Alif: Kameror, Manual, Uppdateringar, Om ----------------
        UsbCard(padding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), spacing = 0.dp) {
            NavItem(
                title = stringResource(R.string.cams_title),
                subtitle = stringResource(R.string.cams_row_sub),
                onClick = onCameras,
                compact = true
            )
            NavItem(
                title = stringResource(R.string.manual_title),
                subtitle = stringResource(R.string.manual_row_sub),
                onClick = onManual,
                compact = true
            )
            NavItem(
                title = stringResource(R.string.update_title),
                subtitle = if (newVersion != null) stringResource(R.string.update_row_available, newVersion)
                else stringResource(R.string.update_row_sub),
                subtitleColor = if (newVersion != null) Ui.Gold else Ui.Muted,
                onClick = onUpdates,
                compact = true,
                dot = newVersion != null,
                value = "v${BuildConfig.VERSION_NAME}"
            )
            NavItem(
                title = stringResource(R.string.about_title),
                subtitle = stringResource(R.string.about_developer_line),
                onClick = onAbout,
                compact = true
            )
        }

        SecondaryButton(
            text = stringResource(R.string.setting_reset),
            onClick = { confirmReset = true },
            icon = Icons.Outlined.RestartAlt,
            modifier = Modifier.fillMaxWidth(),
            danger = true
        )

        Text(
            stringResource(R.string.version_footer, BuildConfig.VERSION_NAME),
            color = Ui.Dim,
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
            containerColor = Ui.Card,
            title = { Text(stringResource(R.string.setting_reset), fontFamily = DisplayFont, fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.setting_reset_confirm), color = Ui.Muted) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    update { AppSettings() }
                }) { Text(stringResource(R.string.reset), color = Ui.Danger, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.cancel), color = Ui.Muted) }
            }
        )
    }
}

/** Textfältet för vattenstämpelns text. */
@Composable
private fun WatermarkField(value: String, onChange: (String) -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = Ui.Text, fontFamily = BodyFont, fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
        cursorBrush = SolidColor(Ui.Gold),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(Ui.Card2)
                    .border(1.dp, Ui.Line, shape)
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                if (value.isEmpty()) Text(WatermarkText.DEFAULT_TITLE, color = Ui.Dim, fontFamily = BodyFont, fontSize = 16.sp)
                inner()
            }
        },
        modifier = Modifier.fillMaxWidth()
    )
}

/** Förhandsvisning av vattenstämpeln på en bild i kvällsljus (samma ritning som på fotot). */
@Composable
private fun WatermarkPreview(title: String, details: Boolean) {
    val context = LocalContext.current
    val fonts = remember { Watermark.fonts(context) }
    val sample = remember(details) {
        if (!details) null else WatermarkText.details(
            "24", "1.88", "0.008333", "100",
            SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date())
        )
    }
    val shape = RoundedCornerShape(16.dp)
    val description = stringResource(R.string.cd_watermark_preview)
    Canvas(
        Modifier
            .fillMaxWidth()
            .aspectRatio(4f / 3f)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    0f to Color(0xFF1C3B4A),
                    0.55f to Color(0xFF7A6A55),
                    1f to Color(0xFF2B2219)
                )
            )
            .semantics { contentDescription = description }
    ) {
        drawIntoCanvas { c ->
            Watermark.draw(c.nativeCanvas, size.width, size.height, Watermark.Spec(title, sample), fonts)
        }
    }
}

/** Kortet överst i Inställningar när en ny version finns (som i Alif). */
@Composable
private fun UpdateAvailableCard(version: String, onUpdate: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Ui.Card)
            .border(1.5.dp, Ui.Gold, shape)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            SectionLabel(stringResource(R.string.update_card_label), color = Ui.Gold)
            Text(
                "SNC Camera $version",
                color = Ui.Text,
                fontFamily = DisplayFont,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
            Text(
                stringResource(R.string.update_card_sub),
                color = Ui.Muted,
                fontFamily = BodyFont,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
        }
        Spacer(Modifier.width(12.dp))
        PrimaryButton(text = stringResource(R.string.update_now), onClick = onUpdate, height = 48.dp)
    }
}
