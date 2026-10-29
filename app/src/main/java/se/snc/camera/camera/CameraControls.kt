package se.snc.camera.camera

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FlipCameraAndroid
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.offset
import se.snc.camera.R
import se.snc.camera.ui.theme.RecordRed
import se.snc.camera.ui.theme.Usb
import se.snc.camera.ui.theme.CameraYellow
import se.snc.camera.ui.theme.Glass
import se.snc.camera.ui.theme.OnGlass
import se.snc.camera.ui.theme.OnGlassDim
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Rund ikonknapp med glaseffekt, roterar med telefonen. */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    rotation: Float = 0f,
    tint: Color = OnGlass,
    size: Dp = 40.dp,
    background: Color = Color.Transparent,
    badge: String? = null,
    enabled: Boolean = true
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.rotate(rotation), contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = contentDescription,
                tint = if (enabled) tint else tint.copy(alpha = 0.35f),
                modifier = Modifier.size(size * 0.58f)
            )
            if (badge != null) {
                Text(
                    badge,
                    color = Usb.Bg,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 6.dp, y = 4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(CameraYellow)
                        .padding(horizontal = 3.dp)
                )
            }
        }
    }
}

/** Textknapp som visar t.ex. bildformat "4:3". */
@Composable
fun GlassTextButton(
    text: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    rotation: Float = 0f,
    enabled: Boolean = true
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            // Skärmläsare läser t.ex. "Bildformat, 4:3"
            .semantics(mergeDescendants = true) {
                this.contentDescription = contentDescription
                stateDescription = text
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (enabled) OnGlass else OnGlass.copy(alpha = 0.35f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .rotate(rotation)
                .border(1.5.dp, if (enabled) OnGlass else OnGlass.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp)
        )
    }
}

