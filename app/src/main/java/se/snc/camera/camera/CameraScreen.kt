package se.snc.camera.camera

import android.Manifest
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FlashAuto
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.GridOff
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.HdrOff
import androidx.compose.material.icons.rounded.HdrOn
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.TimerOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import se.snc.camera.R
import se.snc.camera.data.FlashOption
import se.snc.camera.data.GridOption
import se.snc.camera.data.TimerOption
import se.snc.camera.ui.theme.CameraTheme
import se.snc.camera.ui.theme.CameraYellow
import se.snc.camera.ui.theme.DisplayFont
import se.snc.camera.ui.theme.Usb
import se.snc.camera.update.UpdateManager
import se.snc.camera.ui.components.HeroButton
import se.snc.camera.ui.components.MutedText
import se.snc.camera.ui.components.PrimaryButton
import se.snc.camera.ui.components.SecondaryButton
import se.snc.camera.ui.components.StatusPill
import se.snc.camera.ui.components.Wordmark
import androidx.compose.material.icons.outlined.CameraAlt
import se.snc.camera.util.HardwareKeys
import se.snc.camera.util.KeyCommand
import se.snc.camera.util.findActivity
import se.snc.camera.util.isGranted
import se.snc.camera.util.openAppSettings
import se.snc.camera.util.rememberDeviceOrientation
import se.snc.camera.util.rememberIconRotation
import kotlin.math.abs

@Composable
fun CameraScreen(
    viewModel: CameraViewModel,
    onOpenGallery: (Uri?) -> Unit,
    onOpenSettings: () -> Unit
) {
    CameraTheme {
        val context = LocalContext.current
        var hasCamera by remember { mutableStateOf(context.isGranted(Manifest.permission.CAMERA)) }
        var askedOnce by remember { mutableStateOf(false) }
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            hasCamera = context.isGranted(Manifest.permission.CAMERA)
            askedOnce = true
        }
        LaunchedEffect(Unit) {
            // Fråga automatiskt bara en gång per appstart (inte varje gång man går tillbaka från galleriet)
            hasCamera = context.isGranted(Manifest.permission.CAMERA)
            if (!viewModel.permissionsRequested &&
                (!hasCamera || !context.isGranted(Manifest.permission.RECORD_AUDIO))
            ) {
                viewModel.permissionsRequested = true
                launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
            }
        }
        if (hasCamera) {
            CameraContent(viewModel, onOpenGallery, onOpenSettings)
        } else {
            PermissionPrompt(
                showSettings = askedOnce,
                onRequest = { launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) },
                onOpenSettings = { context.openAppSettings() }
            )
        }
    }
}

