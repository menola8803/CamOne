package se.snc.camera.camera

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import se.snc.camera.data.GridOption
import se.snc.camera.ui.theme.CameraYellow
import se.snc.camera.ui.theme.GlassStrong
import se.snc.camera.ui.theme.Usb
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun GridOverlay(grid: GridOption, modifier: Modifier = Modifier) {
    if (grid == GridOption.OFF) return
    Canvas(modifier.fillMaxSize()) {
        val c = Color.White.copy(alpha = 0.45f)
        val stroke = 1.dp.toPx()
        val fractions: List<Float> = when (grid) {
            GridOption.THIRDS -> listOf(1f / 3f, 2f / 3f)
            GridOption.GOLDEN -> listOf(0.382f, 0.618f)
            GridOption.SQUARE -> listOf(0.25f, 0.5f, 0.75f)
            GridOption.OFF -> emptyList()
        }
        fractions.forEach { f ->
            drawLine(c, Offset(size.width * f, 0f), Offset(size.width * f, size.height), stroke)
            drawLine(c, Offset(0f, size.height * f), Offset(size.width, size.height * f), stroke)
        }
    }
}

/**
 * Vattenpass: en linje som visar lutning. Blir gul när telefonen är i våg.
 * Visas bara när lutningen är liten (som i OnePlus kamera).
 */
@Composable
fun LevelIndicator(tilt: Float?, quantized: Int, modifier: Modifier = Modifier) {
    if (tilt == null || abs(tilt) > 12f) return
    val level = abs(tilt) < 1f
    val color = if (level) CameraYellow else Usb.Text
    Canvas(modifier.fillMaxSize()) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val half = size.minDimension * 0.18f
        val gap = 10.dp.toPx()
        val sw = 1.5.dp.toPx()
        // Fasta referensstreck
        val horizontalRef = quantized == 0 || quantized == 180
        if (horizontalRef) {
            drawLine(color, Offset(cx - half - gap - 18.dp.toPx(), cy), Offset(cx - half - gap, cy), sw)
            drawLine(color, Offset(cx + half + gap, cy), Offset(cx + half + gap + 18.dp.toPx(), cy), sw)
        } else {
            drawLine(color, Offset(cx, cy - half - gap - 18.dp.toPx()), Offset(cx, cy - half - gap), sw)
            drawLine(color, Offset(cx, cy + half + gap), Offset(cx, cy + half + gap + 18.dp.toPx()), sw)
        }
        // Rörlig linje som följer horisonten
        val angle = (if (horizontalRef) 0.0 else Math.PI / 2) + Math.toRadians((if (level) 0f else -tilt).toDouble())
        val dx = kotlin.math.cos(angle).toFloat() * half
        val dy = kotlin.math.sin(angle).toFloat() * half
        drawLine(color, Offset(cx - dx, cy - dy), Offset(cx + dx, cy + dy), sw * 1.4f)
    }
}

/**
 * Fokusring som visas där man tryckte, med en sol-indikator för exponering bredvid.
 * [key] ändras vid varje tryck så att animationen startar om.
 */
@Composable
fun FocusRing(
    position: Offset?,
    key: Long,
    exposureFraction: Float?,
    evLabel: String?,
    rotation: Float,
    modifier: Modifier = Modifier
) {
    if (position == null) return
    val density = LocalDensity.current
    val ringSize = 72.dp
    val ringPx = with(density) { ringSize.toPx() }
    val scale = remember(key) { Animatable(1.4f) }
    val alpha = remember(key) { Animatable(1f) }
    LaunchedEffect(key) {
        scale.animateTo(1f, tween(220))
    }
    Box(modifier.fillMaxSize()) {
        Canvas(
            Modifier
                .offset { IntOffset((position.x - ringPx / 2).roundToInt(), (position.y - ringPx / 2).roundToInt()) }
                .size(ringSize)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    this.alpha = alpha.value
                }
        ) {
            drawCircle(CameraYellow, radius = size.minDimension / 2 - 2.dp.toPx(), style = Stroke(1.5.dp.toPx()))
            drawCircle(CameraYellow, radius = 2.dp.toPx())
        }
        if (exposureFraction != null) {
            val barHeight = 110.dp
            val barPx = with(density) { barHeight.toPx() }
            Box(
                Modifier.offset {
                    IntOffset(
                        (position.x + ringPx / 2 + with(density) { 14.dp.toPx() }).roundToInt(),
                        (position.y - barPx / 2).roundToInt()
                    )
                }
            ) {
                Canvas(Modifier.width(22.dp).height(barHeight)) {
                    val x = size.width / 2
                    val y = size.height * (1f - exposureFraction.coerceIn(0f, 1f))
                    val gap = 12.dp.toPx()
                    if (y - gap > 0) drawLine(CameraYellow, Offset(x, 0f), Offset(x, y - gap), 1.dp.toPx())
                    if (y + gap < size.height) drawLine(CameraYellow, Offset(x, y + gap), Offset(x, size.height), 1.dp.toPx())
                }
                val sunOffsetPx = barPx * (1f - exposureFraction.coerceIn(0f, 1f))
                Icon(
                    Icons.Rounded.WbSunny,
                    contentDescription = null,
                    tint = CameraYellow,
                    modifier = Modifier
                        .offset { IntOffset(0, (sunOffsetPx - with(density) { 11.dp.toPx() }).roundToInt()) }
                        .size(22.dp)
                        .rotate(rotation)
                )
            }
            if (evLabel != null) {
                Text(
                    evLabel,
                    color = CameraYellow,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.offset {
                        IntOffset(
                            (position.x - with(density) { 18.dp.toPx() }).roundToInt(),
                            (position.y + ringPx / 2 + with(density) { 6.dp.toPx() }).roundToInt()
                        )
                    }.rotate(rotation)
                )
            }
        }
    }
}

fun formatEv(index: Int, step: Float): String {
    val ev = index * step
    return if (abs(ev) < 0.05f) "0.0" else String.format(Locale.US, "%+.1f", ev)
}

@Composable
fun CountdownOverlay(seconds: Int, rotation: Float, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = seconds > 0,
        enter = fadeIn() + scaleIn(initialScale = 1.6f),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "$seconds",
                color = Usb.GoldL,
                fontSize = 120.sp,
                fontWeight = FontWeight.Light,
                modifier = Modifier.rotate(rotation)
            )
        }
    }
}

/** Kort informationsruta överst i sökaren. */
@Composable
fun InfoPill(text: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(50))
            .background(GlassStrong)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text, color = Usb.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}
