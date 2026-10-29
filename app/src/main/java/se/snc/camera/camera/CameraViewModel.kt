package se.snc.camera.camera

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.ContentValues
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.MediaActionSound
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Rational
import android.view.Surface
import androidx.annotation.StringRes
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import se.snc.camera.R
import se.snc.camera.SncCameraApp
import se.snc.camera.data.AppSettings
import se.snc.camera.data.AspectOption
import se.snc.camera.data.FlashOption
import se.snc.camera.data.GridOption
import se.snc.camera.data.MediaRepository
import se.snc.camera.data.TimerOption
import se.snc.camera.data.VideoQualityOption
import se.snc.camera.util.await
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
class CameraViewModel(app: Application) : AndroidViewModel(app) {

    private companion object {
        const val TAG = "SncCamera"
    }

    private val context get() = getApplication<Application>()
    private val sncApp = app as SncCameraApp
    private val settingsRepo = sncApp.settingsRepository
    private val media = sncApp.mediaRepository
    private val portrait = PortraitProcessor(sncApp, sncApp.mediaRepository)

    val settings: StateFlow<AppSettings> =
        settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _state = MutableStateFlow(CameraUiState())
    val state: StateFlow<CameraUiState> = _state.asStateFlow()

    private var provider: ProcessCameraProvider? = null
    private var extensions: ExtensionsManager? = null
    private var extensionsTried = false
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var zoomLiveData: LiveData<ZoomState>? = null
    private val zoomObserver = Observer<ZoomState> { z ->
        _state.update { it.copy(zoomRatio = z.zoomRatio, minZoom = z.minZoomRatio, maxZoom = z.maxZoomRatio) }
    }

    private var lifecycleOwner: LifecycleOwner? = null
    private var previewView: PreviewView? = null
    private var bindJob: Job? = null
    private var countdownJob: Job? = null
    private var targetRotation = Surface.ROTATION_0
    private var initialized = false
    private var rebindAfterRecording = false
    private var lastFallbackMode: CameraMode? = null
    private var pendingMode: CameraMode? = null

    /** Behörighetsdialogen visas automatiskt högst en gång per appstart. */
    var permissionsRequested = false

    private val sound = MediaActionSound()

    /** Bakkameran med flest linser (t.ex. kamera 5 på OnePlus 13R: vidvinkel + ultravidvinkel + tele). */
    private val preferredBackId: String? by lazy { LensSelection.preferredBackCameraId(context) }

    @Volatile private var lensBadges: Map<String, LensBadge> = emptyMap()
    @Volatile private var lastActiveLens: String? = null

