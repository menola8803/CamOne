package se.snc.camera.editor

import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.snc.camera.R
import se.snc.camera.data.MediaRepository
import se.snc.camera.ui.theme.DisplayFont
import se.snc.camera.ui.theme.Usb
import se.snc.camera.ui.components.SquareIconButton
import se.snc.camera.ui.theme.CameraTheme
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

        // Den vridna bilden (hela) och den beskurna – beskärningsramen visas på den hela i fliken Beskär
        val oriented by produceState<Bitmap?>(null, base, edit.rotation, edit.flipHorizontal) {
            val b = base ?: return@produceState
            value = withContext(Dispatchers.Default) { b.transformed(edit.rotation, edit.flipHorizontal) }
        }
        val fullPreview = remember(oriented) { oriented?.asImageBitmap() }
        val croppedPreview by produceState<ImageBitmap?>(null, oriented, edit.crop) {
            val o = oriented ?: return@produceState
            value = withContext(Dispatchers.Default) { o.croppedTo(edit.crop).asImageBitmap() }
        }
        var cropRatio by remember { mutableStateOf<Pair<Float, Float>?>(null) }
        var cropTurned by remember { mutableStateOf(false) }
        val orientedW = oriented?.width ?: 0
        val orientedH = oriented?.height ?: 0
        val lockedRatio = cropRatio?.let { (long, short) ->
            if (long == short) 1f else CropMath.ratioFor(long, short, portrait = orientedH > orientedW, turned = cropTurned)
        }
        fun applyRatio(choice: Pair<Float, Float>?, turned: Boolean) {
            cropRatio = choice
            cropTurned = turned
            if (choice == null) return
            val r = if (choice.first == choice.second) 1f else CropMath.ratioFor(choice.first, choice.second, orientedH > orientedW, turned)
            edit = edit.copy(crop = CropMath.centered(orientedW, orientedH, r))
        }
        val cropping = tab == EditorTab.TRANSFORM
        val preview = if (cropping) fullPreview else (croppedPreview ?: fullPreview)
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
                .background(Usb.Bg)
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // Övre fältet
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SquareIconButton(Icons.Rounded.Close, stringResource(R.string.cd_close), { requestClose() })
                Text(
                    stringResource(R.string.editor_title),
                    color = Usb.Text,
                    fontFamily = DisplayFont,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 6.dp)
                )
                // Håll in för att jämföra med originalet
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (comparing) Usb.Hl else Usb.Card)
                        .pointerInput(Unit) {
                            detectTapGestures(onPress = {
                                comparing = true
                                tryAwaitRelease()
                                comparing = false
                            })
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Compare, contentDescription = stringResource(R.string.cd_compare), tint = if (comparing) Usb.GoldL else Usb.Muted)
                }
                SquareIconButton(
                    Icons.Rounded.RestartAlt,
                    stringResource(R.string.editor_reset),
                    { edit = EditState() },
                    enabled = !edit.isUnchanged
                )
                // Spara-knapp i guld (USB-appens .primary, kompakt)
                val canSave = !saving && !edit.isUnchanged && base != null
                Box(
                    Modifier
                        .height(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (canSave) Usb.Gold else Usb.Card2)
                        .clickable(enabled = canSave) { save() }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.editor_save),
                        color = if (canSave) Usb.Bg else Usb.Dim,
                        fontFamily = DisplayFont,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
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
                    CircularProgressIndicator(color = Usb.Gold)
                } else {
                    val vignette = if (comparing || cropping) 0f else edit.adjustments.vignette
                    Box(Modifier.aspectRatio(img.width.toFloat() / img.height.toFloat())) {
                        Image(
                            bitmap = img,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            colorFilter = if (comparing) null else colorFilter,
                            modifier = Modifier
                                .fillMaxSize()
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
                        if (cropping && !comparing) {
                            CropOverlay(
                                crop = edit.crop ?: CropRect.WHOLE,
                                imageW = orientedW,
                                imageH = orientedH,
                                ratio = lockedRatio,
                                onChange = { edit = edit.copy(crop = it) }
                            )
                        }
                    }
                }
                if (saving) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Usb.Bg.copy(alpha = 0.6f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Usb.Gold)
                            Spacer(Modifier.height(12.dp))
                            Text(stringResource(R.string.editor_saving), color = Usb.Text)
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

                    EditorTab.TRANSFORM -> Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Format: fritt eller låst (följer bildens riktning; Vänd byter stående/liggande)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val choices = listOf<Pair<String, Pair<Float, Float>?>>(
                                stringResource(R.string.crop_free) to null,
                                "1:1" to (1f to 1f),
                                "4:3" to (4f to 3f),
                                "3:2" to (3f to 2f),
                                "16:9" to (16f to 9f)
                            )
                            choices.forEach { (label, choice) ->
                                RatioChip(label, selected = cropRatio == choice) { applyRatio(choice, cropTurned) }
                            }
                            val canTurn = cropRatio != null && cropRatio?.first != cropRatio?.second
                            RatioChip(stringResource(R.string.crop_turn), selected = cropTurned && canTurn, enabled = canTurn) {
                                applyRatio(cropRatio, !cropTurned)
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            ToolButton(Icons.Rounded.RotateLeft, stringResource(R.string.editor_rotate_left), false, false) {
                                edit = edit.copy(rotation = (edit.rotation + 270) % 360, crop = edit.crop?.rotatedCcw())
                            }
                            ToolButton(Icons.Rounded.RotateRight, stringResource(R.string.editor_rotate_right), false, false) {
                                edit = edit.copy(rotation = (edit.rotation + 90) % 360, crop = edit.crop?.rotatedCw())
                            }
                            ToolButton(Icons.Rounded.Flip, stringResource(R.string.editor_flip), edit.flipHorizontal, false) {
                                // Spegling före vridningen: i en vriden bild blir det en lodrät spegling
                                val c = edit.crop?.let { if (edit.rotation % 180 == 0) it.flipped() else it.flippedV() }
                                edit = edit.copy(flipHorizontal = !edit.flipHorizontal, crop = c)
                            }
                            ToolButton(Icons.Rounded.Crop, stringResource(R.string.crop_whole), false, edit.crop?.isWhole == false) {
                                cropRatio = null
                                cropTurned = false
                                edit = edit.copy(crop = null)
                            }
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
                        Icon(t.icon, contentDescription = null, tint = if (selected) Usb.Gold else Usb.Dim)
                        Text(
                            stringResource(t.label),
                            color = if (selected) Usb.GoldL else Usb.Dim,
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
                        Text(stringResource(R.string.editor_discard), color = Usb.Danger, fontWeight = FontWeight.SemiBold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.cancel), color = Usb.Muted) }
                }
            )
        }
    }
}

