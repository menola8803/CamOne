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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.launch
import se.snc.camera.ui.theme.GoldTint
import se.snc.camera.ui.theme.GoldEdge
import se.snc.camera.ui.theme.GlassLine
import se.snc.camera.ui.theme.GlassStrong
import androidx.compose.foundation.layout.width

/**
 * Rund glasknapp som i Alif: mörkt glas med tunn guldkant. Påslagen ([tint] = guld eller [active]) får
 * guldton och tydligare guldkant. [glow] = lysande guldring, t.ex. när en ny version finns.
 */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    rotation: Float = 0f,
    tint: Color = OnGlass,
    size: Dp = 44.dp,
    @Suppress("UNUSED_PARAMETER") background: Color = Color.Transparent,
    badge: String? = null,
    enabled: Boolean = true,
    dot: Boolean = false,
    active: Boolean = tint == CameraYellow,
    glow: Boolean = false
) {
    val on = active || glow
    Box(
        modifier = modifier
            .size(size)
            .then(
                if (glow) Modifier.drawBehind {
                    drawCircle(
                        Brush.radialGradient(
                            listOf(Usb.Gold.copy(alpha = 0.45f), Color.Transparent),
                            radius = this.size.minDimension * 0.9f
                        ),
                        radius = this.size.minDimension * 0.9f
                    )
                } else Modifier
            )
            .clip(CircleShape)
            .background(if (on) GoldTint else Glass)
            .border(if (glow) 2.dp else 1.dp, if (glow) Usb.Gold else if (on) GoldEdge else GlassLine, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.rotate(rotation), contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = contentDescription,
                tint = when {
                    !enabled -> tint.copy(alpha = 0.35f)
                    glow -> Usb.Gold
                    else -> tint
                },
                modifier = Modifier.size(size * 0.5f)
            )
            if (badge != null) {
                Text(
                    badge,
                    color = Usb.Bg,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 7.dp, y = 5.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(CameraYellow)
                        .padding(horizontal = 3.dp)
                )
            }
        }
        if (dot) {
            // guldprick, t.ex. när en ny version finns
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 2.dp, end = 2.dp)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(Usb.Bg)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(Usb.Gold)
            )
        }
    }
}