/** Avtryckare i USB-stil: guldknapp med sken (som USB-appens huvudknapp), korallröd för video. */
@Composable
fun ShutterButton(
    isVideo: Boolean,
    isRecording: Boolean,
    isBusy: Boolean,
    countdown: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, spring(stiffness = 600f), label = "shutterScale")
    val innerColor by animateColorAsState(
        when {
            isVideo -> RecordRed
            isBusy -> Usb.Gold.copy(alpha = 0.55f)
            else -> Usb.Gold
        },
        label = "shutterColor"
    )
    val innerSize by animateDpAsState(if (isRecording) 30.dp else 62.dp, spring(stiffness = 500f), label = "innerSize")
    val corner by animateDpAsState(if (isRecording) 8.dp else 31.dp, spring(stiffness = 500f), label = "innerCorner")

    Box(
        modifier = modifier
            .size(78.dp)
            .scale(scale)
            .drawBehind {
                // mjukt sken, som box-shadow på USB-appens .mic
                drawCircle(
                    Brush.radialGradient(
                        listOf(innerColor.copy(alpha = 0.30f), Color.Transparent),
                        radius = size.minDimension * 0.85f
                    ),
                    radius = size.minDimension * 0.85f
                )
            }
            .border(3.dp, Usb.Text, CircleShape)
            .clip(CircleShape)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(innerSize)
                .clip(RoundedCornerShape(corner))
                .background(innerColor)
        )
        if (countdown > 0) {
            Text(
                "$countdown",
                color = Usb.Bg,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/** Miniatyr av senaste bilden – öppnar bildvisaren. */
@Composable
fun GalleryThumbnail(
    bitmap: Bitmap?,
    isVideo: Boolean,
    rotation: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(52.dp)
            .rotate(rotation)
            .clip(CircleShape)
            .border(2.dp, Usb.Gold.copy(alpha = 0.75f), CircleShape)
            .background(Glass)
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.cd_open_gallery), onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = bitmap,
            transitionSpec = { (scaleIn(initialScale = 0.6f) + fadeIn()) togetherWith fadeOut(tween(150)) },
            label = "thumb"
        ) { bmp ->
            if (bmp != null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = stringResource(R.string.cd_open_gallery),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    if (isVideo) {
                        Icon(
                            Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.4f))
                        )
                    }
                }
            } else {
                Icon(
                    Icons.Rounded.PhotoLibrary,
                    contentDescription = stringResource(R.string.cd_open_gallery),
                    tint = OnGlass,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
fun SwitchLensButton(rotation: Float, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var spinTarget by remember { mutableFloatStateOf(0f) }
    val spin by animateFloatAsState(spinTarget, tween(400), label = "lensSpin")
    GlassIconButton(
        icon = Icons.Rounded.FlipCameraAndroid,
        contentDescription = stringResource(R.string.cd_switch_camera),
        onClick = {
            spinTarget += 180f
            onClick()
        },
        rotation = rotation + spin,
        size = 52.dp,
        background = Glass,
        enabled = enabled,
        modifier = modifier
    )
}

@Composable
fun PauseButton(isPaused: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    GlassIconButton(
        icon = if (isPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
        contentDescription = stringResource(if (isPaused) R.string.cd_resume else R.string.cd_pause),
        onClick = onClick,
        size = 52.dp,
        background = Glass,
        modifier = modifier
    )
}

/** Karusell med lägen. Valt läge centreras och markeras med accentfärg. */
@Composable
fun ModeSelector(
    modes: List<CameraMode>,
    selected: CameraMode,
    enabled: Boolean,
    onSelect: (CameraMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val positions = remember { mutableStateMapOf<CameraMode, Pair<Float, Float>>() }
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .clipToBounds()
    ) {
        val center = constraints.maxWidth / 2f
        val p = positions[selected]
        val target = if (p != null) center - (p.first + p.second / 2f) else 0f
        val offset by animateFloatAsState(target, spring(stiffness = 350f, dampingRatio = 0.85f), label = "modeOffset")
        Row(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .wrapContentWidth(Alignment.Start, unbounded = true)
                .offset { IntOffset(offset.roundToInt(), 0) },
            verticalAlignment = Alignment.CenterVertically
        ) {
            modes.forEach { mode ->
                val isSelected = mode == selected
                val color by animateColorAsState(if (isSelected) CameraYellow else OnGlassDim, label = "modeColor")
                Text(
                    text = stringResource(mode.label).uppercase(Locale.getDefault()),
                    color = color,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    letterSpacing = 1.2.sp,
                    modifier = Modifier
                        .onGloballyPositioned { c ->
                            positions[mode] = c.positionInParent().x to c.size.width.toFloat()
                        }
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(enabled = enabled, role = Role.Tab) { onSelect(mode) }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }
    }
}

/** Zoomknappar (0.6×, 1×, 2× …). Den aktiva visar exakt zoomvärde. */
@Composable
fun ZoomChips(
    zoom: Float,
    minZoom: Float,
    maxZoom: Float,
    rotation: Float,
    onZoom: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val stops = buildList {
        if (minZoom < 0.95f) add(minZoom)
        add(1f)
        if (maxZoom >= 2f) add(2f)
        if (maxZoom >= 5f) add(5f)
    }
    if (stops.size < 2 && maxZoom <= 1f) return
    // Vilken knapp är "aktiv"? Den närmaste stoppet under eller lika med aktuell zoom.
    val activeIndex = stops.indices.lastOrNull { zoom >= stops[it] - 0.01f } ?: 0
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Glass)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        stops.forEachIndexed { index, stop ->
            val active = index == activeIndex
            val size by animateDpAsState(if (active) 38.dp else 32.dp, label = "chipSize")
            val label = if (active) formatZoom(zoom, withX = true) else formatZoom(stop, withX = false)
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(if (active) Usb.Bg.copy(alpha = 0.8f) else Color.Transparent)
                    .clickable(role = Role.Button) { onZoom(stop) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    color = if (active) CameraYellow else OnGlass,
                    fontSize = if (active) 12.sp else 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.rotate(rotation)
                )
            }
        }
    }
}

fun formatZoom(value: Float, withX: Boolean): String {
    val rounded = (value * 10f).roundToInt() / 10f
    val text = if (abs(rounded - rounded.roundToInt()) < 0.05f && rounded >= 1f) {
        rounded.roundToInt().toString()
    } else {
        String.format(Locale.US, "%.1f", rounded).removePrefix("0")
    }
    return if (withX) "$text×" else text
}

/** Röd pill med inspelningstid. */
@Composable
fun RecordingTimer(ms: Long, paused: Boolean, modifier: Modifier = Modifier) {
    val totalSec = ms / 1000
    val text = String.format(Locale.US, "%02d:%02d", totalSec / 60, totalSec % 60)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (paused) Glass else RecordRed)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(Color.White)
        )
        Text(text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}
