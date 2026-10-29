package se.snc.camera.text

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.util.Patterns
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Deselect
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import se.snc.camera.R
import se.snc.camera.data.MediaRepository
import se.snc.camera.ui.theme.Accent
import se.snc.camera.ui.theme.CameraTheme
import se.snc.camera.ui.theme.Glass
import se.snc.camera.ui.theme.OnGlassDim
import se.snc.camera.util.awaitTask
import se.snc.camera.util.shareText
import kotlin.math.min

/** En textrad som hittats i bilden, i bildens koordinater. */
data class OcrLine(val block: Int, val text: String, val box: Rect)

private sealed interface OcrState {
    data object Loading : OcrState
    data class Done(val bitmap: Bitmap, val lines: List<OcrLine>) : OcrState
    data object Failed : OcrState
}

fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return
    cm.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.app_name), text))
    // Android 13+ visar en egen bekräftelse
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, R.string.text_copied, Toast.LENGTH_SHORT).show()
    }
}

/** Sätter ihop markerade rader i läsordning – nytt stycke mellan textblock. */
private fun joinLines(lines: List<OcrLine>, selected: Set<Int>): String {
    val sb = StringBuilder()
    var lastBlock = -1
    lines.forEachIndexed { i, line ->
        if (i !in selected) return@forEachIndexed
        if (sb.isNotEmpty()) sb.append(if (line.block != lastBlock) "\n\n" else "\n")
        sb.append(line.text)
        lastBlock = line.block
    }
    return sb.toString()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextRecognitionScreen(
    media: MediaRepository,
    uri: Uri,
    onBack: () -> Unit
) {
    CameraTheme {
        val context = LocalContext.current
        val density = LocalDensity.current
        val recognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
        DisposableEffect(recognizer) { onDispose { recognizer.close() } }

        var state by remember { mutableStateOf<OcrState>(OcrState.Loading) }
        var selected by remember { mutableStateOf(setOf<Int>()) }
        var showSheet by remember { mutableStateOf(false) }
        var container by remember { mutableStateOf(IntSize.Zero) }

        LaunchedEffect(uri) {
            state = OcrState.Loading
            val bmp = media.loadBitmap(uri, 3000)
            if (bmp == null) {
                state = OcrState.Failed
                return@LaunchedEffect
            }
            state = runCatching {
                val result = recognizer.process(InputImage.fromBitmap(bmp, 0)).awaitTask()
                val lines = mutableListOf<OcrLine>()
                result.textBlocks.forEachIndexed { bi, block ->
                    block.lines.forEach { line ->
                        val box = line.boundingBox
                        if (box != null && line.text.isNotBlank()) lines += OcrLine(bi, line.text, box)
                    }
                }
                OcrState.Done(bmp, lines)
            }.getOrElse { OcrState.Failed }
        }

        val done = state as? OcrState.Done
        val lines = done?.lines.orEmpty()

        // Omvandling bild → skärm (ContentScale.Fit)
        val bw = done?.bitmap?.width ?: 1
        val bh = done?.bitmap?.height ?: 1
        val scale = if (container.width > 0) min(container.width.toFloat() / bw, container.height.toFloat() / bh) else 1f
        val left = (container.width - bw * scale) / 2f
        val top = (container.height - bh * scale) / 2f
        val pad = with(density) { 4.dp.toPx() }

        fun hit(p: Offset): Int? {
            var best: Int? = null
            var bestArea = Float.MAX_VALUE
            lines.forEachIndexed { i, l ->
                val r = l.box
                val x0 = left + r.left * scale - pad
                val y0 = top + r.top * scale - pad
                val x1 = left + r.right * scale + pad
                val y1 = top + r.bottom * scale + pad
                if (p.x in x0..x1 && p.y in y0..y1) {
                    val area = (x1 - x0) * (y1 - y0)
                    if (area < bestArea) {
                        bestArea = area
                        best = i
                    }
                }
            }
            return best
        }

        val selectedText = joinLines(lines, selected)
        val allText = joinLines(lines, lines.indices.toSet())

        Column(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.cd_back), tint = Color.White)
                }
                Text(
                    stringResource(R.string.text_title),
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (lines.isNotEmpty()) {
                    IconButton(onClick = { showSheet = true }) {
                        Icon(Icons.Rounded.Description, contentDescription = stringResource(R.string.text_show_all), tint = Color.White)
                    }
                }
            }

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .onSizeChanged { container = it },
                contentAlignment = Alignment.Center
            ) {
                when (val s = state) {
                    is OcrState.Loading -> CircularProgressIndicator(color = Color.White)
                    is OcrState.Failed -> Text(stringResource(R.string.text_failed), color = OnGlassDim)
                    is OcrState.Done -> {
                        Image(
                            bitmap = remember(s.bitmap) { s.bitmap.asImageBitmap() },
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                        // Dämpa bilden lite så att texten sticker ut
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = if (lines.isEmpty()) 0f else 0.25f))
                        )
                        Canvas(
                            Modifier
                                .fillMaxSize()
                                .pointerInput(lines, scale, left, top) {
                                    detectTapGestures(onTap = { p ->
                                        hit(p)?.let { i -> selected = if (i in selected) selected - i else selected + i }
                                    })
                                }
                                .pointerInput(lines, scale, left, top) {
                                    // Håll in och dra för att markera flera rader
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { p -> hit(p)?.let { selected = selected + it } },
                                        onDrag = { change, _ -> hit(change.position)?.let { selected = selected + it } }
                                    )
                                }
                        ) {
                            val corner = CornerRadius(4.dp.toPx())
                            lines.forEachIndexed { i, l ->
                                val r = l.box
                                val tl = Offset(left + r.left * scale - pad, top + r.top * scale - pad)
                                val sz = Size(r.width() * scale + 2 * pad, r.height() * scale + 2 * pad)
                                val isSel = i in selected
                                drawRoundRect(
                                    color = if (isSel) Accent.copy(alpha = 0.38f) else Color.White.copy(alpha = 0.16f),
                                    topLeft = tl,
                                    size = sz,
                                    cornerRadius = corner
                                )
                                drawRoundRect(
                                    color = if (isSel) Accent else Color.White.copy(alpha = 0.55f),
                                    topLeft = tl,
                                    size = sz,
                                    cornerRadius = corner,
                                    style = Stroke(if (isSel) 2.dp.toPx() else 1.dp.toPx())
                                )
                            }
                        }
                        if (lines.isEmpty()) {
                            Text(
                                stringResource(R.string.text_none_found),
                                color = Color.White,
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 16.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(Glass)
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
                if (state is OcrState.Loading) ScanSweep(show = true)
            }

            // Åtgärdsfält
            AnimatedContent(
                targetState = selected.isEmpty(),
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "textActions"
            ) { nothingSelected ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    if (nothingSelected) {
                        Text(
                            if (lines.isEmpty()) "" else stringResource(R.string.text_hint),
                            color = OnGlassDim,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ActionPill(Icons.Rounded.SelectAll, stringResource(R.string.text_select_all), enabled = lines.isNotEmpty()) {
                                selected = lines.indices.toSet()
                            }
                            ActionPill(Icons.Rounded.ContentCopy, stringResource(R.string.text_copy_all), enabled = lines.isNotEmpty()) {
                                copyToClipboard(context, allText)
                            }
                            ActionPill(Icons.Rounded.Share, stringResource(R.string.action_share), enabled = lines.isNotEmpty()) {
                                context.shareText(allText, context.getString(R.string.share_via))
                            }
                        }
                    } else {
                        Text(
                            pluralStringResource(R.plurals.text_lines_selected, selected.size, selected.size),
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ActionPill(Icons.Rounded.ContentCopy, stringResource(R.string.text_copy), primary = true) {
                                copyToClipboard(context, selectedText)
                            }
                            ActionPill(Icons.Rounded.Share, stringResource(R.string.action_share)) {
                                context.shareText(selectedText, context.getString(R.string.share_via))
                            }
                            SmartActions(selectedText)
                            ActionPill(Icons.Rounded.TextFields, stringResource(R.string.text_show)) { showSheet = true }
                            ActionPill(Icons.Rounded.Deselect, stringResource(R.string.text_deselect)) { selected = emptySet() }
                        }
                    }
                }
            }
        }

        if (showSheet) {
            val sheetText = if (selected.isEmpty()) allText else selectedText
            ModalBottomSheet(
                onDismissRequest = { showSheet = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = Color(0xFF17181C)
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 24.dp)
                ) {
                    Text(stringResource(R.string.text_sheet_title), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.text_sheet_hint), color = OnGlassDim, fontSize = 12.sp)
                    Spacer(Modifier.height(12.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(320.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White.copy(alpha = 0.06f))
                            .padding(14.dp)
                    ) {
                        SelectionContainer(Modifier.verticalScroll(rememberScrollState())) {
                            Text(sheetText, color = Color.White, fontSize = 16.sp, lineHeight = 23.sp)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionPill(Icons.Rounded.ContentCopy, stringResource(R.string.text_copy), primary = true) {
                            copyToClipboard(context, sheetText)
                        }
                        ActionPill(Icons.Rounded.Share, stringResource(R.string.action_share)) {
                            context.shareText(sheetText, context.getString(R.string.share_via))
                        }
                    }
                }
            }
        }
    }
}