/** Textknapp i glas som visar t.ex. bildformat "4:3" eller "60 fps". */
@Composable
fun GlassTextButton(
    text: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    rotation: Float = 0f,
    enabled: Boolean = true,
    active: Boolean = false
) {
    Box(
        modifier = modifier
            .height(44.dp)
            .widthIn(min = 52.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(if (active) GoldTint else Glass)
            .border(1.dp, if (active) GoldEdge else GlassLine, RoundedCornerShape(22.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp)
            // Skärmläsare läser t.ex. "Bildformat, 4:3"
            .semantics(mergeDescendants = true) {
                this.contentDescription = contentDescription
                stateDescription = text
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = when {
                !enabled -> OnGlass.copy(alpha = 0.35f)
                active -> CameraYellow
                else -> OnGlass
            },
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.rotate(rotation)
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
    val innerSize by animateDpAsState(if (isRecording) 30.dp else 64.dp, spring(stiffness = 500f), label = "innerSize")
    val corner by animateDpAsState(if (isRecording) 8.dp else 32.dp, spring(stiffness = 500f), label = "innerCorner")

    Box(
        modifier = modifier
            .size(84.dp)
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
            .border(3.dp, if (isVideo) RecordRed.copy(alpha = 0.6f) else Usb.Gold.copy(alpha = 0.6f), CircleShape)
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
            .size(56.dp)
            .rotate(rotation)
            .clip(RoundedCornerShape(18.dp))
            .border(1.5.dp, Usb.Gold.copy(alpha = 0.6f), RoundedCornerShape(18.dp))
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
    GoldSquareButton(
        icon = Icons.Rounded.FlipCameraAndroid,
        contentDescription = stringResource(R.string.cd_switch_camera),
        onClick = {
            spinTarget += 180f
            onClick()
        },
        rotation = rotation + spin,
        enabled = enabled,
        modifier = modifier
    )
}

@Composable
fun PauseButton(isPaused: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    GoldSquareButton(
        icon = if (isPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
        contentDescription = stringResource(if (isPaused) R.string.cd_resume else R.string.cd_pause),
        onClick = onClick,
        modifier = modifier
    )
}

/** Rundad fyrkant med guldton och guldkant, som Alifs knappar (byt kamera, paus). */
@Composable
fun GoldSquareButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    rotation: Float = 0f,
    enabled: Boolean = true
) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier = modifier
            .size(56.dp)
            .clip(shape)
            .background(GoldTint)
            .border(1.dp, GoldEdge, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (enabled) Usb.Gold else Usb.Gold.copy(alpha = 0.35f),
            modifier = Modifier
                .size(26.dp)
                .rotate(rotation)
        )
    }
}

/**
 * Karusell med lägen, som i OnePlus-kameran: svep i sidled eller tryck på ett läge.
 * Raden följer fingret och landar på det läge som hamnar närmast mitten när man släpper.
 * Ett kort svep byter ett läge åt gången, ett längre eller snabbare svep kan hoppa flera.
 */
@Composable
fun ModeSelector(
    modes: List<CameraMode>,
    selected: CameraMode,
    enabled: Boolean,
    onSelect: (CameraMode) -> Unit,
    modifier: Modifier = Modifier
) {
    // Varje lägets x-position och bredd i raden, i px.
    val positions = remember { mutableStateMapOf<CameraMode, Pair<Float, Float>>() }
    var widthPx by remember { mutableIntStateOf(0) }
    // Radens förskjutning i px. NaN tills storlekarna är kända – då hamnar den direkt rätt.
    val offset = remember { Animatable(Float.NaN) }
    var dragging by remember { mutableStateOf(false) }
    var dragDistance by remember { mutableFloatStateOf(0f) }
    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val minSwipePx = with(density) { 24.dp.toPx() }
    val overscrollPx = with(density) { 40.dp.toPx() }

    fun targetFor(mode: CameraMode): Float? {
        if (widthPx == 0) return null
        return positions[mode]?.let { (x, w) -> widthPx / 2f - (x + w / 2f) }
    }

    /** Läget vars mitt ligger närmast radens mitt när raden är förskjuten [at] px. */
    fun closestTo(at: Float): CameraMode? {
        if (widthPx == 0) return null
        val probe = widthPx / 2f - at
        return modes.minByOrNull { m -> positions[m]?.let { (x, w) -> abs(x + w / 2f - probe) } ?: Float.MAX_VALUE }
    }

    val target = targetFor(selected)
    LaunchedEffect(target, dragging) {
        if (target == null || dragging) return@LaunchedEffect
        if (offset.value.isNaN()) offset.snapTo(target)
        else offset.animateTo(target, spring(stiffness = 350f, dampingRatio = 0.85f))
    }

    val dragState = rememberDraggableState { delta ->
        if (offset.value.isNaN()) return@rememberDraggableState
        dragDistance += delta
        scope.launch {
            val first = targetFor(modes.first())
            val last = targetFor(modes.last())
            var v = offset.value + delta
            if (first != null && last != null) v = v.coerceIn(last - overscrollPx, first + overscrollPx)
            offset.snapTo(v)
        }
    }

    // Under svepet lyses läget i mitten upp, så man ser var man landar.
    val highlighted by remember(selected) {
        derivedStateOf { if (dragging && !offset.value.isNaN()) closestTo(offset.value) ?: selected else selected }
    }

    // Bredden på läget i mitten – den guldtonade markeringen följer den.
    val litWidthPx = positions[highlighted]?.second ?: 0f
    val litWidth by animateDpAsState(with(density) { litWidthPx.toDp() }, spring(stiffness = 500f), label = "litWidth")

    // Svepet sitter på hela raden (även tomrummet på sidorna). Ett tryck går fortfarande till lägena,
    // eftersom svepet tar över först när fingret har rört sig en bit.
    // Utseende som Alifs meny: glas med tunn guldkant och guldtonad markering under läget i mitten.
    val barShape = RoundedCornerShape(24.dp)
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(60.dp)
            .clip(barShape)
            .background(GlassStrong)
            .border(1.dp, GlassLine, barShape)
            .onSizeChanged { widthPx = it.width }
            .draggable(
                state = dragState,
                orientation = Orientation.Horizontal,
                enabled = enabled,
                onDragStarted = {
                    dragging = true
                    dragDistance = 0f
                },
                onDragStopped = { velocity ->
                    val now = offset.value
                    if (!now.isNaN()) {
                        // Kasta lite i fingrets riktning, så ett snabbt svep går längre.
                        val fling = velocity * 0.12f
                        var choice = closestTo(now + fling) ?: currentSelected
                        val push = dragDistance + fling
                        if (choice == currentSelected && abs(push) > minSwipePx) {
                            val i = modes.indexOf(currentSelected) + if (push < 0) 1 else -1
                            if (i in modes.indices) choice = modes[i]
                        }
                        if (choice != currentSelected) currentOnSelect(choice)
                    }
                    dragging = false
                    dragDistance = 0f
                }
            )
    ) {
        if (litWidthPx > 0f) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .width(litWidth)
                    .height(50.dp)
                    .clip(RoundedCornerShape(19.dp))
                    .background(GoldTint)
                    .border(1.dp, Usb.Gold.copy(alpha = 0.5f), RoundedCornerShape(19.dp))
            ) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 7.dp)
                        .width(16.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Usb.Gold)
                )
            }
        }
        Row(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .wrapContentWidth(Alignment.Start, unbounded = true)
                .offset { IntOffset(offset.value.let { if (it.isNaN()) 0 else it.roundToInt() }, 0) }
                .graphicsLayer { alpha = if (offset.value.isNaN()) 0f else 1f },
            verticalAlignment = Alignment.CenterVertically
        ) {
            modes.forEach { mode ->
                val lit = mode == highlighted
                val color by animateColorAsState(if (lit) CameraYellow else OnGlassDim, label = "modeColor")
                Text(
                    text = stringResource(mode.label).uppercase(Locale.getDefault()),
                    color = color,
                    fontSize = 12.sp,
                    fontWeight = if (lit) FontWeight.ExtraBold else FontWeight.Bold,
                    letterSpacing = 0.6.sp,
                    modifier = Modifier
                        .onGloballyPositioned { c ->
                            positions[mode] = c.positionInParent().x to c.size.width.toFloat()
                        }
                        .clip(RoundedCornerShape(19.dp))
                        .selectable(selected = mode == selected, enabled = enabled, role = Role.Tab) { onSelect(mode) }
                        .padding(horizontal = 16.dp, vertical = 14.dp)
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
            .clip(RoundedCornerShape(26.dp))
            .background(Glass)
            .border(1.dp, GlassLine, RoundedCornerShape(26.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        stops.forEachIndexed { index, stop ->
            val active = index == activeIndex
            val size by animateDpAsState(if (active) 42.dp else 38.dp, label = "chipSize")
            val label = if (active) formatZoom(zoom, withX = true) else formatZoom(stop, withX = false)
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(if (active) GoldTint else Color.Transparent)
                    .border(1.dp, if (active) Usb.Gold.copy(alpha = 0.6f) else Color.Transparent, CircleShape)
                    .clickable(role = Role.Button) { onZoom(stop) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    color = if (active) CameraYellow else OnGlass,
                    fontSize = 13.sp,
                    fontWeight = if (active) FontWeight.ExtraBold else FontWeight.SemiBold,
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

/** Kort etikett som visar vilken lins som används, t.ex. "Tele · 47 mm". */
@Composable
fun LensPill(badge: LensBadge, rotation: Float, modifier: Modifier = Modifier) {
    val kind = stringResource(
        when (badge.kind) {
            se.snc.camera.info.LensKind.ULTRA_WIDE -> R.string.cams_kind_uw
            se.snc.camera.info.LensKind.WIDE -> R.string.cams_kind_wide
            se.snc.camera.info.LensKind.TELE -> R.string.cams_kind_tele
            se.snc.camera.info.LensKind.FRONT -> R.string.cams_kind_front
            se.snc.camera.info.LensKind.OTHER -> R.string.cams_kind_other
        }
    )
    Text(
        if (badge.eqMm != null) stringResource(R.string.lens_pill, kind, badge.eqMm) else kind,
        color = OnGlass,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .rotate(rotation)
            .clip(RoundedCornerShape(50))
            .background(GlassStrong)
            .border(1.dp, GlassLine, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 5.dp)
    )
}
