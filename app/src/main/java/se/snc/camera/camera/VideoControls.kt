package se.snc.camera.camera

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import se.snc.camera.R
import se.snc.camera.ui.theme.OnGlassDim
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/** Tid mellan bilderna i tidsförlopp (millisekunder). */
val TimelapseIntervals = listOf(500, 1000, 2000, 5000)

/** "0,5 s", "1 s" … med decimaltecknet för språket. */
fun formatSeconds(ms: Int, locale: Locale): String {
    val f = DecimalFormat("0.#", DecimalFormatSymbols.getInstance(locale))
    return "${f.format(ms / 1000.0)} s"
}

/** Klipplängd som "0:12". */
fun formatClip(ms: Long): String {
    val s = ms / 1000
    return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

/**
 * Valen i videoläget: Video, Slow motion och Tidsförlopp. Under slow motion bildfrekvenserna kameran
 * klarar (eller att det inte stöds), under tidsförlopp tiden mellan bilderna och hur mycket snabbare
 * filmen blir.
 */
@Composable
fun VideoStylePicker(state: CameraUiState, onStyle: (VideoStyle) -> Unit, onFps: (Int) -> Unit, onInterval: (Int) -> Unit, modifier: Modifier = Modifier) {
    val locale = LocalConfiguration.current.locales[0]
    val enabled = !state.isRecording && state.countdown == 0
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        GlassChipRow {
            VideoStyle.entries.forEach { s ->
                GlassChip(stringResource(s.label), selected = s == state.videoStyle, enabled = enabled) { onStyle(s) }
            }
        }
        when (state.videoStyle) {
            VideoStyle.SLOW_MOTION -> {
                if (state.slowFpsOptions.isNotEmpty()) {
                    GlassChipRow {
                        state.slowFpsOptions.forEach { fps ->
                            GlassChip(stringResource(R.string.slowmo_fps, fps), selected = fps == state.slowFps, enabled = enabled) { onFps(fps) }
                        }
                    }
                    val size = state.slowSizeLabel
                    val factor = if (state.slowFps > 0) state.slowFps / 30 else 0
                    if (factor > 0) {
                        Text(
                            stringResource(R.string.slowmo_info, size ?: "", factor).trim(),
                            color = OnGlassDim,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                } else if (state.slowProbed) {
                    Text(stringResource(R.string.slowmo_unsupported), color = OnGlassDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            VideoStyle.TIMELAPSE -> {
                GlassChipRow {
                    TimelapseIntervals.forEach { ms ->
                        GlassChip(formatSeconds(ms, locale), selected = ms == state.timelapseIntervalMs, enabled = enabled) { onInterval(ms) }
                    }
                }
                Text(
                    stringResource(R.string.timelapse_info, state.timelapseIntervalMs * 30 / 1000),
                    color = OnGlassDim,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            VideoStyle.NORMAL -> Unit
        }
    }
}

/**
 * Sökaren för slow motion med Camera2: en TextureView som kameran ritar i. Bilden fyller rutan och
 * beskärs på mitten (som FILL_CENTER). [bufferWidth] × [bufferHeight] = kamerans bildstorlek (liggande).
 */
@Composable
fun SlowMotionPreview(
    bufferWidth: Int,
    bufferHeight: Int,
    onAvailable: (SurfaceTexture) -> Unit,
    onGone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val available by rememberUpdatedState(onAvailable)
    val gone by rememberUpdatedState(onGone)
    val bw by rememberUpdatedState(bufferWidth)
    val bh by rememberUpdatedState(bufferHeight)
    AndroidView(
        factory = { ctx ->
            TextureView(ctx).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                        centerCrop(this@apply, width, height, bw, bh)
                        available(st)
                    }

                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                        centerCrop(this@apply, width, height, bw, bh)
                    }

                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        gone()
                        return true
                    }

                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                }
            }
        },
        update = { tv -> centerCrop(tv, tv.width, tv.height, bufferWidth, bufferHeight) },
        modifier = modifier.fillMaxSize()
    )
}

/**
 * Kameran ritar bilden upprätt i TextureView men utsträckt till vyns storlek; skalan rättas så att bilden
 * behåller sin form och fyller vyn (beskuren på mitten). Skärmen är alltid stående.
 */
private fun centerCrop(view: TextureView, viewWidth: Int, viewHeight: Int, bufferWidth: Int, bufferHeight: Int) {
    if (viewWidth <= 0 || viewHeight <= 0 || bufferWidth <= 0 || bufferHeight <= 0) return
    val contentW = minOf(bufferWidth, bufferHeight).toFloat()
    val contentH = maxOf(bufferWidth, bufferHeight).toFloat()
    val scale = maxOf(viewWidth / contentW, viewHeight / contentH)
    val m = Matrix()
    m.setScale(contentW * scale / viewWidth, contentH * scale / viewHeight, viewWidth / 2f, viewHeight / 2f)
    view.setTransform(m)
}