/** Snabbval när den markerade texten är en länk, e-postadress eller ett telefonnummer. */
@Composable
private fun SmartActions(text: String) {
    val context = LocalContext.current
    val trimmed = text.trim()
    if (trimmed.isEmpty() || trimmed.length > 200) return
    when {
        Patterns.EMAIL_ADDRESS.matcher(trimmed).matches() ->
            ActionPill(Icons.Rounded.Email, stringResource(R.string.text_action_email)) {
                runCatching { context.startActivity(Intent(Intent.ACTION_SENDTO, "mailto:$trimmed".toUri())) }
            }
        Patterns.WEB_URL.matcher(trimmed).matches() ->
            ActionPill(Icons.Rounded.Language, stringResource(R.string.text_action_open)) {
                val url = if (trimmed.startsWith("http", ignoreCase = true)) trimmed else "https://$trimmed"
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
            }
        Patterns.PHONE.matcher(trimmed).matches() && trimmed.count { it.isDigit() } >= 6 ->
            ActionPill(Icons.Rounded.Call, stringResource(R.string.text_action_call)) {
                val number = trimmed.filter { it.isDigit() || it == '+' }
                runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, "tel:$number".toUri())) }
            }
    }
}

@Composable
private fun ActionPill(
    icon: ImageVector,
    label: String,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (primary) Accent else Glass)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val tint = if (enabled) Color.White else Color.White.copy(alpha = 0.35f)
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = tint, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/** Skanningsanimation medan texten läses in. */
@Composable
private fun ScanSweep(show: Boolean) {
    if (!show) return
    val transition = rememberInfiniteTransition(label = "scan")
    val y by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Reverse),
        label = "scanY"
    )
    Canvas(Modifier.fillMaxSize()) {
        val lineY = size.height * y
        drawRect(
            Brush.verticalGradient(
                0f to Color.Transparent,
                0.5f to Accent.copy(alpha = 0.35f),
                1f to Color.Transparent,
                startY = lineY - 60f,
                endY = lineY + 60f
            ),
            topLeft = Offset(0f, lineY - 60f),
            size = Size(size.width, 120f)
        )
    }
}
