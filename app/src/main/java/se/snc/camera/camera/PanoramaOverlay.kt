package se.snc.camera.camera

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import se.snc.camera.R
import se.snc.camera.ui.theme.Glass
import se.snc.camera.ui.theme.GlassLine
import se.snc.camera.ui.theme.Usb
import kotlin.math.abs

/** Lutning (grader) som räknas som att telefonen håller linjen. */
private const val LEVEL_DEG = 4f

/**
 * Sökaren i läget Panorama: en guldlinje tvärs över mitten och en prick som visar lutningen (håll den på
 * linjen), det som hittills sytts ihop överst och en kort uppmaning nedtill.
 */
@Composable
fun PanoramaOverlay(
    active: Boolean,
    merging: Boolean,
    status: PanoramaStatus,
    preview: Bitmap?,
    /** Sökarens synfält på höjden (grader) – hur långt pricken flyttas per grad. */
    verticalFovDeg: Float,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val level = abs(status.pitchDeg) <= LEVEL_DEG
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(2.dp)
                .background(Usb.Gold.copy(alpha = 0.9f))
        )
        if (active) {
            val perDeg = maxHeight / verticalFovDeg.coerceAtLeast(10f)
            val offset = (perDeg * -status.pitchDeg).coerceIn(-maxHeight * 0.4f, maxHeight * 0.4f)
            Box(
                Modifier
                    .align(Alignment.Center)
                    .offset(y = offset)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Usb.Text.copy(alpha = 0.25f))
                    .border(3.dp, if (level) Usb.Gold else Usb.Danger, CircleShape)
            )
        }
        val bmp = preview
        if (bmp != null && (active || merging)) {
            val image = remember(bmp) { bmp.asImageBitmap() }
            val shape = RoundedCornerShape(14.dp)
            Image(
                bitmap = image,
                contentDescription = stringResource(R.string.cd_pano_preview),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp, start = 12.dp, end = 12.dp)
                    .fillMaxWidth()
                    .height(72.dp)
                    .clip(shape)
                    .background(Glass)
                    .border(1.dp, GlassLine, shape)
                    .padding(4.dp)
            )
        }
        val hint = when {
            merging -> stringResource(R.string.pano_merging)
            !active -> stringResource(R.string.pano_hint)
            status.tooFast -> stringResource(R.string.pano_slower)
            !level -> stringResource(R.string.pano_level)
            status.frames < 2 -> stringResource(R.string.pano_sweep)
            else -> stringResource(R.string.pano_progress, status.degrees, status.frames)
        }
        InfoPill(
            hint,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
                .semantics { contentDescription = hint }
        )
    }
}
