package se.snc.camera.editor

import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.Lens
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.RotateLeft
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.snc.camera.R
import se.snc.camera.data.MediaRepository
import se.snc.camera.ui.theme.Accent
import se.snc.camera.ui.theme.CameraTheme
import se.snc.camera.ui.theme.Glass
import se.snc.camera.ui.theme.OnGlassDim
import kotlin.math.max
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.ColorMatrix as ComposeColorMatrix

private enum class EditorTab(val label: Int, val icon: ImageVector) {
    FILTERS(R.string.editor_filters, Icons.Rounded.AutoAwesome),
    ADJUST(R.string.editor_adjust, Icons.Rounded.Tune),
    TRANSFORM(R.string.editor_rotate, Icons.Rounded.Crop)
}

private enum class AdjustKind(val label: Int, val icon: ImageVector, val min: Float) {
    BRIGHTNESS(R.string.adjust_brightness, Icons.Rounded.Brightness6, -1f),
    CONTRAST(R.string.adjust_contrast, Icons.Rounded.Contrast, -1f),
    SATURATION(R.string.adjust_saturation, Icons.Rounded.Palette, -1f),
    WARMTH(R.string.adjust_warmth, Icons.Rounded.Thermostat, -1f),
    VIGNETTE(R.string.adjust_vignette, Icons.Rounded.Lens, 0f)
}

private fun Adjustments.get(kind: AdjustKind) = when (kind) {
    AdjustKind.BRIGHTNESS -> brightness
    AdjustKind.CONTRAST -> contrast
    AdjustKind.SATURATION -> saturation
    AdjustKind.WARMTH -> warmth
    AdjustKind.VIGNETTE -> vignette
}

private fun Adjustments.with(kind: AdjustKind, v: Float) = when (kind) {
    AdjustKind.BRIGHTNESS -> copy(brightness = v)
    AdjustKind.CONTRAST -> copy(contrast = v)
    AdjustKind.SATURATION -> copy(saturation = v)
    AdjustKind.WARMTH -> copy(warmth = v)
    AdjustKind.VIGNETTE -> copy(vignette = v)
}

private fun android.graphics.ColorMatrix.toCompose() = ComposeColorMatrix(array.copyOf())

