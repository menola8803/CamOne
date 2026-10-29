package se.snc.camera.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material.icons.rounded.CameraFront
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PhotoSizeSelectLarge
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import se.snc.camera.R
import se.snc.camera.data.AppSettings
import se.snc.camera.data.AspectOption
import se.snc.camera.data.GridOption
import se.snc.camera.data.SettingsRepository
import se.snc.camera.data.ThemeOption
import se.snc.camera.data.VideoQualityOption
import se.snc.camera.data.VolumeKeyAction
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

@StringRes
fun ThemeOption.labelRes() = when (this) {
    ThemeOption.SYSTEM -> R.string.theme_system
    ThemeOption.LIGHT -> R.string.theme_light
    ThemeOption.DARK -> R.string.theme_dark
}

/** Beskriver en flervalsdialog. */
private class ChoiceDialog(
    @StringRes val title: Int,
    val options: List<Any>,
    val selected: Any,
    val label: @Composable (Any) -> String,
    val onSelect: (Any) -> Unit
)

@Suppress("UNCHECKED_CAST")
private fun <T : Any> choice(
    @StringRes title: Int,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit
): ChoiceDialog = ChoiceDialog(
    title = title,
    options = options,
    selected = selected,
    label = { label(it as T) },
    onSelect = { onSelect(it as T) }
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    repository: SettingsRepository,
    onBack: () -> Unit,
    onAbout: () -> Unit
) {
    val settings by repository.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val scope = rememberCoroutineScope()
    fun update(transform: (AppSettings) -> AppSettings) {
        scope.launch { repository.update(transform) }
    }

    var dialog by remember { mutableStateOf<ChoiceDialog?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 24.dp,
                start = 16.dp,
                end = 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ---------------- Foto ----------------
            item {
                SettingsGroup(R.string.section_photo) {
                    ValueRow(
                        icon = Icons.Rounded.AspectRatio,
                        title = stringResource(R.string.setting_aspect),
                        value = settings.aspect.label,
                        onClick = {
                            dialog = choice(
                                R.string.setting_aspect, AspectOption.entries, settings.aspect, { it.label }
                            ) { v -> update { it.copy(aspect = v) } }
                        }
                    )
                    SwitchRow(
                        icon = Icons.Rounded.HighQuality,
                        title = stringResource(R.string.setting_max_quality),
                        subtitle = stringResource(R.string.setting_max_quality_desc),
                        checked = settings.maxQuality,
                        onChange = { v -> update { it.copy(maxQuality = v) } }
                    )
                    SliderRow(
                        icon = Icons.Rounded.PhotoSizeSelectLarge,
                        title = stringResource(R.string.setting_jpeg_quality),
                        value = settings.jpegQuality,
                        range = 70..100,
                        onChange = { v -> update { it.copy(jpegQuality = v) } }
                    )
                    SwitchRow(
                        icon = Icons.Rounded.CameraFront,
                        title = stringResource(R.string.setting_mirror_front),
                        subtitle = stringResource(R.string.setting_mirror_front_desc),
                        checked = settings.mirrorFront,
                        onChange = { v -> update { it.copy(mirrorFront = v) } }
                    )
                }
            }

            // ---------------- Video ----------------
            item {
                SettingsGroup(R.string.section_video) {
                    ValueRow(
                        icon = Icons.Rounded.Videocam,
                        title = stringResource(R.string.setting_video_quality),
                        value = settings.videoQuality.label,
                        onClick = {
                            dialog = choice(
                                R.string.setting_video_quality, VideoQualityOption.entries, settings.videoQuality, { it.label }
                            ) { v -> update { it.copy(videoQuality = v) } }
                        }
                    )
                    SwitchRow(
                        icon = Icons.Rounded.CenterFocusStrong,
                        title = stringResource(R.string.setting_stabilization),
                        subtitle = stringResource(R.string.setting_stabilization_desc),
                        checked = settings.videoStabilization,
                        onChange = { v -> update { it.copy(videoStabilization = v) } }
                    )
                    SwitchRow(
                        icon = Icons.Rounded.Mic,
                        title = stringResource(R.string.setting_record_audio),
                        checked = settings.recordAudio,
                        onChange = { v -> update { it.copy(recordAudio = v) } }
                    )
                }
            }

            // ---------------- Sökare ----------------
            item {
                SettingsGroup(R.string.section_viewfinder) {
                    ValueRow(
                        icon = Icons.Rounded.GridOn,
                        title = stringResource(R.string.setting_grid),
                        value = stringResource(settings.grid.labelRes()),
                        onClick = {
                            dialog = choice(
                                R.string.setting_grid, GridOption.entries, settings.grid, { stringResource(it.labelRes()) }
                            ) { v -> update { it.copy(grid = v) } }
                        }
                    )
                    SwitchRow(
                        icon = Icons.Rounded.Straighten,
                        title = stringResource(R.string.setting_level),
                        subtitle = stringResource(R.string.setting_level_desc),
                        checked = settings.levelIndicator,
                        onChange = { v -> update { it.copy(levelIndicator = v) } }
                    )
                }
            }

            // ---------------- Allmänt ----------------
            item {
                SettingsGroup(R.string.section_general) {
                    SwitchRow(
                        icon = Icons.Rounded.MusicNote,
                        title = stringResource(R.string.setting_shutter_sound),
                        checked = settings.shutterSound,
                        onChange = { v -> update { it.copy(shutterSound = v) } }
                    )
                    SwitchRow(
                        icon = Icons.Rounded.Vibration,
                        title = stringResource(R.string.setting_haptics),
                        checked = settings.haptics,
                        onChange = { v -> update { it.copy(haptics = v) } }
                    )
                    ValueRow(
                        icon = Icons.AutoMirrored.Rounded.VolumeUp,
                        title = stringResource(R.string.setting_volume_keys),
                        value = stringResource(settings.volumeKeyAction.labelRes()),
                        onClick = {
                            dialog = choice(
                                R.string.setting_volume_keys, VolumeKeyAction.entries, settings.volumeKeyAction,
                                { stringResource(it.labelRes()) }
                            ) { v -> update { it.copy(volumeKeyAction = v) } }
                        }
                    )
                    SwitchRow(
                        icon = Icons.Rounded.History,
                        title = stringResource(R.string.setting_keep_mode),
                        subtitle = stringResource(R.string.setting_keep_mode_desc),
                        checked = settings.keepLastMode,
                        onChange = { v -> update { it.copy(keepLastMode = v) } }
                    )
                    ValueRow(
                        icon = Icons.Rounded.Brightness6,
                        title = stringResource(R.string.setting_theme),
                        value = stringResource(settings.theme.labelRes()),
                        onClick = {
                            dialog = choice(
                                R.string.setting_theme, ThemeOption.entries, settings.theme, { stringResource(it.labelRes()) }
                            ) { v -> update { it.copy(theme = v) } }
                        }
                    )
                    SwitchRow(
                        icon = Icons.Rounded.ColorLens,
                        title = stringResource(R.string.setting_dynamic_color),
                        subtitle = stringResource(R.string.setting_dynamic_color_desc),
                        checked = settings.dynamicColor,
                        onChange = { v -> update { it.copy(dynamicColor = v) } }
                    )
                }
            }

            // ---------------- Om ----------------
            item {
                SettingsGroup(R.string.section_about) {
                    ValueRow(
                        icon = Icons.Rounded.Info,
                        title = stringResource(R.string.about_title),
                        value = stringResource(R.string.about_developer_value),
                        onClick = onAbout
                    )
                    ValueRow(
                        icon = Icons.Rounded.RestartAlt,
                        title = stringResource(R.string.setting_reset),
                        value = null,
                        onClick = { confirmReset = true }
                    )
                }
            }
        }
    }

    dialog?.let { d -> ChoiceDialogView(d) { dialog = null } }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.setting_reset)) },
            text = { Text(stringResource(R.string.setting_reset_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    update { AppSettings() }
                }) { Text(stringResource(R.string.reset)) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

@Composable
private fun ChoiceDialogView(dialog: ChoiceDialog, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(dialog.title)) },
        text = {
            Column {
                dialog.options.forEach { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .selectable(
                                selected = option == dialog.selected,
                                role = Role.RadioButton,
                                onClick = {
                                    dialog.onSelect(option)
                                    onDismiss()
                                }
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = option == dialog.selected, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(dialog.label(option), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
fun SettingsGroup(@StringRes title: Int, content: @Composable () -> Unit) {
    Column {
        Text(
            stringResource(title).uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
        )
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(vertical = 6.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun RowIcon(icon: ImageVector) {
    Box(
        Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun ValueRow(icon: ImageVector, title: String, value: String?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RowIcon(icon)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (value != null) {
                Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun SwitchRow(
    icon: ImageVector,
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    subtitle: String? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Switch) { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RowIcon(icon)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun SliderRow(icon: ImageVector, title: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    var local by remember(value) { mutableStateOf(value.toFloat()) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RowIcon(icon)
            Spacer(Modifier.width(16.dp))
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text("${local.roundToInt()}", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onChange(local.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first) / 5 - 1,
            modifier = Modifier
                .padding(start = 52.dp)
                .height(36.dp)
        )
    }
}
