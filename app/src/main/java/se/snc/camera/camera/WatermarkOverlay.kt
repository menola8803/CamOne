package se.snc.camera.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.delay
import se.snc.camera.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Vattenstämpeln i sökaren: samma ritning och storlek som på fotot, i det hörn där den hamnar på bilden.
 * Hålls telefonen liggande vrids den med (fotot blir liggande). Uppgiftsraden visar bara datum och tid –
 * exponeringen är känd först när bilden tagits.
 */
@Composable
fun WatermarkOverlay(text: String, details: Boolean, deviceDegrees: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val fonts = remember { Watermark.fonts(context) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(20_000)
            now = System.currentTimeMillis()
        }
    }
    val line = if (details) WatermarkText.date(SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date(now))) else null
    val spec = Watermark.Spec(WatermarkText.title(text), line)
    val description = stringResource(R.string.cd_watermark_preview)
    Canvas(
        modifier
            .fillMaxSize()
            .semantics { contentDescription = description }
    ) {
        val d = ((deviceDegrees % 360) + 360) % 360
        val swap = d == 90 || d == 270
        val w = if (swap) size.height else size.width
        val h = if (swap) size.width else size.height
        drawIntoCanvas { c ->
            val nc = c.nativeCanvas
            nc.save()
            nc.translate(size.width / 2f, size.height / 2f)
            nc.rotate(-d.toFloat())
            nc.translate(-w / 2f, -h / 2f)
            Watermark.draw(nc, w, h, spec, fonts)
            nc.restore()
        }
    }
}