@Composable
fun EditorScreen(
    media: MediaRepository,
    uri: Uri,
    onClose: () -> Unit,
    onSaved: (Uri) -> Unit
) {
    CameraTheme {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        val base by produceState<Bitmap?>(null, uri) { value = media.loadBitmap(uri, 2048) }
        val thumb by produceState<ImageBitmap?>(null, base) {
            val b = base ?: return@produceState
            value = withContext(Dispatchers.Default) {
                val s = 160f / max(b.width, b.height)
                Bitmap.createScaledBitmap(b, (b.width * s).roundToInt().coerceAtLeast(1), (b.height * s).roundToInt().coerceAtLeast(1), true)
                    .asImageBitmap()
            }
        }

        var edit by remember { mutableStateOf(EditState()) }
        var tab by remember { mutableStateOf(EditorTab.FILTERS) }
        var adjustKind by remember { mutableStateOf(AdjustKind.BRIGHTNESS) }
        var comparing by remember { mutableStateOf(false) }
        var saving by remember { mutableStateOf(false) }
        var confirmDiscard by remember { mutableStateOf(false) }

        val preview by produceState<ImageBitmap?>(null, base, edit.rotation, edit.flipHorizontal) {
            val b = base ?: return@produceState
            value = withContext(Dispatchers.Default) { b.transformed(edit.rotation, edit.flipHorizontal).asImageBitmap() }
        }
        val colorFilter = remember(edit.filter, edit.intensity, edit.adjustments) {
            ColorFilter.colorMatrix(buildColorMatrix(edit).toCompose())
        }

        fun requestClose() {
            if (edit.isUnchanged) onClose() else confirmDiscard = true
        }
        BackHandler { requestClose() }

        fun save() {
            if (saving) return
            saving = true
            scope.launch {
                val result = withContext(Dispatchers.Default) {
                    val full = media.loadBitmap(uri, 6000) ?: return@withContext null
                    val rendered = renderEdited(full, edit)
                    full.recycle()
                    val saved = media.saveBitmap(rendered, 95)
                    rendered.recycle()
                    saved
                }
                saving = false
                if (result != null) {
                    Toast.makeText(context, R.string.editor_saved, Toast.LENGTH_SHORT).show()
                    onSaved(result)
                } else {
                    Toast.makeText(context, R.string.editor_save_failed, Toast.LENGTH_LONG).show()
                }
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // Övre fältet
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { requestClose() }) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cd_close), tint = Color.White)
                }
                Text(
                    stringResource(R.string.editor_title),
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                    modifier = Modifier.weight(1f)
                )
                // Håll in för att jämföra med originalet
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(50))
                        .pointerInput(Unit) {
                            detectTapGestures(onPress = {
                                comparing = true
                                tryAwaitRelease()
                                comparing = false
                            })
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Compare, contentDescription = stringResource(R.string.cd_compare), tint = if (comparing) Accent else Color.White)
                }
                IconButton(onClick = { edit = EditState() }, enabled = !edit.isUnchanged) {
                    Icon(
                        Icons.Rounded.RestartAlt,
                        contentDescription = stringResource(R.string.editor_reset),
                        tint = if (edit.isUnchanged) Color.White.copy(alpha = 0.3f) else Color.White
                    )
                }
                Button(
                    onClick = { save() },
                    enabled = !saving && !edit.isUnchanged && base != null,
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Text(stringResource(R.string.editor_save))
                }
            }

            // Bild
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                val img = preview
                if (img == null) {
                    CircularProgressIndicator(color = Color.White)
                } else {
                    val vignette = if (comparing) 0f else edit.adjustments.vignette
                    Image(
                        bitmap = img,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        colorFilter = if (comparing) null else colorFilter,
                        modifier = Modifier
                            .aspectRatio(img.width.toFloat() / img.height.toFloat())
                            .drawWithContent {
                                drawContent()
                                if (vignette > 0f) {
                                    drawRect(
                                        Brush.radialGradient(
                                            0f to Color.Transparent,
                                            0.55f to Color.Transparent,
                                            1f to Color.Black.copy(alpha = 0.75f * vignette),
                                            center = center,
                                            radius = size.minDimension.coerceAtLeast(1f) * 0.5f * 1.414f
                                        )
                                    )
                                }
                            }
                    )
                }
                if (saving) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Color.White)
                            Spacer(Modifier.height(12.dp))
                            Text(stringResource(R.string.editor_saving), color = Color.White)
                        }
                    }
                }
            }

            // Verktygspanel
            AnimatedContent(
                targetState = tab,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "editorTab",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
            ) { current ->
                when (current) {
                    EditorTab.FILTERS -> Column(Modifier.fillMaxWidth()) {
                        if (edit.filter != PhotoFilter.ORIGINAL) {
                            LabeledSlider(
                                label = stringResource(R.string.editor_intensity),
                                value = edit.intensity,
                                range = 0f..1f,
                                display = "${(edit.intensity * 100).roundToInt()}%",
                                onChange = { edit = edit.copy(intensity = it) }
                            )
                        } else {
                            Spacer(Modifier.height(48.dp))
                        }
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)
                        ) {
                            items(PhotoFilter.entries) { f ->
                                FilterThumb(
                                    thumb = thumb,
                                    filter = f,
                                    selected = f == edit.filter,
                                    onClick = { edit = edit.copy(filter = f, intensity = 1f) }
                                )
                            }
                        }
                    }

                    EditorTab.ADJUST -> Column(Modifier.fillMaxWidth()) {
                        val value = edit.adjustments.get(adjustKind)
                        LabeledSlider(
                            label = stringResource(adjustKind.label),
                            value = value,
                            range = adjustKind.min..1f,
                            display = "${(value * 100).roundToInt()}",
                            onChange = { edit = edit.copy(adjustments = edit.adjustments.with(adjustKind, it)) }
                        )
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            AdjustKind.entries.forEach { k ->
                                val changed = edit.adjustments.get(k) != 0f
                                ToolButton(
                                    icon = k.icon,
                                    label = stringResource(k.label),
                                    selected = k == adjustKind,
                                    dot = changed,
                                    onClick = { adjustKind = k }
                                )
                            }
                        }
                    }

                    EditorTab.TRANSFORM -> Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 24.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        ToolButton(Icons.Rounded.RotateLeft, stringResource(R.string.editor_rotate_left), false, false) {
                            edit = edit.copy(rotation = (edit.rotation + 270) % 360)
                        }
                        ToolButton(Icons.Rounded.RotateRight, stringResource(R.string.editor_rotate_right), false, false) {
                            edit = edit.copy(rotation = (edit.rotation + 90) % 360)
                        }
                        ToolButton(Icons.Rounded.Flip, stringResource(R.string.editor_flip), edit.flipHorizontal, false) {
                            edit = edit.copy(flipHorizontal = !edit.flipHorizontal)
                        }
                    }
                }
            }

            // Flikar
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                EditorTab.entries.forEach { t ->
                    val selected = t == tab
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { tab = t }
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(t.icon, contentDescription = null, tint = if (selected) Accent else OnGlassDim)
                        Text(
                            stringResource(t.label),
                            color = if (selected) Color.White else OnGlassDim,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
            }
        }

        if (confirmDiscard) {
            AlertDialog(
                onDismissRequest = { confirmDiscard = false },
                title = { Text(stringResource(R.string.editor_discard_title)) },
                text = { Text(stringResource(R.string.editor_discard_text)) },
                confirmButton = {
                    TextButton(onClick = { confirmDiscard = false; onClose() }) {
                        Text(stringResource(R.string.editor_discard), color = Accent)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.cancel)) }
                }
            )
        }
    }
}

@Composable
private fun FilterThumb(thumb: ImageBitmap?, filter: PhotoFilter, selected: Boolean, onClick: () -> Unit) {
    val cf = remember(filter) { ColorFilter.colorMatrix(filter.matrix().toCompose()) }
    Column(
        Modifier
            .width(72.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(if (selected) 2.dp else 0.dp, if (selected) Accent else Color.Transparent, RoundedCornerShape(12.dp))
                .background(Glass)
        ) {
            if (thumb != null) {
                Image(
                    bitmap = thumb,
                    contentDescription = stringResource(filter.label),
                    contentScale = ContentScale.Crop,
                    colorFilter = cf,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(filter.label),
            color = if (selected) Color.White else OnGlassDim,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1
        )
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, selected: Boolean, dot: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) Glass else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box {
            Icon(icon, contentDescription = null, tint = if (selected) Accent else Color.White, modifier = Modifier.size(26.dp))
            if (dot) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Accent)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = if (selected) Color.White else OnGlassDim, fontSize = 11.sp)
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    onChange: (Float) -> Unit
) {
    Column(Modifier.padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = OnGlassDim, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text(display, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Accent,
                inactiveTrackColor = Color.White.copy(alpha = 0.2f)
            ),
            modifier = Modifier.height(32.dp)
        )
    }
}