/** Behörighetsskärm i USB-stil: ordmärke överst, stor guldknapp i ringar, status och knappar. */
@Composable
private fun PermissionPrompt(showSettings: Boolean, onRequest: () -> Unit, onOpenSettings: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Usb.Bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Wordmark(subtitle = stringResource(R.string.wm_sub), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.weight(1f))
        HeroButton(
            icon = Icons.Outlined.CameraAlt,
            contentDescription = stringResource(R.string.permission_grant),
            onClick = onRequest
        )
        Spacer(Modifier.height(18.dp))
        StatusPill(stringResource(R.string.permission_status), color = Usb.GoldL, dot = Usb.Gold)
        Spacer(Modifier.height(18.dp))
        Text(
            stringResource(R.string.permission_title),
            color = Usb.Text,
            fontFamily = DisplayFont,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        MutedText(
            stringResource(R.string.permission_text),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(Modifier.weight(1f))
        PrimaryButton(
            text = stringResource(R.string.permission_grant),
            onClick = onRequest,
            icon = Icons.Outlined.CameraAlt,
            modifier = Modifier.fillMaxWidth()
        )
        if (showSettings) {
            Spacer(Modifier.height(10.dp))
            SecondaryButton(
                text = stringResource(R.string.permission_open_settings),
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun CameraContent(
    viewModel: CameraViewModel,
    onOpenGallery: (Uri?) -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    val orientation by rememberDeviceOrientation { viewModel.onDeviceRotation(it.surfaceRotation) }
    val iconRotation = rememberIconRotation(orientation)

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    DisposableEffect(previewView, lifecycleOwner) {
        viewModel.attach(lifecycleOwner, previewView)
        onDispose { viewModel.detach() }
    }

    LifecycleResumeEffect(Unit) {
        viewModel.refreshThumbnail()
        onPauseOrDispose { }
    }

    // Skärmen ska inte slockna medan kameran är öppen
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // Volymknappar → avtryckare eller zoom
    DisposableEffect(settings.volumeKeyAction) {
        HardwareKeys.cameraActive = true
        HardwareKeys.volumeAction = settings.volumeKeyAction
        onDispose { HardwareKeys.cameraActive = false }
    }
    LaunchedEffect(Unit) {
        HardwareKeys.events.collect { cmd ->
            when (cmd) {
                KeyCommand.SHUTTER -> viewModel.onShutter()
                KeyCommand.ZOOM_IN -> viewModel.zoomBy(1.15f)
                KeyCommand.ZOOM_OUT -> viewModel.zoomBy(1f / 1.15f)
            }
        }
    }

    // Skärmblixt för frontkameran: maximal ljusstyrka
    DisposableEffect(state.screenFlash) {
        val window = context.findActivity()?.window
        val params = window?.attributes
        if (window != null && params != null) {
            params.screenBrightness = if (state.screenFlash) 1f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = params
        }
        onDispose {
            val w = context.findActivity()?.window
            val p = w?.attributes
            if (w != null && p != null) {
                p.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                w.attributes = p
            }
        }
    }

    // Haptisk återkoppling
    LaunchedEffect(state.shutterBlink) {
        if (state.shutterBlink > 0 && settings.haptics) {
            view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    // Meddelanden försvinner efter en stund
    LaunchedEffect(state.messageId) {
        if (state.message != null) {
            delay(2800)
            viewModel.clearMessage()
        }
    }

    // Fokusring
    var focusPos by remember { mutableStateOf<Offset?>(null) }
    var focusKey by remember { mutableLongStateOf(0L) }
    var focusTouch by remember { mutableLongStateOf(0L) }
    LaunchedEffect(focusTouch) {
        if (focusPos != null) {
            delay(3500)
            focusPos = null
        }
    }
    LaunchedEffect(state.mode, state.lensFront) { focusPos = null }

    // Kort svart blink vid fotografering
    val blink = remember { Animatable(0f) }
    LaunchedEffect(state.shutterBlink) {
        if (state.shutterBlink > 0 && !state.mode.isVideo) {
            blink.snapTo(0.85f)
            blink.animateTo(0f, tween(260))
        }
    }

    val previewRatio = if (state.mode.isVideo) 9f / 16f else settings.aspect.portraitRatio
    val animatedRatio by animateFloatAsState(previewRatio, tween(280), label = "previewRatio")
    val modes = CameraMode.entries

    val currentState by rememberUpdatedState(state)
    val focusActive by rememberUpdatedState(focusPos != null)

    Box(
        Modifier
            .fillMaxSize()
            .background(Usb.Bg)
    ) {
        // --------------------------- Sökare ---------------------------
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 56.dp)
                .fillMaxWidth()
                .aspectRatio(animatedRatio)
                .clip(RoundedCornerShape(if (settings.aspect.portraitRatio == 1f && !state.mode.isVideo) 0.dp else 4.dp))
        ) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

            // Gester: tryck = fokus, nyp = zoom, svep = byt läge, dra upp/ned efter fokus = exponering
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        val swipeThreshold = 64.dp.toPx()
                        val evStepPx = 16.dp.toPx()
                        val slop = viewConfiguration.touchSlop
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var pinch = false
                            var dx = 0f
                            var dy = 0f
                            var evAcc = 0f
                            var evDragging = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.isEmpty()) break
                                if (pressed.size >= 2) {
                                    pinch = true
                                    val zoom = event.calculateZoom()
                                    if (zoom != 1f) viewModel.zoomBy(zoom)
                                    event.changes.forEach { it.consume() }
                                } else if (!pinch) {
                                    val d = pressed[0].positionChange()
                                    dx += d.x
                                    dy += d.y
                                    val st = currentState
                                    if (focusActive && st.exposureSupported && st.mode != CameraMode.PRO &&
                                        (evDragging || (abs(dy) > slop && abs(dy) > abs(dx)))
                                    ) {
                                        evDragging = true
                                        evAcc += d.y
                                        var index = currentState.exposureIndex
                                        while (evAcc <= -evStepPx) {
                                            evAcc += evStepPx; index++
                                        }
                                        while (evAcc >= evStepPx) {
                                            evAcc -= evStepPx; index--
                                        }
                                        if (index != currentState.exposureIndex) viewModel.setExposureIndex(index)
                                        focusTouch = System.currentTimeMillis()
                                        pressed[0].consume()
                                    }
                                }
                            }
                            if (!pinch && !evDragging) {
                                val st = currentState
                                if (abs(dx) > swipeThreshold && abs(dx) > abs(dy) * 1.5f) {
                                    if (!st.isRecording && st.countdown == 0) {
                                        val i = modes.indexOf(st.mode) + if (dx < 0) 1 else -1
                                        if (i in modes.indices) viewModel.setMode(modes[i])
                                    }
                                } else if (abs(dx) < slop && abs(dy) < slop) {
                                    focusPos = down.position
                                    focusKey++
                                    focusTouch = System.currentTimeMillis()
                                    if (st.exposureIndex != 0 && st.mode != CameraMode.PRO) viewModel.setExposureIndex(0)
                                    viewModel.focusAt(down.position.x, down.position.y)
                                }
                            }
                        }
                    }
            )

            GridOverlay(settings.grid)
            if (settings.levelIndicator) LevelIndicator(orientation.tilt, orientation.quantized)

            FocusRing(
                position = focusPos,
                key = focusKey,
                exposureFraction = if (state.exposureSupported && state.exposureMax > state.exposureMin && state.mode != CameraMode.PRO) {
                    (state.exposureIndex - state.exposureMin).toFloat() / (state.exposureMax - state.exposureMin)
                } else null,
                evLabel = if (state.exposureIndex != 0) formatEv(state.exposureIndex, state.exposureStep) else null,
                rotation = iconRotation
            )

            CountdownOverlay(state.countdown, iconRotation)

            // Läges-badge (t.ex. nattläge)
            if (state.mode == CameraMode.NIGHT && !state.isCapturing) {
                Row(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Usb.Bg.copy(alpha = 0.72f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.NightsStay, contentDescription = null, tint = CameraYellow, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.night_hold_steady), color = Usb.Text, fontSize = 12.sp)
                }
            }

            AnimatedVisibility(
                visible = state.message != null,
                enter = fadeIn() + slideInVertically { -it },
                exit = fadeOut() + slideOutVertically { -it },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 52.dp)
            ) {
                val msg = state.message
                if (msg != null) InfoPill(stringResource(msg))
            }

            // Svart blink när bilden tas
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = blink.value }
                    .background(Color.Black)
            )
        }

        // --------------------------- Övre fältet ---------------------------
        TopBar(
            state = state,
            flash = settings.flash,
            timer = settings.timer,
            hdr = settings.hdr,
            grid = settings.grid,
            aspectLabel = settings.aspect.label,
            videoQualityLabel = settings.videoQuality.label,
            rotation = iconRotation,
            onFlash = { if (state.mode.isVideo) viewModel.toggleTorch() else viewModel.cycleFlash() },
            onHdr = viewModel::toggleHdr,
            onTimer = viewModel::cycleTimer,
            onAspect = viewModel::cycleAspect,
            onGrid = viewModel::cycleGrid,
            onVideoQuality = viewModel::cycleVideoQuality,
            onSettings = onOpenSettings,
            updateAvailable = settings.latestKnownVersion.isNotBlank() && UpdateManager.isNewer(settings.latestKnownVersion),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
        )

        // --------------------------- Nedre fältet ---------------------------
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.25f to Usb.Bg.copy(alpha = 0.6f),
                        1f to Usb.Bg
                    )
                )
                .navigationBarsPadding()
                .padding(top = 12.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (state.mode == CameraMode.PRO && !state.isRecording) {
                ProPanel(
                    state = state,
                    rotation = iconRotation,
                    onSelect = viewModel::selectProControl,
                    onPro = viewModel::updatePro,
                    onExposure = viewModel::setExposureIndex,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            ZoomChips(
                zoom = state.zoomRatio,
                minZoom = state.minZoom,
                maxZoom = state.maxZoom,
                rotation = iconRotation,
                onZoom = viewModel::setZoom,
                modifier = Modifier.padding(bottom = 10.dp)
            )

            if (state.isRecording) {
                Spacer(Modifier.height(44.dp))
            } else {
                ModeSelector(
                    modes = modes,
                    selected = state.mode,
                    enabled = state.countdown == 0,
                    onSelect = viewModel::setMode
                )
            }

            Spacer(Modifier.height(14.dp))

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 36.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                    if (state.isRecording) {
                        PauseButton(state.isPaused, viewModel::togglePause)
                    } else {
                        GalleryThumbnail(
                            bitmap = state.lastThumbnail,
                            isVideo = state.lastIsVideo,
                            rotation = iconRotation,
                            onClick = { onOpenGallery(state.lastUri) }
                        )
                        if (state.isCapturing && state.mode == CameraMode.PORTRAIT) {
                            CircularProgressIndicator(
                                color = Usb.Gold,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(56.dp)
                            )
                        }
                    }
                }

                ShutterButton(
                    isVideo = state.mode.isVideo,
                    isRecording = state.isRecording,
                    isBusy = state.isCapturing,
                    countdown = state.countdown,
                    onClick = viewModel::onShutter
                )

                Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                    SwitchLensButton(
                        rotation = iconRotation,
                        enabled = !state.isRecording && state.hasFrontCamera && state.hasBackCamera,
                        onClick = viewModel::switchLens
                    )
                }
            }
        }

        // Skärmblixt
        if (state.screenFlash) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.White)
            )
        }
    }
}

