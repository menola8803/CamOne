package se.snc.camera.util

import android.view.OrientationEventListener
import android.view.Surface
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlin.math.abs

/**
 * @param quantized 0, 90, 180 eller 270 – hur telefonen hålls.
 * @param raw sensorns vinkel 0–359, eller -1 när telefonen ligger plant.
 */
data class DeviceOrientation(val quantized: Int = 0, val raw: Int = -1) {
    /** Lutning i grader relativt närmaste 90°-läge (för vattenpass). */
    val tilt: Float?
        get() {
            if (raw < 0) return null
            var d = (raw - quantized).toFloat()
            while (d > 180f) d -= 360f
            while (d < -180f) d += 360f
            return d
        }

    val surfaceRotation: Int
        get() = when (quantized) {
            90 -> Surface.ROTATION_270
            180 -> Surface.ROTATION_180
            270 -> Surface.ROTATION_90
            else -> Surface.ROTATION_0
        }
}

private fun angularDistance(a: Int, b: Int): Int {
    val d = abs(a - b) % 360
    return if (d > 180) 360 - d else d
}

@Composable
fun rememberDeviceOrientation(onChanged: (DeviceOrientation) -> Unit = {}): State<DeviceOrientation> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(DeviceOrientation()) }
    val callback by rememberUpdatedState(onChanged)
    DisposableEffect(context) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                val current = state.value
                if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) {
                    if (current.raw != -1) state.value = current.copy(raw = -1)
                    return
                }
                // Hysteres så att ikonerna inte fladdrar vid 45°
                val quantized = if (angularDistance(orientation, current.quantized) < 60) {
                    current.quantized
                } else {
                    (((orientation + 45) / 90) * 90) % 360
                }
                val next = DeviceOrientation(quantized, orientation)
                val quantizedChanged = quantized != current.quantized
                state.value = next
                if (quantizedChanged) callback(next)
            }
        }
        if (listener.canDetectOrientation()) listener.enable()
        callback(state.value)
        onDispose { listener.disable() }
    }
    return state
}

/**
 * Rotationsvinkel för ikoner så att de alltid är rättvända (som i OnePlus kamera).
 * Följer kortaste vägen så att ikonerna inte snurrar 270°.
 */
@Composable
fun rememberIconRotation(orientation: DeviceOrientation): Float {
    val target = -orientation.quantized.toFloat()
    val holder = remember { floatArrayOf(0f) }
    val continuous = remember(target) {
        var delta = (target - holder[0]) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        holder[0] += delta
        holder[0]
    }
    val animated by animateFloatAsState(
        targetValue = continuous,
        animationSpec = spring(stiffness = 300f, dampingRatio = 0.8f),
        label = "iconRotation"
    )
    return animated
}