    /** Läser vilken fysisk lins den logiska kameran använder i varje bildruta. */
    private val lensCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
            val id = result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID) ?: return
            if (id == lastActiveLens) return
            val first = lastActiveLens == null
            lastActiveLens = id
            val badge = lensBadges[id] ?: return
            _state.update { it.copy(activeLens = badge, lensChangeTick = if (first) it.lensChangeTick else System.nanoTime()) }
        }
    }

    /** Inställningar som kräver att kameran binds om när de ändras. */
    private data class BindKey(
        val aspect: AspectOption,
        val hdr: Boolean,
        val videoQuality: VideoQualityOption,
        val stabilization: Boolean,
        val maxQuality: Boolean,
        val jpegQuality: Int,
        val videoFps: Int
    )

    init {
        sound.load(MediaActionSound.SHUTTER_CLICK)
        sound.load(MediaActionSound.START_VIDEO_RECORDING)
        sound.load(MediaActionSound.STOP_VIDEO_RECORDING)

        viewModelScope.launch {
            val s = settingsRepo.settings.first()
            val mode = pendingMode ?: if (s.keepLastMode) {
                CameraMode.entries.firstOrNull { it.name == s.lastMode } ?: CameraMode.PHOTO
            } else CameraMode.PHOTO
            pendingMode = null
            _state.update { it.copy(mode = mode, lensFront = s.lastLensFront) }
            initialized = true
            rebind()
        }
        viewModelScope.launch {
            settingsRepo.settings
                .map { BindKey(it.aspect, it.hdr, it.videoQuality, it.videoStabilization, it.maxQuality, it.jpegQuality, it.videoFps) }
                .distinctUntilChanged()
                .drop(1) // första värdet hanteras av start/attach – rebinda bara vid ändringar
                .collect { if (initialized) rebind() }
        }
        viewModelScope.launch {
            settingsRepo.settings.map { it.flash }.distinctUntilChanged().collect { applyFlashMode() }
        }
        viewModelScope.launch {
            media.changes.collect { refreshThumbnail() }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Livscykel
    // ---------------------------------------------------------------------------------------------

    fun attach(owner: LifecycleOwner, view: PreviewView) {
        lifecycleOwner = owner
        previewView = view
        rebind()
    }

    fun detach() {
        countdownJob?.cancel()
        recording?.stop()
        recording = null
        bindJob?.cancel()
        zoomLiveData?.removeObserver(zoomObserver)
        zoomLiveData = null
        runCatching { provider?.unbindAll() }
        camera = null
        imageCapture = null
        videoCapture = null
        previewView = null
        lifecycleOwner = null
        _state.update { it.copy(isReady = false, countdown = 0, isRecording = false, isPaused = false, screenFlash = false) }
    }

    override fun onCleared() {
        detach()
        sound.release()
        portrait.close()
        super.onCleared()
    }

    private fun rebind() {
        if (!initialized) return
        if (_state.value.isRecording) {
            rebindAfterRecording = true
            return
        }
        bindJob?.cancel()
        bindJob = viewModelScope.launch {
            val owner = lifecycleOwner ?: return@launch
            val view = previewView ?: return@launch
            // Några försök: kameran kan vara upptagen en kort stund (t.ex. direkt efter en annan app)
            var lastError: Exception? = null
            for (attempt in 1..3) {
                try {
                    val cp = provider ?: ProcessCameraProvider.getInstance(context).await().also { provider = it }
                    if (!extensionsTried) {
                        extensions = try {
                            ExtensionsManager.getInstanceAsync(context, cp).await()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "Kameratillägg (natt/porträtt/HDR) saknas", e)
                            null
                        }
                        extensionsTried = true
                    }
                    val s = settingsRepo.settings.first()
                    if (bindUseCases(cp, owner, view, s)) return@launch
                } catch (e: CancellationException) {
                    // En ny bindning har ersatt den här – inget fel
                    throw e
                } catch (e: Exception) {
                    lastError = e
                    Log.w(TAG, "Kamerastart misslyckades (försök $attempt)", e)
                }
                delay(700L * attempt)
            }
            Log.e(TAG, "Kunde inte starta kameran", lastError)
            showMessage(R.string.msg_camera_error)
        }
    }

    /** Returnerar true om kameran kunde bindas. */
    private fun bindUseCases(cp: ProcessCameraProvider, owner: LifecycleOwner, view: PreviewView, s: AppSettings): Boolean {
        val st = _state.value
        val hasBack = runCatching { cp.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) }.getOrDefault(false)
        val hasFront = runCatching { cp.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) }.getOrDefault(false)
        var front = st.lensFront
        if (front && !hasFront) front = false
        if (!front && !hasBack && hasFront) front = true
        val defaultBase = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        // Bak: den logiska kameran med flest linser, så att 0,6× och 2× använder de riktiga linserna
        val backId = if (!front) preferredBackId else null
        val base = if (backId != null) {
            CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .addCameraFilter { infos ->
                    infos.filter { info -> runCatching { Camera2CameraInfo.from(info).cameraId }.getOrNull() == backId }
                        .ifEmpty { infos }
                }
                .build()
        } else defaultBase

        val ext = extensions
        fun available(mode: Int) = ext != null && runCatching { ext.isExtensionAvailable(base, mode) }.getOrDefault(false)
        val nightAvailable = available(ExtensionMode.NIGHT)
        val bokehAvailable = available(ExtensionMode.BOKEH)
        val hdrAvailable = available(ExtensionMode.HDR)

        val wantedExtension: Int? = when (st.mode) {
            CameraMode.NIGHT -> if (nightAvailable) ExtensionMode.NIGHT else null
            CameraMode.PORTRAIT -> if (bokehAvailable) ExtensionMode.BOKEH else null
            CameraMode.PHOTO -> if (s.hdr && hdrAvailable) ExtensionMode.HDR else null
            else -> null
        }

        // Ta bort ev. manuella Camera2-värden från förra läget
        camera?.let { old -> runCatching { Camera2CameraControl.from(old.cameraControl).clearCaptureRequestOptions() } }
        zoomLiveData?.removeObserver(zoomObserver)
        zoomLiveData = null

        fun attempt(useExtension: Boolean, stabilization: Boolean, highFps: Boolean, cameraBase: CameraSelector): Camera? {
            val selector = if (useExtension && wantedExtension != null && ext != null) {
                ext.getExtensionEnabledCameraSelector(cameraBase, wantedExtension)
            } else cameraBase
            val fpsRange = if (st.mode.isVideo && highFps) Range(60, 60) else null
            val photoAspect = if (s.aspect == AspectOption.RATIO_16_9) {
                AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
            } else AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
            val previewAspect = if (st.mode.isVideo) AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY else photoAspect

            val previewBuilder = Preview.Builder()
                .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(previewAspect).build())
            if (fpsRange != null) previewBuilder.setTargetFrameRate(fpsRange)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(lensCallback)
            }
            val preview = previewBuilder.build()
            preview.setSurfaceProvider(view.surfaceProvider)

            val useCases = mutableListOf<UseCase>(preview)
            var ic: ImageCapture? = null
            var vc: VideoCapture<Recorder>? = null
            if (st.mode.isVideo) {
                val quality = when (s.videoQuality) {
                    VideoQualityOption.HD -> Quality.HD
                    VideoQualityOption.FHD -> Quality.FHD
                    VideoQualityOption.UHD -> Quality.UHD
                }
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(quality, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                    .build()
                val videoBuilder = VideoCapture.Builder(recorder)
                    .setVideoStabilizationEnabled(stabilization)
                    .setTargetRotation(targetRotation)
                if (fpsRange != null) videoBuilder.setTargetFrameRate(fpsRange)
                vc = videoBuilder.build()
                useCases += vc
            } else {
                ic = ImageCapture.Builder()
                    .setCaptureMode(
                        if (s.maxQuality) ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
                        else ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                    )
                    .setJpegQuality(s.jpegQuality)
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(photoAspect)
                            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                            .build()
                    )
                    .setTargetRotation(targetRotation)
                    .setFlashMode(flashModeFor(s.flash, front))
                    .build()
                if (s.aspect == AspectOption.RATIO_1_1) ic.setCropAspectRatio(Rational(1, 1))
                useCases += ic
            }

            cp.unbindAll()
            return try {
                val cam = cp.bindToLifecycle(owner, selector, *useCases.toTypedArray())
                imageCapture = ic
                videoCapture = vc
                cam
            } catch (e: Exception) {
                Log.w(TAG, "Bindning misslyckades (extension=$useExtension, stab=$stabilization, 60fps=$highFps)", e)
                null
            }
        }

        // Försök i ordning: allt påslaget → utan tillägg/stabilisering → 30 fps → standardkameran
        val wantHighFps = st.mode.isVideo && s.videoFps >= 60
        val tries = buildList {
            add(BindTry(true, s.videoStabilization, wantHighFps, base))
            if (wantHighFps) add(BindTry(false, false, true, base))
            add(BindTry(false, false, false, base))
            if (backId != null) add(BindTry(false, false, false, defaultBase))
        }
        var bound: Camera? = null
        var used: BindTry? = null
        lastActiveLens = null
        for (t in tries) {
            bound = attempt(t.extension, t.stabilization, t.highFps, t.selector)
            if (bound != null) {
                used = t
                break
            }
        }
        val cam: Camera = bound ?: return false
        camera = cam
        val extensionActive = used?.extension == true && wantedExtension != null && imageCapture != null
        if (wantHighFps && used?.highFps != true) showMessage(R.string.msg_fps_unsupported)

        // Linserna bakom kameran, för etiketten som visar aktiv lins
        val boundId = runCatching { Camera2CameraInfo.from(cam.cameraInfo).cameraId }.getOrNull()
        lensBadges = boundId?.let { LensSelection.lensesOf(context, it) } ?: emptyMap()
        _state.update { it.copy(activeLens = lensBadges.values.singleOrNull()) }

        zoomLiveData = cam.cameraInfo.zoomState.also { it.observe(owner, zoomObserver) }

        val exposure = cam.cameraInfo.exposureState
        val pro = readProCapabilities(cam, st.pro)

        _state.update {
            it.copy(
                isReady = true,
                lensFront = front,
                hasFrontCamera = hasFront,
                hasBackCamera = hasBack,
                hasFlashUnit = cam.cameraInfo.hasFlashUnit(),
                torchOn = false,
                nightAvailable = nightAvailable,
                bokehAvailable = bokehAvailable,
                hdrAvailable = hdrAvailable,
                extensionActive = extensionActive,
                exposureSupported = exposure.isExposureCompensationSupported,
                exposureIndex = exposure.exposureCompensationIndex,
                exposureMin = exposure.exposureCompensationRange.lower,
                exposureMax = exposure.exposureCompensationRange.upper,
                exposureStep = exposure.exposureCompensationStep.toFloat(),
                pro = pro
            )
        }

        when (st.mode) {
            CameraMode.PRO -> applyProOptions()
            CameraMode.NIGHT -> if (!extensionActive) applySoftwareNightOptions(cam)
            else -> Unit
        }

        // Berätta en gång om läget körs utan tillverkarens extension
        if ((st.mode == CameraMode.NIGHT || st.mode == CameraMode.PORTRAIT) && !extensionActive && lastFallbackMode != st.mode) {
            lastFallbackMode = st.mode
            showMessage(if (st.mode == CameraMode.NIGHT) R.string.msg_night_software else R.string.msg_portrait_software)
        }
        return true
    }

    private fun flashModeFor(f: FlashOption, front: Boolean): Int = if (front) {
        ImageCapture.FLASH_MODE_OFF // Frontkameran använder skärmblixt
    } else when (f) {
        FlashOption.OFF -> ImageCapture.FLASH_MODE_OFF
        FlashOption.AUTO -> ImageCapture.FLASH_MODE_AUTO
        FlashOption.ON -> ImageCapture.FLASH_MODE_ON
    }

    private fun applyFlashMode() {
        val ic = imageCapture ?: return
        runCatching { ic.flashMode = flashModeFor(settings.value.flash, _state.value.lensFront) }
    }

    // ---------------------------------------------------------------------------------------------
    // Pro- och nattläge (Camera2-interop)
    // ---------------------------------------------------------------------------------------------

    private fun readProCapabilities(cam: Camera, previous: ProState): ProState {
        return runCatching {
            val info = Camera2CameraInfo.from(cam.cameraInfo)
            val caps = info.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
            val manual = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
            val iso = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val exp = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val minFocus = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            if (!manual || iso == null || exp == null) {
                previous.copy(supported = false)
            } else {
                val maxShutter = minOf(exp.upper, 30_000_000_000L) // lång exponering upp till 30 s
                previous.copy(
                    supported = true,
                    isoMin = iso.lower,
                    isoMax = iso.upper,
                    iso = previous.iso.coerceIn(iso.lower, iso.upper),
                    shutterMinNs = exp.lower,
                    shutterMaxNs = maxShutter,
                    shutterNs = previous.shutterNs.coerceIn(exp.lower, maxShutter),
                    focusMaxDiopters = minFocus,
                    focusDiopters = previous.focusDiopters.coerceIn(0f, maxOf(minFocus, 0f))
                )
            }
        }.getOrDefault(previous.copy(supported = false))
    }

    private fun applyProOptions() {
        val cam = camera ?: return
        val st = _state.value
        if (st.mode != CameraMode.PRO || !st.pro.supported) return
        val p = st.pro
        val builder = CaptureRequestOptions.Builder()
        if (!p.isoAuto || !p.shutterAuto) {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, p.iso)
            builder.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, p.shutterNs)
        }
        builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, p.whiteBalance.awbMode)
        if (!p.focusAuto && p.focusMaxDiopters > 0f) {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, p.focusDiopters)
        }
        runCatching { Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(builder.build()) }
            .onFailure { Log.w(TAG, "Pro-värden kunde inte sättas", it) }
    }

    /**
     * Nattläge utan tillverkarens extension: tillåter längre exponering genom att sänka lägsta
     * bildfrekvens och slå på högkvalitativ brusreducering.
     */
    private fun applySoftwareNightOptions(cam: Camera) {
        runCatching {
            val info = Camera2CameraInfo.from(cam.cameraInfo)
            val ranges = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            val slow: Range<Int>? = ranges?.minWithOrNull(compareBy<Range<Int>>({ it.lower }, { -it.upper }))
            val builder = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                .setCaptureRequestOption(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
            if (slow != null) builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, slow)
            Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(builder.build())
        }.onFailure { Log.w(TAG, "Nattinställningar kunde inte sättas", it) }
    }

    fun updatePro(transform: (ProState) -> ProState) {
        _state.update { it.copy(pro = transform(it.pro)) }
        applyProOptions()
    }

    fun selectProControl(control: ProControl?) {
        _state.update { it.copy(activeProControl = if (it.activeProControl == control) null else control) }
    }

    // ---------------------------------------------------------------------------------------------
    // Användaråtgärder
    // ---------------------------------------------------------------------------------------------

    /** Används av t.ex. VIDEO_CAMERA-intent; fungerar även innan kameran startat. */
    fun requestMode(mode: CameraMode) {
        if (!initialized) pendingMode = mode else setMode(mode)
    }

    fun setMode(mode: CameraMode) {
        val st = _state.value
        if (st.isRecording || st.countdown > 0 || mode == st.mode) return
        _state.update { it.copy(mode = mode, activeProControl = null) }
        viewModelScope.launch { settingsRepo.update { it.copy(lastMode = mode.name) } }
        rebind()
    }

    fun switchLens() {
        val st = _state.value
        if (st.isRecording || st.countdown > 0) return
        if (!st.hasFrontCamera || !st.hasBackCamera) return
        val front = !st.lensFront
        _state.update { it.copy(lensFront = front, zoomRatio = 1f) }
        viewModelScope.launch { settingsRepo.update { it.copy(lastLensFront = front) } }
        rebind()
    }

    fun setZoom(ratio: Float) {
        val st = _state.value
        val r = ratio.coerceIn(st.minZoom, st.maxZoom)
        _state.update { it.copy(zoomRatio = r) }
        camera?.cameraControl?.setZoomRatio(r)
    }

    fun zoomBy(factor: Float) = setZoom(_state.value.zoomRatio * factor)

    fun focusAt(x: Float, y: Float) {
        val view = previewView ?: return
        val cam = camera ?: return
        val st = _state.value
        val manualFocus = st.mode == CameraMode.PRO && !st.pro.focusAuto
        val flags = if (manualFocus) FocusMeteringAction.FLAG_AE else FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
        val point = view.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, flags)
            .setAutoCancelDuration(4, TimeUnit.SECONDS)
            .build()
        runCatching { cam.cameraControl.startFocusAndMetering(action) }
    }

    fun setExposureIndex(index: Int) {
        val st = _state.value
        val i = index.coerceIn(st.exposureMin, st.exposureMax)
        _state.update { it.copy(exposureIndex = i) }
        camera?.cameraControl?.setExposureCompensationIndex(i)
    }

    fun setFlash(option: FlashOption) {
        viewModelScope.launch { settingsRepo.update { it.copy(flash = option) } }
    }

    fun cycleFlash() {
        val next = when (settings.value.flash) {
            FlashOption.OFF -> FlashOption.AUTO
            FlashOption.AUTO -> FlashOption.ON
            FlashOption.ON -> FlashOption.OFF
        }
        setFlash(next)
    }

    fun toggleTorch() {
        val cam = camera ?: return
        val on = !_state.value.torchOn
        _state.update { it.copy(torchOn = on) }
        cam.cameraControl.enableTorch(on)
    }

    fun setTimer(option: TimerOption) {
        viewModelScope.launch { settingsRepo.update { it.copy(timer = option) } }
    }

    fun cycleTimer() {
        val next = when (settings.value.timer) {
            TimerOption.OFF -> TimerOption.S3
            TimerOption.S3 -> TimerOption.S5
            TimerOption.S5 -> TimerOption.S10
            TimerOption.S10 -> TimerOption.OFF
        }
        setTimer(next)
    }

    fun setAspect(option: AspectOption) {
        viewModelScope.launch { settingsRepo.update { it.copy(aspect = option) } }
    }

    fun cycleAspect() {
        val values = AspectOption.entries
        val next = values[(settings.value.aspect.ordinal + 1) % values.size]
        setAspect(next)
    }

    fun cycleFps() {
        if (_state.value.isRecording) return
        viewModelScope.launch { settingsRepo.update { it.copy(videoFps = if (it.videoFps >= 60) 30 else 60) } }
    }

    fun cycleVideoQuality() {
        if (_state.value.isRecording) return
        viewModelScope.launch {
            settingsRepo.update {
                val values = VideoQualityOption.entries
                it.copy(videoQuality = values[(it.videoQuality.ordinal + 1) % values.size])
            }
        }
    }

    fun toggleHdr() {
        viewModelScope.launch { settingsRepo.update { it.copy(hdr = !it.hdr) } }
    }

    fun cycleGrid() {
        viewModelScope.launch {
            settingsRepo.update {
                val values = GridOption.entries
                it.copy(grid = values[(it.grid.ordinal + 1) % values.size])
            }
        }
    }

    fun onDeviceRotation(surfaceRotation: Int) {
        if (targetRotation == surfaceRotation) return
        targetRotation = surfaceRotation
        imageCapture?.targetRotation = surfaceRotation
        if (!_state.value.isRecording) videoCapture?.targetRotation = surfaceRotation
    }

    /** Avtryckaren: foto, start/stopp av video eller avbryt nedräkning. */
    fun onShutter() {
        val st = _state.value
        if (!st.isReady) return
        if (st.countdown > 0) {
            countdownJob?.cancel()
            _state.update { it.copy(countdown = 0) }
            return
        }
        if (st.mode.isVideo) {
            if (st.isRecording) stopRecording() else runWithTimer { startRecording() }
            return
        }
        if (st.isCapturing) return
        runWithTimer { takePhoto() }
    }

    private fun runWithTimer(action: () -> Unit) {
        val seconds = settings.value.timer.seconds
        if (seconds == 0) {
            action()
            return
        }
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            for (i in seconds downTo 1) {
                _state.update { it.copy(countdown = i) }
                delay(1000)
            }
            _state.update { it.copy(countdown = 0) }
            action()
        }
    }

    private fun takePhoto() {
        val ic = imageCapture ?: return
        val s = settings.value
        val st = _state.value
        viewModelScope.launch {
            _state.update { it.copy(isCapturing = true) }
            val screenFlash = st.lensFront && s.flash == FlashOption.ON
            if (screenFlash) {
                _state.update { it.copy(screenFlash = true) }
                delay(500) // låt skärmen lysa upp ansiktet och exponeringen ställa in sig
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, MediaRepository.newFileName("IMG", "jpg"))
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, MediaRepository.RELATIVE_PATH)
            }
            val metadata = ImageCapture.Metadata().apply {
                isReversedHorizontal = st.lensFront && s.mirrorFront
            }
            val options = ImageCapture.OutputFileOptions.Builder(
                context.contentResolver,
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                values
            ).setMetadata(metadata).build()

            if (s.shutterSound) sound.play(MediaActionSound.SHUTTER_CLICK)
            _state.update { it.copy(shutterBlink = System.currentTimeMillis()) }

            ic.takePicture(options, ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    _state.update { it.copy(screenFlash = false) }
                    val uri = output.savedUri
                    if (uri == null) {
                        _state.update { it.copy(isCapturing = false) }
                        return
                    }
                    viewModelScope.launch {
                        if (st.mode == CameraMode.PORTRAIT && !st.extensionActive) {
                            showMessage(R.string.msg_processing_portrait)
                            portrait.process(uri, s.jpegQuality)
                        }
                        _state.update { it.copy(isCapturing = false) }
                        onMediaSaved(uri, isVideo = false)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Fotografering misslyckades", exception)
                    _state.update { it.copy(isCapturing = false, screenFlash = false) }
                    showMessage(R.string.msg_capture_failed)
                }
            })
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        val vc = videoCapture ?: return
        val s = settings.value
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, MediaRepository.newFileName("VID", "mp4"))
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, MediaRepository.RELATIVE_PATH)
        }
        val output = MediaStoreOutputOptions.Builder(
            context.contentResolver,
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        ).setContentValues(values).build()

        var pending = vc.output.prepareRecording(context, output)
        val audioAllowed = s.recordAudio && ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (audioAllowed) pending = pending.withAudioEnabled()

        if (s.shutterSound) sound.play(MediaActionSound.START_VIDEO_RECORDING)
        recording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
            when (event) {
                is VideoRecordEvent.Start ->
                    _state.update { it.copy(isRecording = true, isPaused = false, recordingMs = 0L) }
                is VideoRecordEvent.Pause -> _state.update { it.copy(isPaused = true) }
                is VideoRecordEvent.Resume -> _state.update { it.copy(isPaused = false) }
                is VideoRecordEvent.Status ->
                    _state.update { it.copy(recordingMs = event.recordingStats.recordedDurationNanos / 1_000_000L) }
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    _state.update { it.copy(isRecording = false, isPaused = false, recordingMs = 0L) }
                    val uri = event.outputResults.outputUri
                    if (uri != Uri.EMPTY) {
                        onMediaSaved(uri, isVideo = true)
                    } else {
                        Log.e(TAG, "Videoinspelning misslyckades: ${event.error}", event.cause)
                        showMessage(R.string.msg_record_failed)
                    }
                    videoCapture?.targetRotation = targetRotation
                    if (rebindAfterRecording) {
                        rebindAfterRecording = false
                        rebind()
                    }
                }
            }
        }
    }

    fun stopRecording() {
        val r = recording ?: return
        if (settings.value.shutterSound) sound.play(MediaActionSound.STOP_VIDEO_RECORDING)
        r.stop()
    }

    fun togglePause() {
        val r = recording ?: return
        if (_state.value.isPaused) r.resume() else r.pause()
    }

    private fun onMediaSaved(uri: Uri, isVideo: Boolean) {
        viewModelScope.launch {
            val thumb = media.loadThumbnail(uri)
            _state.update { it.copy(lastUri = uri, lastThumbnail = thumb ?: it.lastThumbnail, lastIsVideo = isVideo) }
            media.notifyChanged()
        }
    }

    fun refreshThumbnail() {
        viewModelScope.launch {
            val latest = media.latest()
            if (latest == null) {
                _state.update { it.copy(lastUri = null, lastThumbnail = null) }
                return@launch
            }
            val thumb = media.loadThumbnail(latest.uri)
            _state.update { it.copy(lastUri = latest.uri, lastThumbnail = thumb, lastIsVideo = latest.isVideo) }
        }
    }

    fun showMessage(@StringRes res: Int) {
        _state.update { it.copy(message = res, messageId = System.nanoTime()) }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }
}

/** Ett försök att binda kameran (se fallback-ordningen i bindUseCases). */
private data class BindTry(
    val extension: Boolean,
    val stabilization: Boolean,
    val highFps: Boolean,
    val selector: CameraSelector
)