@Composable
private fun RatioChip(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Text(
        label,
        color = when {
            selected -> Usb.Bg
            enabled -> Usb.Text
            else -> Usb.Dim
        },
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(shape)
            .background(if (selected) Usb.Gold else Usb.Card)
            .border(1.dp, if (selected) Usb.Gold else Usb.Line, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}

/**
 * Beskärningsramen ovanpå bilden: mörkt utanför, guldram med tredjedelslinjer och hörn. Dra i ett hörn
 * för att ändra storlek (med låst format följer den andra sidan med), dra inne i ramen för att flytta den.
 */
@Composable
private fun CropOverlay(crop: CropRect, imageW: Int, imageH: Int, ratio: Float?, onChange: (CropRect) -> Unit) {
    val current by rememberUpdatedState(crop)
    val lock by rememberUpdatedState(ratio)
    val change by rememberUpdatedState(onChange)
    val density = LocalDensity.current
    val slop = with(density) { 36.dp.toPx() }
    val stroke = with(density) { 2.dp.toPx() }
    val handle = with(density) { 18.dp.toPx() }
    val handleStroke = with(density) { 4.dp.toPx() }
    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(imageW, imageH) {
                var corner: CropCorner? = null
                var moving = false
                detectDragGestures(
                    onDragStart = { pos ->
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val h = size.height.toFloat().coerceAtLeast(1f)
                        val nx = pos.x / w
                        val ny = pos.y / h
                        val c = current
                        corner = CropMath.hitCorner(c, nx, ny, slop / w, slop / h)
                        moving = corner == null && nx in c.left..c.right && ny in c.top..c.bottom
                    },
                    onDragEnd = { corner = null; moving = false },
                    onDragCancel = { corner = null; moving = false },
                    onDrag = { pointer, drag ->
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val h = size.height.toFloat().coerceAtLeast(1f)
                        val dx = drag.x / w
                        val dy = drag.y / h
                        val k = corner
                        val next = when {
                            k != null -> CropMath.dragCorner(current, k, dx, dy, imageW, imageH, lock)
                            moving -> CropMath.move(current, dx, dy)
                            else -> null
                        }
                        if (next != null) {
                            pointer.consume()
                            change(next)
                        }
                    }
                )
            }
    ) {
        val l = crop.left * size.width
        val t = crop.top * size.height
        val r = crop.right * size.width
        val b = crop.bottom * size.height
        val shade = Color.Black.copy(alpha = 0.55f)
        drawRect(shade, topLeft = Offset.Zero, size = Size(size.width, t))
        drawRect(shade, topLeft = Offset(0f, b), size = Size(size.width, size.height - b))
        drawRect(shade, topLeft = Offset(0f, t), size = Size(l, b - t))
        drawRect(shade, topLeft = Offset(r, t), size = Size(size.width - r, b - t))
        val thirds = Color.White.copy(alpha = 0.35f)
        for (i in 1..2) {
            val x = l + (r - l) * i / 3f
            val y = t + (b - t) * i / 3f
            drawLine(thirds, Offset(x, t), Offset(x, b), strokeWidth = stroke / 2)
            drawLine(thirds, Offset(l, y), Offset(r, y), strokeWidth = stroke / 2)
        }
        drawRect(Usb.Gold, topLeft = Offset(l, t), size = Size(r - l, b - t), style = Stroke(stroke))
        // Hörnen
        fun corner(x: Float, y: Float, sx: Float, sy: Float) {
            drawLine(Usb.Gold, Offset(x, y), Offset(x + handle * sx, y), strokeWidth = handleStroke, cap = StrokeCap.Round)
            drawLine(Usb.Gold, Offset(x, y), Offset(x, y + handle * sy), strokeWidth = handleStroke, cap = StrokeCap.Round)
        }
        corner(l, t, 1f, 1f)
        corner(r, t, -1f, 1f)
        corner(l, b, 1f, -1f)
        corner(r, b, -1f, -1f)
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
                .border(if (selected) 2.dp else 1.dp, if (selected) Usb.Gold else Usb.Line, RoundedCornerShape(12.dp))
                .background(Usb.Card)
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
            color = if (selected) Usb.GoldL else Usb.Dim,
            fontSize = 12.sp,
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
            .background(if (selected) Usb.Card2 else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box {
            Icon(icon, contentDescription = null, tint = if (selected) Usb.Gold else Usb.Text, modifier = Modifier.size(26.dp))
            if (dot) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Usb.Gold)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = if (selected) Usb.GoldL else Usb.Dim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
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
            Text(label, color = Usb.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(display, color = Usb.GoldL, fontFamily = DisplayFont, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Usb.Gold,
                activeTrackColor = Usb.Gold,
                inactiveTrackColor = Usb.Card2
            ),
            modifier = Modifier.height(32.dp)
        )
    }
}
