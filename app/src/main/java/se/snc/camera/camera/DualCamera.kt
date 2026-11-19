package se.snc.camera.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import se.snc.camera.R
import se.snc.camera.ui.theme.Usb
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Fram och bak samtidigt (läget Dubbel): bakkameran och frontkameran visas i var sin halva av sökaren
 * (CameraX samtidig kamera, två PreviewView). Håll in en halva och dra den förbi mitten för att byta
 * plats. Fotot sätts ihop av båda kamerornas bilder i samma ordning som i sökaren.
 */

/** Sökaren i läget Dubbel är 3:4; varje halva är då 3:2 (bredd/höjd). */
const val DUAL_HALF_RATIO = 1.5f

object DualCompose {

    /** Den mittersta delen av [b] med formatet [ratio] (bredd/höjd). */
    fun cropRect(b: Bitmap, ratio: Float): Rect {
        val br = b.width.toFloat() / b.height
        return if (br > ratio) {
            val w = (b.height * ratio).roundToInt().coerceIn(1, b.width)
            val x = (b.width - w) / 2
            Rect(x, 0, x + w, b.height)
        } else {
            val h = (b.width / ratio).roundToInt().coerceIn(1, b.height)
            val y = (b.height - h) / 2
            Rect(0, y, b.width, y + h)
        }
    }

    /**
     * Lägger [top] över [bottom], båda beskurna på mitten till [halfRatio], med en tunn guldlinje emellan
     * (som i sökaren), och vrider hela bilden [rotation] grader medurs så att den blir rättvänd när
     * telefonen hålls liggande. [filter] = live-filtret (null = inget).
     */
    fun stack(top: Bitmap, bottom: Bitmap, halfRatio: Float, rotation: Int, filter: ColorMatrix?): Bitmap {
        val w = minOf(top.width, bottom.width, 4096)
        val half = (w / halfRatio).roundToInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, half * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
            if (filter != null) colorFilter = ColorMatrixColorFilter(filter)
        }
        canvas.drawBitmap(top, cropRect(top, halfRatio), Rect(0, 0, w, half), paint)
        canvas.drawBitmap(bottom, cropRect(bottom, halfRatio), Rect(0, half, w, half * 2), paint)
        val line = (half / 160).coerceAtLeast(2)
        canvas.drawRect(
            0f, (half - line / 2).toFloat(), w.toFloat(), (half - line / 2 + line).toFloat(),
            Paint().apply { color = 0xFFC9A54A.toInt() }
        )
        val r = ((rotation % 360) + 360) % 360
        if (r == 0) return out
        val m = Matrix().apply { postRotate(r.toFloat()) }
        val rotated = Bitmap.createBitmap(out, 0, 0, out.width, out.height, m, true)
        if (rotated !== out) out.recycle()
        return rotated
    }
}

/**
 * Läget på de två halvorna i den delade sökaren. Bakkamerans sökare är appens vanliga PreviewView,
 * som ligger kvar på samma plats i vyträdet i alla lägen (bara storlek och läge ändras) – så att den
 * aldrig behöver flyttas till en annan förälder.
 */
@Stable
class DualLayout {
    val backY = Animatable(0f)
    val frontY = Animatable(0f)
    /** Vilken halva som dras: true = bakkameran, false = frontkameran, null = ingen. */
    var dragging by mutableStateOf<Boolean?>(null)
    internal var placed = false
}

@Composable
fun rememberDualLayout(): DualLayout = remember { DualLayout() }

/** Storlek och läge för en halva (bakkamerans eller frontkamerans sökare). */
fun Modifier.dualPane(layout: DualLayout, back: Boolean, halfDp: Dp): Modifier = this
    .fillMaxWidth()
    .height(halfDp)
    .offset { IntOffset(0, (if (back) layout.backY else layout.frontY).value.roundToInt()) }
    .zIndex(if (layout.dragging == back) 1f else 0f)

/**
 * Det som hör till den delade sökaren utöver bakkamerans sökare: frontkamerans sökare, guldlinjen och
 * gesterna. Långt tryck och dra flyttar halvan; släpps den förbi en tredjedel byter halvorna plats
 * ([onSwap]). Ett vågrätt svep byter läge ([onSwipe] med +1 = nästa, −1 = förra). Utan stöd för
 * samtidig kamera ([available] = false) visas ett besked i stället.
 */