@Composable
private fun TopBar(
    state: CameraUiState,
    flash: FlashOption,
    timer: TimerOption,
    hdr: Boolean,
    grid: GridOption,
    aspectLabel: String,
    videoQualityLabel: String,
    rotation: Float,
    onFlash: () -> Unit,
    onHdr: () -> Unit,
    onTimer: () -> Unit,
    onAspect: () -> Unit,
    onGrid: () -> Unit,
    onVideoQuality: () -> Unit,
    onSettings: () -> Unit,
    updateAvailable: Boolean = false,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        if (state.isRecording) {
            RecordingTimer(state.recordingMs, state.isPaused)
            return@Box
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val mode = state.mode
            val flashUsable = state.lensFront || state.hasFlashUnit
            // Blixt / ficklampa
            if (mode != CameraMode.NIGHT && flashUsable) {
                if (mode.isVideo) {
                    GlassIconButton(
                        icon = if (state.torchOn) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff,
                        contentDescription = stringResource(R.string.cd_torch),
                        onClick = onFlash,
                        rotation = rotation,
                        tint = if (state.torchOn) CameraYellow else Usb.Text,
                        enabled = !state.lensFront
                    )
                } else {
                    GlassIconButton(
                        icon = when (flash) {
                            FlashOption.OFF -> Icons.Rounded.FlashOff
                            FlashOption.AUTO -> Icons.Rounded.FlashAuto
                            FlashOption.ON -> Icons.Rounded.FlashOn
                        },
                        contentDescription = stringResource(R.string.cd_flash),
                        onClick = onFlash,
                        rotation = rotation,
                        tint = if (flash == FlashOption.ON) CameraYellow else Usb.Text
                    )
                }
            } else {
                Spacer(Modifier.size(40.dp))
            }

            if (mode == CameraMode.PHOTO && state.hdrAvailable) {
                GlassIconButton(
                    icon = if (hdr) Icons.Rounded.HdrOn else Icons.Rounded.HdrOff,
                    contentDescription = stringResource(R.string.cd_hdr),
                    onClick = onHdr,
                    rotation = rotation,
                    tint = if (hdr) CameraYellow else Usb.Text
                )
            }

            GlassIconButton(
                icon = if (timer == TimerOption.OFF) Icons.Rounded.TimerOff else Icons.Rounded.Timer,
                contentDescription = stringResource(R.string.cd_timer),
                onClick = onTimer,
                rotation = rotation,
                tint = if (timer == TimerOption.OFF) Usb.Text else CameraYellow,
                badge = if (timer == TimerOption.OFF) null else "${timer.seconds}s"
            )

            if (mode.isVideo) {
                GlassTextButton(
                    text = videoQualityLabel,
                    contentDescription = stringResource(R.string.cd_video_quality),
                    onClick = onVideoQuality,
                    rotation = rotation
                )
            } else if (mode == CameraMode.PHOTO || mode == CameraMode.PRO) {
                GlassTextButton(
                    text = aspectLabel,
                    contentDescription = stringResource(R.string.cd_aspect),
                    onClick = onAspect,
                    rotation = rotation
                )
            }

            GlassIconButton(
                icon = if (grid == GridOption.OFF) Icons.Rounded.GridOff else Icons.Rounded.GridOn,
                contentDescription = stringResource(R.string.cd_grid),
                onClick = onGrid,
                rotation = rotation,
                tint = if (grid == GridOption.OFF) Usb.Text else CameraYellow
            )

            GlassIconButton(
                icon = Icons.Rounded.Settings,
                contentDescription = stringResource(R.string.cd_settings),
                onClick = onSettings,
                rotation = rotation,
                dot = updateAvailable
            )
        }
    }
}