@Composable
fun DualPanes(
    layout: DualLayout,
    frontView: PreviewView,
    heightPx: Int,
    swapped: Boolean,
    available: Boolean?,
    onSwap: () -> Unit,
    onSwipe: (Int) -> Unit
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val half = heightPx / 2f
    val halfDp = with(density) { half.toDp() }
    val swappedNow by rememberUpdatedState(swapped)
    val availableNow by rememberUpdatedState(available)
    val swap by rememberUpdatedState(onSwap)
    val swipe by rememberUpdatedState(onSwipe)

    fun slot(back: Boolean, sw: Boolean): Float = if (back != sw) 0f else half

    LaunchedEffect(swapped, half) {
        if (half <= 0f || layout.dragging != null) return@LaunchedEffect
        if (!layout.placed) {
            layout.placed = true
            layout.backY.snapTo(slot(true, swapped))
            layout.frontY.snapTo(slot(false, swapped))
        } else {
            launch { layout.backY.animateTo(slot(true, swapped), spring(stiffness = 500f)) }
            launch { layout.frontY.animateTo(slot(false, swapped), spring(stiffness = 500f)) }
        }
    }

    AndroidView(factory = { frontView }, modifier = Modifier.dualPane(layout, back = false, halfDp = halfDp))

    // Guldlinje mellan halvorna (inte medan en halva dras)
    if (layout.dragging == null && available != false && half > 0f) {
        Box(
            Modifier
                .fillMaxWidth()
                .offset { IntOffset(0, (half - with(density) { 1.dp.toPx() }).roundToInt()) }
                .height(2.dp)
                .zIndex(2f)
                .background(Usb.Gold.copy(alpha = 0.85f))
        )
    }

    if (available == false) {
        Box(
            Modifier
                .fillMaxSize()
                .zIndex(3f)
                .background(Usb.Bg),
            contentAlignment = Alignment.Center
        ) {
            Text(
                stringResource(R.string.dual_unsupported),
                color = Usb.Text,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(32.dp)
            )
        }
    }

    // Gester: långt tryck + dra = byt plats, vågrätt svep = byt läge
    Box(
        // Samma lager som sökarna (zIndex 0) och efter dem, så att etiketter och knappar ovanpå går att trycka på
        Modifier
            .fillMaxSize()
            .pointerInput(half) {
                val slop = viewConfiguration.touchSlop
                val swipeThreshold = 40.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var dx = 0f
                    var dy = 0f
                    var released = false
                    var moved = false
                    withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (!released && !moved) {
                            val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) {
                                released = true
                            } else {
                                val d = c.positionChange()
                                dx += d.x
                                dy += d.y
                                if (abs(dx) > slop || abs(dy) > slop) moved = true
                            }
                        }
                    }
                    if (released) return@awaitEachGesture
                    if (moved) {
                        // Svep: följ fingret till slut och byt läge om det var vågrätt
                        while (true) {
                            val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            val d = c.positionChange()
                            dx += d.x
                            dy += d.y
                        }
                        if (abs(dx) > swipeThreshold && abs(dx) > abs(dy) * 1.2f) swipe(if (dx < 0) 1 else -1)
                        return@awaitEachGesture
                    }
                    if (availableNow != true || half <= 0f) return@awaitEachGesture
                    // Långt tryck: halvan under fingret följer med
                    val sw = swappedNow
                    val back = if (down.position.y < half) !sw else sw
                    layout.dragging = back
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    val anim = if (back) layout.backY else layout.frontY
                    val start = slot(back, sw)
                    var offset = 0f
                    while (true) {
                        val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        offset += c.positionChange().y
                        c.consume()
                        val y = (start + offset).coerceIn(0f, half)
                        scope.launch { anim.snapTo(y) }
                    }
                    val end = (start + offset).coerceIn(0f, half)
                    val crossed = if (start == 0f) end > half / 3f else end < half * 2f / 3f
                    layout.dragging = null
                    if (crossed) {
                        swap()
                    } else {
                        scope.launch { anim.animateTo(start, spring(stiffness = 500f)) }
                    }
                }
            }
    )
}

/** Visas en kort stund när läget Dubbel öppnas: hur man byter plats. */
@Composable
fun DualHint(visible: Boolean, modifier: Modifier = Modifier) {
    if (!visible) return
    Text(
        stringResource(R.string.dual_hint),
        color = Usb.Text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .alpha(0.9f)
            .background(Usb.Bg.copy(alpha = 0.72f), androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}
