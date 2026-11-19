package se.snc.camera.camera

import android.Manifest
import android.location.Location
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
import androidx.camera.core.UseCaseGroup
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
import se.snc.camera.editor.PhotoFilter
import se.snc.camera.editor.withColorMatrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import se.snc.camera.data.PhotoFormat
import se.snc.camera.editor.centerCropToRatio
import androidx.camera.core.ImageAnalysis
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.withTimeoutOrNull
import se.snc.camera.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.graphics.Bitmap
import android.graphics.Gainmap
import androidx.annotation.RequiresApi
import kotlin.math.pow
import android.util.Size
import kotlinx.coroutines.channels.Channel
import android.graphics.SurfaceTexture
import android.os.SystemClock
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraInfo
import androidx.camera.core.DynamicRange
import androidx.camera.core.ExperimentalSessionConfig
import androidx.camera.video.ExperimentalHighSpeedVideo
import androidx.camera.video.HighSpeedVideoSessionConfig
import androidx.camera.video.VideoCapabilities
import kotlinx.coroutines.isActive
import android.graphics.Matrix
import androidx.camera.core.ConcurrentCamera
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import androidx.camera.core.ExperimentalGetImage
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.common.InputImage
import se.snc.camera.scan.CodeBox
import se.snc.camera.scan.DocFilter
import se.snc.camera.scan.DocumentMath
import se.snc.camera.scan.DocumentPdf
import se.snc.camera.scan.ScanCodes
import se.snc.camera.scan.ScanFrame
import java.io.File

@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
class CameraViewModel(app: Application) : AndroidViewModel(app) {

    private companion object {
        const val TAG = "SncCamera"
        /** HDR: exponeringar i EV (den första är referensen) i handen och på stativ. */
        val HDR_EVS_HAND = listOf(0f, -2f, 2f)
        val HDR_EVS_STEADY = listOf(0f, -1.5f, 1.5f, -3f, 3f)
        /** Serietagning: högst så många bilder per serie och så många på väg samtidigt. */
        const val BURST_MAX = 50
        const val BURST_IN_FLIGHT = 2
        /** Pro-hjälpmedlens analysbild (4:3). */
        val ASSIST_SIZE = Size(640, 480)
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
    /** RAW eller RAW + JPEG när kameran är bunden med RAW, annars null (vanlig JPEG). */
    private var rawFormat: PhotoFormat? = null
    /** Sökarens form (lång sida / kort sida) – helskärmsbilder beskärs till exakt den. */
    private var viewfinderLongShort: Float = 20f / 9f
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    // Nattläget utan tillverkarens extension: bildrutor i full upplösning som läggs ihop (NightStack.kt)
    private var nightAnalysis: ImageAnalysis? = null
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val stackParts = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
    private val stackPool: ExecutorService = Executors.newFixedThreadPool(stackParts)
    private val planeBuffers = PlaneBuffers()
    @Volatile private var stackSession: FrameSink? = null
    @Volatile private var lastExposureNs: Long = 0L
    @Volatile private var lastIso: Int = 0
    private val motion by lazy { MotionMeter(context) { steady -> onSteadyChanged(steady) } }
    private val stackAnalyzer = ImageAnalysis.Analyzer { image ->
        try {
            val session = stackSession
            if (session != null && !session.finished) {
                if (session.seen == 0) {
                    val p = image.planes
                    Log.i(
                        TAG,
                        "Natt: första bildrutan ${image.width}x${image.height} format=${image.format} " +
                            "rad=${p[0].rowStride} uv=${p[1].rowStride}/${p[1].pixelStride}"
                    )
                }
                session.onFrame(image.toFrameView(planeBuffers), image.imageInfo.rotationDegrees, image.imageInfo.timestamp)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Bildrutan kunde inte läsas", t)
        } finally {
            image.close()
        }
    }
    // Pro-hjälpmedel (ProAssist.kt): liten analysbild för histogram, fokusmarkering och zebra
    @Volatile private var assistAnalysis: ImageAnalysis? = null
    private val _assist = MutableStateFlow<AssistFrame?>(null)
    val assist: StateFlow<AssistFrame?> = _assist.asStateFlow()
    private var assistFailShown = false
    private val assistAnalyzer = AssistAnalyzer(
        options = {
            val s = settings.value
            AssistOptions(s.histogram, s.focusPeaking, s.zebra, mirror = _state.value.lensFront)
        },
        onFrame = { f -> if (assistAnalysis != null) _assist.value = f }
    )

    // Tidsförlopp (Timelapse.kt): en OpenGL-effekt på videon släpper igenom en bildruta per intervall
    private val timelapseGate = TimelapseGate(1_000_000_000L)
    private var timelapseEffect: VideoFilterEffect? = null
    private var timelapseBound = false
    private var timelapseAllowed = true

    // Slow motion (SlowMotion.kt): CameraX höghastighetssession om telefonen har profilerna, annars Camera2
    private var slowProbeDone = false
    private var slowCameraXId: String? = null
    private var slowCameraXFps: List<Int> = emptyList()
    private var slowCameraXBroken = false
    private var slowCamera2Options: List<SlowOption> = emptyList()
    private var slowCam: SlowMotionCamera? = null
    private var slowTexture: SurfaceTexture? = null
    /** Stilen för den pågående inspelningen (vanlig video, slow motion eller tidsförlopp). */
    private var recordingStyle = VideoStyle.NORMAL
    private var recordingFps = 30
    private var recordingTicker: Job? = null

    // Fram och bak samtidigt (DualCamera.kt): två sökare och två ImageCapture
    private var dualView: PreviewView? = null
    private var dualBackCapture: ImageCapture? = null
    private var dualFrontCapture: ImageCapture? = null
    private var dualBound = false

    // Dubbelvideo (DualVideo.kt): båda sökarna går genom en egen OpenGL-tråd som också spelar in
    private var dualRenderer: DualVideoRenderer? = null
    private var dualVideoBound = false
    private var dualRecorder: DualRecorder? = null
    private var dualStopping = false
    private var dualVideoFailShown = false
    /** Filmen sparas klart även om kameraskärmen stängs under tiden. */
    private val recorderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stopObserver = LifecycleEventObserver { _, event ->
        // Appen går till bakgrunden: kamerorna stängs, så filmen avslutas och sparas
        if (event == Lifecycle.Event.ON_STOP) finishDualRecording()
    }

    // Spara plats (LocationTracker.kt): lyssnar bara medan kameraskärmen är öppen och inställningen är på
    private val locationTracker = LocationTracker(context)

    // Panorama (Panorama.kt, PanoramaStitcher.kt): gyrot väljer bilderna, en egen tråd syr ihop dem
    private val panoMotion by lazy { PanoramaMotion(context) }
    private var panoAnalysis: ImageAnalysis? = null
    @Volatile private var panoSession: PanoramaSession? = null
    private var panoFocal = 0f
    private val _panoPreview = MutableStateFlow<Bitmap?>(null)
    val panoPreview: StateFlow<Bitmap?> = _panoPreview.asStateFlow()
    private val panoAnalyzer = ImageAnalysis.Analyzer { image ->
        val session = panoSession
        if (session != null && session.active) session.analyze(image) else image.close()
    }

    // Läget Skanna (scan/): QR- och streckkoder med ML Kit, dokument med egen hörnsökning
    private var scanAnalysis: ImageAnalysis? = null
    private val scanExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "SncScan") }
    private var barcodeScanner: BarcodeScanner? = null
    private val _scanFrame = MutableStateFlow<ScanFrame?>(null)
    val scanFrame: StateFlow<ScanFrame?> = _scanFrame.asStateFlow()
    @Volatile private var lastCodeSeenMs = 0L
    @Volatile private var dismissedRaw: String? = null
    @Volatile private var dismissedSeenMs = 0L
    private var scanY = ByteArray(0)
    private var liveQuad: FloatArray? = null
    private var quadMisses = 0
    private var lastDocAnalysisMs = 0L
    @Volatile private var scanLogged = false

    /** Dokumentet som justeras just nu: den tagna bilden och hörnen (normaliserade). */
    class DocEdit(val bitmap: Bitmap, val quad: FloatArray)
    private val _docEdit = MutableStateFlow<DocEdit?>(null)
    val docEdit: StateFlow<DocEdit?> = _docEdit.asStateFlow()
    private val docPageFiles = mutableListOf<File>()

    // Serietagning: håll in avtryckaren
    private var burstJob: Job? = null
    @Volatile private var burstHeld = false

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

    // Live-filter i video (OpenGL, VideoFilter.kt): bara bunden när ett filter är på i Video
    private var videoFilter: VideoFilterEffect? = null
    private var videoFilterBound = false
    private var videoFilterAllowed = true
    private var lastFallbackMode: CameraMode? = null
    private var pendingMode: CameraMode? = null

    /** Behörighetsdialogen visas automatiskt högst en gång per appstart. */
    var permissionsRequested = false

    private val sound = MediaActionSound()

    /** Bakkameran med flest linser (t.ex. kamera 5 på OnePlus 13R: vidvinkel + ultravidvinkel + tele). */
    private val preferredBackId: String? by lazy { LensSelection.preferredBackCameraId(context) }

    /**
     * Standardkameran bak (kamera 0 på OnePlus 13R, samma som LineageOS kameraapp). Video använder den:
     * kamera 5 ger en randig remsa längs ena kanten så fort den ska leverera 16:9, och film är alltid 16:9.
     */
    private val videoBackId: String? by lazy { LensSelection.defaultBackCameraId(context) }

    @Volatile private var lensBadges: Map<String, LensBadge> = emptyMap()
    @Volatile private var lastActiveLens: String? = null

    /** Läser vilken fysisk lins den logiska kameran använder i varje bildruta. */
    private val lensCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            // Exponering och ISO till nattbildens EXIF
            result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { lastExposureNs = it }
            result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { lastIso = it }
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
        // Sidor från en tidigare körning som aldrig blev en PDF
        runCatching { DocumentPdf.pagesDir(context).deleteRecursively() }
        sound.load(MediaActionSound.SHUTTER_CLICK)
        sound.load(MediaActionSound.START_VIDEO_RECORDING)
        sound.load(MediaActionSound.STOP_VIDEO_RECORDING)

        viewModelScope.launch {
            val s = settingsRepo.settings.first()
            val mode = pendingMode ?: if (s.keepLastMode) {
                // Dubbel öppnas aldrig av sig själv vid start (två kameror tar längre tid att starta)
                CameraMode.entries.firstOrNull { it.name == s.lastMode }
                    ?.takeIf { it != CameraMode.DUAL && it != CameraMode.SCAN } ?: CameraMode.PHOTO
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
            settingsRepo.settings.map { it.saveLocation }.distinctUntilChanged().collect { updateLocationTracking(it) }
        }
        viewModelScope.launch {
            media.changes.collect { refreshThumbnail() }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Livscykel
    // ---------------------------------------------------------------------------------------------

    fun attach(owner: LifecycleOwner, view: PreviewView) {
        lifecycleOwner?.lifecycle?.removeObserver(stopObserver)
        lifecycleOwner = owner
        owner.lifecycle.addObserver(stopObserver)
        previewView = view
        updateLocationTracking()
        rebind()
    }

    fun detach() {
        countdownJob?.cancel()
        burstHeld = false
        burstJob?.cancel()
        recording?.stop()
        recording = null
        stopStacking()
        stopAssist()
        slowCam?.closeAndWait()
        timelapseGate.disarm()
        stopTicker()
        dualBackCapture = null
        dualFrontCapture = null
        dualBound = false
        finishDualRecording()
        dualVideoBound = false
        lifecycleOwner?.lifecycle?.removeObserver(stopObserver)
        locationTracker.stop()
        cancelPanorama()
        panoAnalysis = null
        stopScan()
        motion.stop()
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
        analysisExecutor.shutdown()
        stackPool.shutdown()
        videoFilter?.release()
        timelapseEffect?.release()
        slowCam?.release()
        dualRenderer?.release()
        panoSession?.release()
        runCatching { barcodeScanner?.close() }
        scanExecutor.shutdown()
        runCatching { DocumentPdf.pagesDir(context).deleteRecursively() }
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
        // Panorama tas med bakkameran
        if (st.mode == CameraMode.PANORAMA && hasBack) front = false
        val defaultBase = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        // Bak, foto: den logiska kameran med flest linser, så att 0,6× och 2× använder de riktiga linserna.
        // Bak, video: standardkameran (0,6× och 1× är riktiga linser, 2× och 5× digital zoom), se videoBackId.
        val backId = when {
            front -> null
            st.mode.isVideo -> videoBackId
            else -> preferredBackId
        }
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

        // En pågående nattbild avbryts (kameran binds om)
        stopStacking()
        stopAssist()
        stopScan()
        cancelPanorama()
        panoAnalysis = null
        // Slow motion med Camera2 stängs (öppnas igen nedan om det fortfarande gäller)
        slowCam?.closeAndWait()
        videoFilterBound = false
        timelapseBound = false
        dualBackCapture = null
        dualFrontCapture = null
        dualBound = false
        dualVideoBound = false

        // Fram och bak samtidigt
        if (st.mode == CameraMode.DUAL) return bindDual(cp, owner, view, hasFront, hasBack)

        // Slow motion: undersök en gång vad kamerorna klarar och välj CameraX eller Camera2
        val slowWanted = st.mode == CameraMode.VIDEO && st.videoStyle == VideoStyle.SLOW_MOTION && !front
        var slowFps = 0
        var slowEngine = SlowEngine.NONE
        if (slowWanted) {
            probeSlowMotion(cp)
            slowFps = chooseSlowFps(st.slowFps)
            slowEngine = slowEngineFor(slowFps)
            if (slowEngine == SlowEngine.CAMERA2) return bindCamera2Slow(cp, slowFps, hasFront, hasBack)
        }

        // Ta bort ev. manuella Camera2-värden från förra läget
        camera?.let { old -> runCatching { Camera2CameraControl.from(old.cameraControl).clearCaptureRequestOptions() } }
        zoomLiveData?.removeObserver(zoomObserver)
        zoomLiveData = null

        var pendingRaw: PhotoFormat? = null
        var rawUnsupported = false
        var allowRaw = true
        // Pro-hjälpmedlen: en liten analysbild till. Går bindningen inte med den (t.ex. RAW + JPEG) binds
        // samma försök utan, med ett meddelande.
        var allowAssist = true
        fun attempt(useExtension: Boolean, stabilization: Boolean, highFps: Boolean, cameraBase: CameraSelector): Camera? {
            val selector = if (useExtension && wantedExtension != null && ext != null) {
                ext.getExtensionEnabledCameraSelector(cameraBase, wantedExtension)
            } else cameraBase
            val fpsRange = if (st.mode.isVideo && highFps) Range(60, 60) else null
            // Fotolägena ber aldrig kameran om 16:9: kamera 5 på OnePlus 13R skär då bort kanten två gånger och
            // en sjättedel av bilden blir randig (både sökare och bild). Sökaren och fotot tas i 4:3 (hela
            // sensorn); sökaren beskärs på skärmen (FILL_CENTER) och fotot direkt efteråt (3:2, 16:9, helskärm).
            val photoAspect = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
            val previewAspect = if (st.mode.isVideo) {
                AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
            } else photoAspect

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
            var na: ImageAnalysis? = null
            var aa: ImageAnalysis? = null
            var sa: ImageAnalysis? = null
            var pa: ImageAnalysis? = null
            // Natt (utan tillverkarens nattläge) och HDR i Foto (utan tillverkarens HDR) läggs ihop i appen
            val stacking = (st.mode == CameraMode.NIGHT || (st.mode == CameraMode.PHOTO && s.hdr)) &&
                !(useExtension && wantedExtension != null)
            if (stacking) {
                // Natt, ljusspår och silkesvatten: bildrutor i full upplösning (4:3) som läggs ihop i appen.
                // Analysen tar emot bildrutor hela tiden i nattläget; de används bara medan en bild tas.
                na = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                            .build()
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .setTargetRotation(targetRotation)
                    .build()
                useCases += na
            } else if (st.mode.isVideo) {
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
            } else if (st.mode == CameraMode.SCAN) {
                // Skanna: en analysbild (QR-kod: 1280 × 960 för små koder, dokument: 640 × 480 för hörnen)
                // och för dokument även en ImageCapture (sidan tas i full upplösning)
                val doc = st.scanKind == ScanKind.DOCUMENT
                sa = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(photoAspect)
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    if (doc) ASSIST_SIZE else Size(1280, 960),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                )
                            )
                            .build()
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    // Skärmen är alltid stående: rutorna ritas i sökarens riktning
                    .setTargetRotation(Surface.ROTATION_0)
                    .build()
                useCases += sa
                if (doc) {
                    ic = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setAspectRatioStrategy(photoAspect)
                                .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                                .build()
                        )
                        .setTargetRotation(targetRotation)
                        .setFlashMode(ImageCapture.FLASH_MODE_OFF)
                        .build()
                    useCases += ic
                }
            } else if (st.mode == CameraMode.PANORAMA) {
                // Panorama: bildrutor i RGBA (ca 1280 × 960) som sys ihop i appen – ingen ImageCapture
                pa = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(photoAspect)
                            .setResolutionStrategy(
                                ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                            )
                            .build()
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    // Skärmen är alltid stående; bilderna vrids stående i appen
                    .setTargetRotation(Surface.ROTATION_0)
                    .build()
                useCases += pa
            } else {
                // RAW (DNG) i Foto och Pro om kameran stöder det
                val wantRaw = allowRaw && s.photoFormat.isRaw && (st.mode == CameraMode.PHOTO || st.mode == CameraMode.PRO) &&
                    !(useExtension && wantedExtension != null)
                val rawOutput = if (wantRaw) {
                    val formats = runCatching {
                        ImageCapture.getImageCaptureCapabilities(cp.getCameraInfo(selector)).supportedOutputFormats
                    }.getOrDefault(emptySet())
                    when {
                        s.photoFormat == PhotoFormat.RAW && ImageCapture.OUTPUT_FORMAT_RAW in formats -> ImageCapture.OUTPUT_FORMAT_RAW
                        s.photoFormat == PhotoFormat.RAW_JPEG && ImageCapture.OUTPUT_FORMAT_RAW_JPEG in formats -> ImageCapture.OUTPUT_FORMAT_RAW_JPEG
                        else -> null
                    }
                } else null
                if (wantRaw && rawOutput == null) rawUnsupported = true
                val icBuilder = ImageCapture.Builder()
                if (rawOutput != null) icBuilder.setOutputFormat(rawOutput)
                ic = icBuilder
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
                if (s.aspect == AspectOption.RATIO_1_1 && rawOutput == null) ic.setCropAspectRatio(Rational(1, 1))
                pendingRaw = when (rawOutput) {
                    ImageCapture.OUTPUT_FORMAT_RAW -> PhotoFormat.RAW
                    ImageCapture.OUTPUT_FORMAT_RAW_JPEG -> PhotoFormat.RAW_JPEG
                    else -> null
                }
                useCases += ic
                if (st.mode == CameraMode.PRO && s.anyAssist && allowAssist) {
                    aa = ImageAnalysis.Builder()
                        .setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setAspectRatioStrategy(photoAspect)
                                .setResolutionStrategy(
                                    ResolutionStrategy(ASSIST_SIZE, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                                )
                                .build()
                        )
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        // Skärmen är alltid stående: lagret vrids som sökaren, inte som telefonen
                        .setTargetRotation(Surface.ROTATION_0)
                        .build()
                    useCases += aa
                }
            }

            // Live-filter i video: effekten läggs på sökaren och filmen. Tidsförlopp: en effekt bara på
            // videon som släpper igenom en bildruta per intervall (sökaren får filtret med RenderEffect).
            val timelapse = st.mode.isVideo && st.videoStyle == VideoStyle.TIMELAPSE
            val effect = when {
                timelapse && timelapseAllowed -> timelapseEffect
                    ?: VideoFilterEffect(CameraEffect.VIDEO_CAPTURE, timelapseGate).also { timelapseEffect = it }
                timelapse -> null
                st.mode.isVideo && st.videoStyle == VideoStyle.NORMAL && st.filter.isActive && videoFilterAllowed ->
                    videoFilter ?: VideoFilterEffect().also { videoFilter = it }
                else -> null
            }

            cp.unbindAll()
            return try {
                val cam = if (effect != null) {
                    val group = UseCaseGroup.Builder().also { b -> useCases.forEach { b.addUseCase(it) } }
                        .addEffect(effect)
                        .build()
                    cp.bindToLifecycle(owner, selector, group)
                } else {
                    cp.bindToLifecycle(owner, selector, *useCases.toTypedArray())
                }
                videoFilterBound = effect != null && effect === videoFilter
                timelapseBound = effect != null && effect === timelapseEffect
                effect?.setMatrix(if (st.filter.isActive) st.filter.matrix() else null)
                imageCapture = ic
                videoCapture = vc
                nightAnalysis = na
                na?.setAnalyzer(analysisExecutor, stackAnalyzer)
                assistAnalysis = aa
                aa?.setAnalyzer(analysisExecutor, assistAnalyzer)
                scanAnalysis = sa
                sa?.setAnalyzer(scanExecutor, scanAnalyzer)
                panoAnalysis = pa
                pa?.setAnalyzer(analysisExecutor, panoAnalyzer)
                rawFormat = if (ic != null) pendingRaw else null
                cam
            } catch (e: Exception) {
                Log.w(TAG, "Bindning misslyckades (extension=$useExtension, stab=$stabilization, 60fps=$highFps, filter=${effect != null})", e)
                if (effect != null) {
                    // Gick det inte med effekten: samma försök utan, så att video alltid fungerar
                    if (effect === timelapseEffect) {
                        timelapseAllowed = false
                        showMessage(R.string.msg_timelapse_failed)
                    } else {
                        videoFilterAllowed = false
                        showMessage(R.string.msg_video_filter_failed)
                    }
                    return attempt(useExtension, stabilization, highFps, cameraBase)
                }
                if (aa != null) {
                    // Pro-hjälpmedlen får inte plats med det här filformatet: kameran startar utan dem
                    allowAssist = false
                    if (!assistFailShown) {
                        assistFailShown = true
                        showMessage(R.string.msg_assist_failed)
                    }
                    return attempt(useExtension, stabilization, highFps, cameraBase)
                }
                null
            }
        }

        // Försök i ordning: allt påslaget → utan tillägg/stabilisering → 30 fps → standardkameran
        val wantHighFps = st.mode.isVideo && s.videoFps >= 60 && st.videoStyle == VideoStyle.NORMAL
        val tries = buildList {
            add(BindTry(true, s.videoStabilization, wantHighFps, base))
            if (wantHighFps) add(BindTry(false, false, true, base))
            add(BindTry(false, false, false, base))
            if (backId != null) add(BindTry(false, false, false, defaultBase))
        }
        var bound: Camera? = null
        var used: BindTry? = null
        lastActiveLens = null
        var slowBound = false
        if (slowEngine == SlowEngine.CAMERAX) {
            bound = bindHighSpeed(cp, owner, view, slowFps)
            if (bound != null) {
                slowBound = true
            } else {
                // CameraX klarade det inte: Camera2 om kameran har bildfrekvensen, annars vanlig video
                slowCameraXBroken = true
                if (slowEngineFor(slowFps) == SlowEngine.CAMERA2) return bindCamera2Slow(cp, slowFps, hasFront, hasBack)
                showMessage(R.string.msg_slowmo_failed)
            }
        }
        if (bound == null) {
            for (t in tries) {
                bound = attempt(t.extension, t.stabilization, t.highFps, t.selector)
                if (bound != null) {
                    used = t
                    break
                }
            }
        }
        // Gick det inte med RAW: försök igen utan RAW, så att kameran alltid startar
        if (bound == null && s.photoFormat.isRaw) {
            allowRaw = false
            rawUnsupported = true
            for (t in tries) {
                bound = attempt(t.extension, t.stabilization, t.highFps, t.selector)
                if (bound != null) {
                    used = t
                    break
                }
            }
        }
        val cam: Camera = bound ?: return false
        camera = cam
        val extensionActive = used?.extension == true && wantedExtension != null && imageCapture != null
        if (wantHighFps && used?.highFps != true) showMessage(R.string.msg_fps_unsupported)
        if (rawUnsupported && rawFormat == null) showMessage(R.string.msg_raw_unsupported)

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
                pro = pro,
                stackAvailable = nightAnalysis != null,
                videoFilterOn = videoFilterBound,
                assistActive = assistAnalysis != null,
                burstAvailable = (st.mode == CameraMode.PHOTO || st.mode == CameraMode.PRO) &&
                    imageCapture != null && !extensionActive,
                timelapseReady = timelapseBound,
                slowEngine = if (slowBound) SlowEngine.CAMERAX else SlowEngine.NONE,
                slowProbed = slowProbeDone,
                slowFpsOptions = slowFpsOptions(),
                slowFps = if (slowWanted) slowFps else it.slowFps,
                slowSizeLabel = if (slowBound) videoCapture?.resolutionInfo?.resolution?.let { r -> "${minOf(r.width, r.height)}p" } else null
            )
        }
        if (nightAnalysis != null) motion.start() else motion.stop()
        nightAnalysis?.let { Log.i(TAG, "Natt: analys ${it.resolutionInfo?.resolution} bunden, kamera $boundId") }
        assistAnalysis?.let { Log.i(TAG, "Hjälpmedel: analys ${it.resolutionInfo?.resolution} bunden, kamera $boundId") }
        scanAnalysis?.let { Log.i(TAG, "Skanna: ${_state.value.scanKind} analys ${it.resolutionInfo?.resolution} bunden, kamera $boundId") }
        panoAnalysis?.let { a ->
            val res = a.resolutionInfo?.resolution
            val longPx = res?.let { maxOf(it.width, it.height) } ?: 1280
            val ci = runCatching { Camera2CameraInfo.from(cam.cameraInfo) }.getOrNull()
            val sensor = ci?.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            // En logisk kamera kan ange flera brännvidder: välj den som motsvarar huvudlinsen (ca 24 mm)
            val diag = sensor?.let { kotlin.math.sqrt(it.width * it.width + it.height * it.height) } ?: 0f
            val focalMm = ci?.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.minByOrNull { f -> if (diag > 0f) kotlin.math.abs(f * 43.27f / diag - 25f) else 0f }
            // Brännvidden i bildpunkter: brännvidd (mm) × bildens långa sida / sensorns långa sida (mm)
            val fromCamera = if (focalMm != null && sensor != null && maxOf(sensor.width, sensor.height) > 0f) {
                focalMm * longPx / maxOf(sensor.width, sensor.height)
            } else null
            panoFocal = (fromCamera ?: (longPx / 2f / kotlin.math.tan(Math.toRadians(36.5)).toFloat()))
                .coerceIn(longPx * 0.45f, longPx * 2f)
            val vfov = Math.toDegrees(2.0 * kotlin.math.atan(longPx / 2.0 / panoFocal)).toFloat()
            _state.update { it.copy(panoVfov = vfov) }
            Log.i(
                TAG,
                "Panorama: analys $res bunden, brännvidd ${panoFocal.toInt()} px (${focalMm ?: "?"} mm), " +
                    "synfält ${vfov.toInt()}° på höjden, kamera $boundId, gyro ${if (panoMotion.available) "ja" else "nej"}"
            )
        }

        when (st.mode) {
            CameraMode.PRO -> applyProOptions()
            CameraMode.NIGHT -> if (!extensionActive) applyNightOptions()
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
     * Nattläge utan tillverkarens extension. Natt: i handen högst 1/15 s per bild (skarpa bilder, fler
     * läggs ihop), på stativ kamerans längsta exponering. Ljusspår och silkesvatten: 30 bilder per
     * sekund utan luckor. Snabb brusreducering – bruset minskar när bilderna läggs ihop.
     */
    private fun applyNightOptions() {
        val cam = camera ?: return
        val st = _state.value
        if (st.mode != CameraMode.NIGHT || nightAnalysis == null) return
        runCatching {
            val info = Camera2CameraInfo.from(cam.cameraInfo)
            val ranges = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.toList().orEmpty()
            val slowest = compareBy<Range<Int>>({ it.lower }, { -it.upper })
            val range: Range<Int>? = when {
                st.nightStyle != StackStyle.NIGHT ->
                    ranges.firstOrNull { it.lower == 30 && it.upper == 30 }
                        ?: ranges.maxWithOrNull(compareBy<Range<Int>>({ it.lower }, { it.upper }))
                st.steady -> ranges.minWithOrNull(slowest)
                else -> ranges.filter { it.lower >= 15 }.minWithOrNull(slowest) ?: ranges.minWithOrNull(slowest)
            }
            val builder = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
                .setCaptureRequestOption(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
            if (range != null) builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
            Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(builder.build())
        }.onFailure { Log.w(TAG, "Nattinställningar kunde inte sättas", it) }
    }

    private fun onSteadyChanged(steady: Boolean) {
        _state.update { it.copy(steady = steady) }
        if (!_state.value.isCapturing) applyNightOptions()
    }

    /** Natt, Ljusspår eller Silkesvatten. */
    fun setNightStyle(style: StackStyle) {
        val st = _state.value
        if (st.isCapturing || st.countdown > 0 || style == st.nightStyle) return
        _state.update { it.copy(nightStyle = style) }
        applyNightOptions()
    }

    /** Exponeringstid för ljusspår och silkesvatten (sekunder). */
    fun setExposureSeconds(seconds: Int) {
        val st = _state.value
        if (st.isCapturing || st.countdown > 0) return
        _state.update { it.copy(exposureSeconds = seconds.coerceIn(1, 8)) }
    }

    /** Avbryter en pågående nattbild och kopplar bort analysen (innan kameran binds om). */
    private fun stopStacking() {
        stackSession?.cancel()
        stackSession = null
        runCatching { nightAnalysis?.clearAnalyzer() }
        nightAnalysis = null
    }

    /** Kopplar bort Pro-hjälpmedlens analys (innan kameran binds om). */
    private fun stopAssist() {
        runCatching { assistAnalysis?.clearAnalyzer() }
        assistAnalysis = null
        _assist.value = null
    }

    /**
     * Slår på eller av ett Pro-hjälpmedel. Kameran binds om bara när det första slås på eller det sista
     * stängs av (analysbilden kopplas till eller från); däremellan räknar analysen bara något annat.
     */
    fun toggleAssist(tool: AssistTool) {
        viewModelScope.launch {
            val before = settingsRepo.settings.first().anyAssist
            settingsRepo.update {
                when (tool) {
                    AssistTool.HISTOGRAM -> it.copy(histogram = !it.histogram)
                    AssistTool.PEAKING -> it.copy(focusPeaking = !it.focusPeaking)
                    AssistTool.ZEBRA -> it.copy(zebra = !it.zebra)
                }
            }
            val after = settingsRepo.settings.first().anyAssist
            if (before != after && _state.value.mode == CameraMode.PRO) {
                assistFailShown = false
                rebind()
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Slow motion och tidsförlopp
    // ---------------------------------------------------------------------------------------------

    private fun idOf(info: CameraInfo): String? = runCatching { Camera2CameraInfo.from(info).cameraId }.getOrNull()

    /** Upplösning för slow motion i CameraX: 1080p om det finns, annars 720p, annars det som finns. */
    private fun slowQualitySelector(caps: VideoCapabilities): QualitySelector {
        val supported = runCatching { caps.getSupportedQualities(DynamicRange.SDR) }.getOrDefault(emptyList())
        val pick = listOf(Quality.FHD, Quality.HD, Quality.SD).firstOrNull { it in supported } ?: supported.firstOrNull() ?: Quality.HD
        return QualitySelector.from(pick, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
    }

    /**
     * Undersöker en gång vilka bildfrekvenser (minst 120) som går: CameraX höghastighetssession (kräver
     * telefonens höghastighetsprofiler) och Camera2 (kräver bara att kameran klarar höghastighetsvideo).
     */
    @kotlin.OptIn(ExperimentalSessionConfig::class, ExperimentalHighSpeedVideo::class)
    private fun probeSlowMotion(cp: ProcessCameraProvider) {
        if (slowProbeDone) return
        slowProbeDone = true
        runCatching {
            val backs = cp.availableCameraInfos
                .filter { it.lensFacing == CameraSelector.LENS_FACING_BACK }
                .sortedBy { idOf(it)?.toIntOrNull() ?: 99 }
            for (info in backs) {
                val caps = Recorder.getHighSpeedVideoCapabilities(info) ?: continue
                val vc = VideoCapture.withOutput(Recorder.Builder().setQualitySelector(slowQualitySelector(caps)).build())
                val cfg = HighSpeedVideoSessionConfig.Builder(vc)
                    .setPreview(Preview.Builder().build())
                    .setSlowMotionEnabled(true)
                    .build()
                val fps = info.getSupportedFrameRateRanges(cfg)
                    .filter { it.lower == it.upper && it.upper >= 120 }
                    .map { it.upper }
                    .distinct()
                    .sorted()
                if (fps.isNotEmpty()) {
                    slowCameraXId = idOf(info)
                    slowCameraXFps = fps
                    break
                }
            }
        }.onFailure { Log.w(TAG, "Slowmo: CameraX kunde inte undersökas", it) }
        slowCamera2Options = runCatching { SlowMotionProbe.camera2Options(context) }.getOrDefault(emptyList())
        Log.i(
            TAG,
            "Slowmo: CameraX ${if (slowCameraXFps.isEmpty()) "nej" else "kamera $slowCameraXId $slowCameraXFps"}, " +
                "Camera2 ${slowCamera2Options.joinToString { "${it.cameraId}:${it.width}x${it.height}@${it.fps}" }.ifEmpty { "nej" }}"
        )
    }

    private fun cameraXSlowFps(): List<Int> = if (slowCameraXBroken) emptyList() else slowCameraXFps

    /** Alla bildfrekvenser för slow motion, från CameraX och Camera2. */
    private fun slowFpsOptions(): List<Int> = (cameraXSlowFps() + slowCamera2Options.map { it.fps }).distinct().sorted()

    /** Vald bildfrekvens om den finns, annars 240, annars den närmaste. */
    private fun chooseSlowFps(wanted: Int): Int {
        val all = slowFpsOptions()
        if (all.isEmpty()) return 0
        if (wanted in all) return wanted
        return all.firstOrNull { it == 240 } ?: all.minBy { kotlin.math.abs(it - 240) }
    }

    private fun slowEngineFor(fps: Int): SlowEngine = when {
        fps <= 0 -> SlowEngine.NONE
        fps in cameraXSlowFps() -> SlowEngine.CAMERAX
        slowCamera2Options.any { it.fps == fps } -> SlowEngine.CAMERA2
        else -> SlowEngine.NONE
    }

    /** Slow motion med CameraX: höghastighetssession med sökare och video, filmen sparas i 30 fps. */
    @kotlin.OptIn(ExperimentalSessionConfig::class, ExperimentalHighSpeedVideo::class)
    private fun bindHighSpeed(cp: ProcessCameraProvider, owner: LifecycleOwner, view: PreviewView, fps: Int): Camera? {
        val id = slowCameraXId ?: return null
        return try {
            val info = cp.availableCameraInfos.firstOrNull { idOf(it) == id } ?: return null
            val caps = Recorder.getHighSpeedVideoCapabilities(info) ?: return null
            val recorder = Recorder.Builder().setQualitySelector(slowQualitySelector(caps)).build()
            val vc = VideoCapture.Builder(recorder).setTargetRotation(targetRotation).build()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
            val probe = HighSpeedVideoSessionConfig.Builder(vc).setPreview(preview).setSlowMotionEnabled(true).build()
            val range = info.getSupportedFrameRateRanges(probe).firstOrNull { it.lower == fps && it.upper == fps } ?: return null
            val cfg = HighSpeedVideoSessionConfig.Builder(vc)
                .setPreview(preview)
                .setSlowMotionEnabled(true)
                .setFrameRateRange(range)
                .build()
            val selector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .addCameraFilter { infos -> infos.filter { idOf(it) == id } }
                .build()
            cp.unbindAll()
            val cam = cp.bindToLifecycle(owner, selector, cfg)
            videoCapture = vc
            imageCapture = null
            nightAnalysis = null
            assistAnalysis = null
            rawFormat = null
            Log.i(TAG, "Slowmo: CameraX $fps fps bunden på kamera $id (${vc.resolutionInfo?.resolution})")
            cam
        } catch (e: Exception) {
            Log.w(TAG, "Slowmo: CameraX-bindningen misslyckades ($fps fps)", e)
            runCatching { cp.unbindAll() }
            null
        }
    }

    /**
     * Slow motion med Camera2: CameraX släpper kameran och appen öppnar den själv (SlowMotionCamera) när
     * sökarens TextureView finns. Avtryckaren blir aktiv när sessionen är klar.
     */
    private fun bindCamera2Slow(cp: ProcessCameraProvider, fps: Int, hasFront: Boolean, hasBack: Boolean): Boolean {
        val opt = slowCamera2Options.firstOrNull { it.fps == fps } ?: return false
        camera?.let { old -> runCatching { Camera2CameraControl.from(old.cameraControl).clearCaptureRequestOptions() } }
        zoomLiveData?.removeObserver(zoomObserver)
        zoomLiveData = null
        cp.unbindAll()
        camera = null
        imageCapture = null
        videoCapture = null
        nightAnalysis = null
        rawFormat = null
        lensBadges = emptyMap()
        motion.stop()
        _state.update {
            it.copy(
                isReady = false,
                lensFront = false,
                hasFrontCamera = hasFront,
                hasBackCamera = hasBack,
                hasFlashUnit = false,
                torchOn = false,
                activeLens = null,
                extensionActive = false,
                exposureSupported = false,
                stackAvailable = false,
                videoFilterOn = false,
                assistActive = false,
                burstAvailable = false,
                timelapseReady = false,
                slowEngine = SlowEngine.CAMERA2,
                slowProbed = true,
                slowFpsOptions = slowFpsOptions(),
                slowFps = fps,
                slowSizeLabel = opt.label,
                slowWidth = opt.width,
                slowHeight = opt.height,
                zoomRatio = 1f,
                minZoom = 1f,
                maxZoom = 1f
            )
        }
        Log.i(TAG, "Slowmo: Camera2 ${opt.width}x${opt.height} @ $fps på kamera ${opt.cameraId}")
        slowTexture?.let { slowController().open(opt, it) }
        return true
    }

    /** Svaren från SlowMotionCamera kommer på dess egen tråd och hanteras på huvudtråden. */
    private fun slowController(): SlowMotionCamera = slowCam ?: SlowMotionCamera(context, object : SlowMotionCamera.Listener {
        override fun onReady() {
            viewModelScope.launch {
                if (_state.value.slowEngine == SlowEngine.CAMERA2) _state.update { it.copy(isReady = true) }
            }
        }

        override fun onRecordingStarted() {
            viewModelScope.launch {
                recordingStyle = VideoStyle.SLOW_MOTION
                recordingFps = _state.value.slowFps
                _state.update { it.copy(isRecording = true, isPaused = false, recordingMs = 0L, recordingOutputMs = 0L) }
                startTicker()
            }
        }

        override fun onRecordingStopped(uri: Uri?) {
            viewModelScope.launch {
                stopTicker()
                _state.update { it.copy(isRecording = false, isPaused = false, recordingMs = 0L, recordingOutputMs = 0L) }
                if (uri != null) onMediaSaved(uri, isVideo = true) else showMessage(R.string.msg_record_failed)
                if (rebindAfterRecording) {
                    rebindAfterRecording = false
                    rebind()
                }
            }
        }

        override fun onError(stage: String, error: Throwable?) {
            Log.w(TAG, "Slowmo: fel ($stage): $error", error)
            showMessage(R.string.msg_slowmo_failed)
        }
    }).also { slowCam = it }

    /** Sökarens TextureView för slow motion med Camera2 finns (eller har bytts ut). */
    fun onSlowPreviewAvailable(texture: SurfaceTexture) {
        slowTexture = texture
        val st = _state.value
        if (st.slowEngine != SlowEngine.CAMERA2 || !st.mode.isVideo) return
        val opt = slowCamera2Options.firstOrNull { it.fps == st.slowFps } ?: return
        slowController().open(opt, texture)
    }

    fun onSlowPreviewGone() {
        slowTexture = null
        slowCam?.closeAndWait()
    }

    /** Video, slow motion eller tidsförlopp. */
    fun setVideoStyle(style: VideoStyle) {
        val st = _state.value
        if (st.isRecording || st.countdown > 0 || style == st.videoStyle) return
        _state.update { it.copy(videoStyle = style, filterPanelOpen = false) }
        if (st.mode.isVideo) rebind()
    }

    fun setSlowFps(fps: Int) {
        val st = _state.value
        if (st.isRecording || st.countdown > 0 || fps == st.slowFps) return
        _state.update { it.copy(slowFps = fps) }
        if (st.mode.isVideo && st.videoStyle == VideoStyle.SLOW_MOTION) rebind()
    }

    fun setTimelapseInterval(ms: Int) {
        val st = _state.value
        if (st.isRecording || st.countdown > 0) return
        _state.update { it.copy(timelapseIntervalMs = ms) }
    }

    /** Klockan i sökaren under slow motion och tidsförlopp: verklig tid och filmens längd. */
    private fun startTicker() {
        recordingTicker?.cancel()
        val start = SystemClock.elapsedRealtime()
        recordingTicker = viewModelScope.launch {
            while (isActive) {
                val real = SystemClock.elapsedRealtime() - start
                val out = when (recordingStyle) {
                    VideoStyle.SLOW_MOTION -> real * recordingFps / 30
                    VideoStyle.TIMELAPSE -> timelapseGate.outputMs
                    VideoStyle.NORMAL -> real
                }
                _state.update { it.copy(recordingMs = real, recordingOutputMs = out) }
                delay(250)
            }
        }
    }

    private fun stopTicker() {
        recordingTicker?.cancel()
        recordingTicker = null
    }

    // ---------------------------------------------------------------------------------------------
    // Fram och bak samtidigt (läget Dubbel)
    // ---------------------------------------------------------------------------------------------

    /** Frontkamerans sökare i läget Dubbel (bakkameran använder den vanliga sökaren). */
    fun setDualView(view: PreviewView?) {
        dualView = view
    }

    /**
     * Ett par med en bak- och en frontkamera som telefonen kan köra samtidigt. Helst fotokameran (flest
     * linser), annars standardkameran, annars första paret.
     */
    private fun findDualPair(cp: ProcessCameraProvider): Pair<CameraInfo, CameraInfo>? {
        val pairs = runCatching { cp.availableConcurrentCameraInfos }.getOrDefault(emptyList())
        val candidates = pairs.mapNotNull { list ->
            if (list.size != 2) return@mapNotNull null
            val back = list.firstOrNull { it.lensFacing == CameraSelector.LENS_FACING_BACK } ?: return@mapNotNull null
            val front = list.firstOrNull { it.lensFacing == CameraSelector.LENS_FACING_FRONT } ?: return@mapNotNull null
            back to front
        }
        return candidates.firstOrNull { idOf(it.first) == preferredBackId }
            ?: candidates.firstOrNull { idOf(it.first) == "0" }
            ?: candidates.firstOrNull()
    }

    /**
     * Läget Dubbel: CameraX samtidig kamera med en sökare och en ImageCapture per kamera (4:3). Går det
     * inte med ImageCapture binds bara sökarna, och fotot tas då från sökarna. Saknar telefonen stöd
     * visas ett besked i sökaren.
     */
    private fun bindDual(cp: ProcessCameraProvider, owner: LifecycleOwner, view: PreviewView, hasFront: Boolean, hasBack: Boolean): Boolean {
        camera?.let { old -> runCatching { Camera2CameraControl.from(old.cameraControl).clearCaptureRequestOptions() } }
        zoomLiveData?.removeObserver(zoomObserver)
        zoomLiveData = null
        cp.unbindAll()
        camera = null
        imageCapture = null
        videoCapture = null
        nightAnalysis = null
        assistAnalysis = null
        rawFormat = null
        lensBadges = emptyMap()
        motion.stop()

        val second = dualView
        val pair = findDualPair(cp)
        val feature = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_CONCURRENT)
        Log.i(
            TAG,
            "Dubbel: par ${runCatching { cp.availableConcurrentCameraInfos.map { l -> l.map { idOf(it) } } }.getOrNull()}, " +
                "samtidig kamera ${if (feature) "ja" else "nej"}, valt ${pair?.let { "${idOf(it.first)}+${idOf(it.second)}" } ?: "inget"}"
        )

        fun publish(ready: Boolean, available: Boolean) = _state.update {
            it.copy(
                isReady = ready,
                dualAvailable = available,
                dualVideoReady = dualVideoBound,
                lensFront = false,
                hasFrontCamera = hasFront,
                hasBackCamera = hasBack,
                hasFlashUnit = false,
                torchOn = false,
                activeLens = null,
                extensionActive = false,
                exposureSupported = false,
                stackAvailable = false,
                videoFilterOn = false,
                assistActive = false,
                burstAvailable = false,
                timelapseReady = false,
                slowEngine = SlowEngine.NONE,
                zoomRatio = 1f,
                minZoom = 1f,
                maxZoom = 1f
            )
        }

        if (pair == null || second == null || !feature) {
            publish(ready = false, available = false)
            return true
        }
        val (backInfo, frontInfo) = pair
        // CameraInfo.cameraSelector pekar ut just den kameran (samma som i paret)
        fun selectorFor(info: CameraInfo): CameraSelector = info.cameraSelector
        fun ratio() = ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
        val previewBack = Preview.Builder().setResolutionSelector(ratio().build()).build().also { it.setSurfaceProvider(view.surfaceProvider) }
        val previewFront = Preview.Builder().setResolutionSelector(ratio().build()).build().also { it.setSurfaceProvider(second.surfaceProvider) }

        fun bind(withCapture: Boolean): ConcurrentCamera? = try {
            fun capture() = ImageCapture.Builder()
                .setResolutionSelector(ratio().setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY).build())
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                // Bilden tas stående (som skärmen); hela den sammansatta bilden vrids efter telefonen
                .setTargetRotation(Surface.ROTATION_0)
                .build()
            val icBack = if (withCapture) capture() else null
            val icFront = if (withCapture) capture() else null
            val groupBack = UseCaseGroup.Builder().addUseCase(previewBack).also { b -> icBack?.let { b.addUseCase(it) } }.build()
            val groupFront = UseCaseGroup.Builder().addUseCase(previewFront).also { b -> icFront?.let { b.addUseCase(it) } }.build()
            cp.unbindAll()
            val cc = cp.bindToLifecycle(
                listOf(
                    ConcurrentCamera.SingleCameraConfig(selectorFor(backInfo), groupBack, owner),
                    ConcurrentCamera.SingleCameraConfig(selectorFor(frontInfo), groupFront, owner)
                )
            )
            dualBackCapture = icBack
            dualFrontCapture = icFront
            cc
        } catch (e: Exception) {
            Log.w(TAG, "Dubbel: bindningen misslyckades (foto ${if (withCapture) "ja" else "nej"})", e)
            runCatching { cp.unbindAll() }
            null
        }

        /**
         * Dubbelvideo: bara sökarna (4:3, helst 1440 × 1080), var och en genom den gemensamma
         * OpenGL-tråden som också ritar filmen.
         */
        fun bindVideo(): ConcurrentCamera? = try {
            val r = dualRenderer ?: DualVideoRenderer().also { dualRenderer = it }
            r.configure(backInfo.sensorRotationDegrees, frontInfo.sensorRotationDegrees, settings.value.mirrorFront)
            fun videoPreview(target: PreviewView) = Preview.Builder()
                .setResolutionSelector(
                    ratio().setResolutionStrategy(
                        ResolutionStrategy(Size(1440, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                    ).build()
                )
                .build()
                .also { it.setSurfaceProvider(target.surfaceProvider) }
            val groupBack = UseCaseGroup.Builder().addUseCase(videoPreview(view)).addEffect(r.backEffect).build()
            val groupFront = UseCaseGroup.Builder().addUseCase(videoPreview(second)).addEffect(r.frontEffect).build()
            cp.unbindAll()
            cp.bindToLifecycle(
                listOf(
                    ConcurrentCamera.SingleCameraConfig(selectorFor(backInfo), groupBack, owner),
                    ConcurrentCamera.SingleCameraConfig(selectorFor(frontInfo), groupFront, owner)
                )
            ).also { dualVideoBound = true }
        } catch (e: Exception) {
            Log.w(TAG, "Dubbelvideo: bindningen misslyckades", e)
            runCatching { cp.unbindAll() }
            null
        }

        val wantVideo = _state.value.dualVideo
        val cc = (if (wantVideo) bindVideo() else null) ?: bind(withCapture = true) ?: bind(withCapture = false)
        if (cc == null) {
            publish(ready = false, available = false)
            return true
        }
        dualBound = true
        publish(ready = true, available = true)
        if (wantVideo && !dualVideoBound && !dualVideoFailShown) {
            dualVideoFailShown = true
            showMessage(R.string.msg_dual_video_unsupported)
        }
        Log.i(
            TAG,
            if (dualVideoBound) {
                "Dubbel: bunden för video – bak ${idOf(backInfo)} (${backInfo.sensorRotationDegrees}°), fram ${idOf(frontInfo)} (${frontInfo.sensorRotationDegrees}°)"
            } else {
                "Dubbel: bunden – bak ${idOf(backInfo)} ${dualBackCapture?.resolutionInfo?.resolution ?: "(sökaren)"}, " +
                    "fram ${idOf(frontInfo)} ${dualFrontCapture?.resolutionInfo?.resolution ?: "(sökaren)"}"
            }
        )
        return true
    }

    /** Läget Dubbel: Foto eller Video. */
    fun setDualVideo(video: Boolean) {
        val st = _state.value
        if (st.isRecording || st.countdown > 0 || st.isCapturing || video == st.dualVideo) return
        _state.update { it.copy(dualVideo = video) }
        dualVideoFailShown = false
        Log.i(TAG, "Dubbel: ${if (video) "video" else "foto"}")
        if (st.mode == CameraMode.DUAL) rebind()
    }

    @SuppressLint("MissingPermission")
    private fun startDualRecording() {
        val r = dualRenderer
        if (!dualVideoBound || r == null) {
            showMessage(R.string.msg_dual_video_unsupported)
            return
        }
        if (dualRecorder != null) return
        val st = _state.value
        val s = settings.value
        val audio = s.recordAudio && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val deviceDegrees = when (targetRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        // Samma vridning som fotot: liggande telefon ger halvorna bredvid varandra
        val hint = (360 - deviceDegrees) % 360
        r.frontOnTop = st.dualSwapped
        r.setMatrix(if (st.filter.isActive) st.filter.matrix() else null)
        r.setMirrorFront(s.mirrorFront)
        val rec = DualRecorder(context, r)
        dualRecorder = rec
        dualStopping = false
        if (s.shutterSound) sound.play(MediaActionSound.START_VIDEO_RECORDING)
        viewModelScope.launch {
            val ok = rec.start(withAudio = audio, orientationHint = hint, location = currentLocation(s))
            if (dualRecorder !== rec) {
                // Stoppad (t.ex. appen stängdes) medan den startade
                return@launch
            }
            if (!ok) {
                dualRecorder = null
                showMessage(R.string.msg_record_failed)
                return@launch
            }
            recordingStyle = VideoStyle.NORMAL
            _state.update { it.copy(isRecording = true, isPaused = false, recordingMs = 0L, recordingOutputMs = 0L) }
            startTicker()
        }
    }

    private fun stopDualRecording() {
        val rec = dualRecorder ?: return
        if (dualStopping || !_state.value.isRecording) return
        dualStopping = true
        if (settings.value.shutterSound) sound.play(MediaActionSound.STOP_VIDEO_RECORDING)
        viewModelScope.launch {
            val uri = rec.stop()
            if (dualRecorder === rec) dualRecorder = null
            dualStopping = false
            stopTicker()
            _state.update { it.copy(isRecording = false, isPaused = false, recordingMs = 0L, recordingOutputMs = 0L) }
            if (uri != null) onMediaSaved(uri, isVideo = true) else showMessage(R.string.msg_record_failed)
            if (rebindAfterRecording) {
                rebindAfterRecording = false
                rebind()
            }
        }
    }

    /**
     * Avslutar en pågående dubbelvideo direkt (appen går till bakgrunden eller kameraskärmen stängs).
     * Filmen sparas klart i bakgrunden.
     */
    private fun finishDualRecording() {
        val rec = dualRecorder ?: return
        dualRecorder = null
        dualStopping = false
        stopTicker()
        _state.update { it.copy(isRecording = false, isPaused = false, recordingMs = 0L, recordingOutputMs = 0L) }
        recorderScope.launch {
            val uri = rec.stop()
            if (uri != null) withContext(Dispatchers.Main) { onMediaSaved(uri, isVideo = true) }
        }
    }

    /** Byter plats på halvorna (knappen eller håll in och dra). */
    fun swapDual() {
        val st = _state.value
        if (st.mode != CameraMode.DUAL || st.isCapturing || st.countdown > 0) return
        _state.update { it.copy(dualSwapped = !it.dualSwapped) }
        // Dubbelvideo: bytet syns direkt i filmen
        dualRenderer?.frontOnTop = _state.value.dualSwapped
        Log.i(TAG, "Dubbel: bytt plats (${if (_state.value.dualSwapped) "frontkameran" else "bakkameran"} överst)")
    }

    /** En bild från en av kamerorna, rättvänd för stående skärm (och spegelvänd om [mirror]). */
    private suspend fun captureUpright(ic: ImageCapture, mirror: Boolean): Bitmap = suspendCancellableCoroutine { cont ->
        ic.takePicture(Dispatchers.Default.asExecutor(), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val raw = image.toBitmap()
                    val m = Matrix().apply {
                        postRotate(image.imageInfo.rotationDegrees.toFloat())
                        if (mirror) postScale(-1f, 1f)
                    }
                    val upright = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
                    if (upright !== raw) raw.recycle()
                    cont.resume(upright)
                } catch (t: Throwable) {
                    cont.resumeWithException(t)
                } finally {
                    image.close()
                }
            }

            override fun onError(exception: ImageCaptureException) {
                cont.resumeWithException(exception)
            }
        })
    }

    /**
     * Fotot i läget Dubbel: båda kamerorna tar en bild samtidigt, bilderna läggs över varandra i samma
     * ordning som i sökaren och hela bilden vrids efter hur telefonen hålls (liggande = bredvid varandra).
     */
    private fun takeDualShot() {
        val st = _state.value
        val s = settings.value
        if (st.isCapturing || !dualBound) return
        val icBack = dualBackCapture
        val icFront = dualFrontCapture
        val rotationNow = targetRotation
        _state.update { it.copy(isCapturing = true, shutterBlink = System.currentTimeMillis()) }
        if (s.shutterSound) sound.play(MediaActionSound.SHUTTER_CLICK)
        viewModelScope.launch {
            try {
                val (back, front) = if (icBack != null && icFront != null) {
                    coroutineScope {
                        val b = async { captureUpright(icBack, mirror = false) }
                        val f = async { captureUpright(icFront, mirror = s.mirrorFront) }
                        b.await() to f.await()
                    }
                } else {
                    // Bara sökarna gick att binda: bilden tas från sökarna (skärmens upplösning)
                    val b = previewView?.bitmap ?: error("ingen bild från bakkameran")
                    val f = dualView?.bitmap ?: error("ingen bild från frontkameran")
                    b to f
                }
                val top = if (st.dualSwapped) front else back
                val bottom = if (st.dualSwapped) back else front
                val deviceDegrees = when (rotationNow) {
                    Surface.ROTATION_90 -> 90
                    Surface.ROTATION_180 -> 180
                    Surface.ROTATION_270 -> 270
                    else -> 0
                }
                val filter = if (st.filter.isActive) st.filter.matrix() else null
                val composed = withContext(Dispatchers.Default) {
                    DualCompose.stack(top, bottom, DUAL_HALF_RATIO, (360 - deviceDegrees) % 360, filter)
                }
                val exif = baseExif("SNC Camera: fram och bak samtidigt", 0L, 0)
                val photo = Watermark.specFor(s, exif)?.let { spec ->
                    withContext(Dispatchers.Default) { Watermark.apply(context, composed, spec) }
                } ?: composed
                val uri = media.saveNewPhoto(photo, s.photoFormat, s.jpegQuality, exif, prefix = "DUAL", location = currentLocation(s))
                if (uri != null) {
                    Log.i(TAG, "Dubbel: sparad ${photo.width}x${photo.height} (${if (icBack != null) "kamerornas bilder" else "sökarna"})")
                    onMediaSaved(uri, isVideo = false)
                } else {
                    showMessage(R.string.msg_capture_failed)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Dubbel: bilden kunde inte tas", e)
                showMessage(R.string.msg_capture_failed)
            } finally {
                _state.update { it.copy(isCapturing = false) }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Läget Skanna: QR- och streckkoder, dokument till PDF
    // ---------------------------------------------------------------------------------------------

    private fun stopScan() {
        runCatching { scanAnalysis?.clearAnalyzer() }
        scanAnalysis = null
        _scanFrame.value = null
        liveQuad = null
    }

    fun setScanKind(kind: ScanKind) {
        val st = _state.value
        if (kind == st.scanKind || st.isCapturing) return
        _state.update { it.copy(scanKind = kind, scanCode = null) }
        if (st.mode == CameraMode.SCAN) rebind()
    }

    /** Stänger kortet; samma kod visas inte igen förrän den har varit borta en stund. */
    fun dismissCode() {
        dismissedRaw = _state.value.scanCode?.raw
        dismissedSeenMs = SystemClock.uptimeMillis()
        _state.update { it.copy(scanCode = null) }
    }

    fun dismissPdf() {
        _state.update { it.copy(lastPdf = null) }
    }

    private val scanAnalyzer = ImageAnalysis.Analyzer { image ->
        when (_state.value.scanKind) {
            ScanKind.CODE -> analyzeCode(image)
            ScanKind.DOCUMENT -> analyzeDocument(image)
        }
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun analyzeCode(image: ImageProxy) {
        val media = image.image
        if (media == null) {
            image.close()
            return
        }
        val rotation = image.imageInfo.rotationDegrees
        val uw = if (rotation % 180 == 0) image.width else image.height
        val uh = if (rotation % 180 == 0) image.height else image.width
        val scanner = barcodeScanner ?: ScanCodes.newScanner().also { barcodeScanner = it }
        if (!scanLogged) {
            scanLogged = true
            Log.i(TAG, "Skanna: första bildrutan ${image.width}x${image.height} vriden $rotation°")
        }
        scanner.process(InputImage.fromMediaImage(media, rotation))
            .addOnSuccessListener(scanExecutor) { codes ->
                val now = SystemClock.uptimeMillis()
                val found = codes.mapNotNull { b -> ScanCodes.from(b)?.let { it to b.boundingBox } }
                if (found.isEmpty()) {
                    // Rutorna ligger kvar en kort stund så att de inte blinkar
                    if (now - lastCodeSeenMs > 600) _scanFrame.value = ScanFrame(uw, uh)
                    return@addOnSuccessListener
                }
                lastCodeSeenMs = now
                _scanFrame.value = ScanFrame(
                    uw, uh,
                    boxes = found.mapNotNull { (_, r) -> r?.let { CodeBox(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat()) } }
                )
                // Kortet visar den största koden
                val best = found.maxBy { (_, r) -> r?.let { it.width() * it.height() } ?: 0 }.first
                if (best.raw == dismissedRaw && now - dismissedSeenMs < 1500) {
                    dismissedSeenMs = now
                    return@addOnSuccessListener
                }
                if (best.raw != _state.value.scanCode?.raw) {
                    dismissedRaw = null
                    Log.i(TAG, "Skanna: ${best.format} ${best.kind} (${best.raw.length} tecken)")
                    _state.update { it.copy(scanCode = best) }
                }
            }
            .addOnFailureListener(scanExecutor) { e -> Log.w(TAG, "Skanna: avläsningen misslyckades", e) }
            .addOnCompleteListener(scanExecutor) { image.close() }
    }

    /** Dokument i sökaren: papperets hörn ungefär tio gånger per sekund (litet rutnät av Y-planet). */
    private fun analyzeDocument(image: ImageProxy) {
        try {
            val now = SystemClock.uptimeMillis()
            if (now - lastDocAnalysisMs < 100) return
            lastDocAnalysisMs = now
            val plane = image.planes[0]
            val w = image.width
            val h = image.height
            val stride = plane.rowStride
            val buf = plane.buffer
            buf.rewind()
            val n = minOf(buf.remaining(), stride * h)
            if (scanY.size < stride * h) scanY = ByteArray(stride * h)
            buf.get(scanY, 0, n)
            val factor = maxOf(1, w / DocumentMath.GRID)
            val grid = DocumentMath.downscale(scanY, stride, w, h, factor)
            val rotation = image.imageInfo.rotationDegrees
            val q = DocumentMath.detect(grid, w / factor, h / factor)?.let { DocumentMath.rotate(it, rotation) }
            if (q != null) {
                liveQuad = DocumentMath.smooth(liveQuad, q)
                quadMisses = 0
            } else if (++quadMisses > 3) {
                liveQuad = null
            }
            val uw = if (rotation % 180 == 0) w else h
            val uh = if (rotation % 180 == 0) h else w
            _scanFrame.value = ScanFrame(uw, uh, quad = liveQuad)
            if (!scanLogged) {
                scanLogged = true
                Log.i(TAG, "Skanna: dokument ${w}x$h, papper ${if (q != null) "hittat" else "inte hittat"}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Skanna: dokumentbilden kunde inte läsas", t)
        } finally {
            image.close()
        }
    }

    /** Tar en dokumentsida och öppnar redigeraren med papperets hörn. */
    private fun takeDocumentPhoto() {
        val st = _state.value
        val ic = imageCapture ?: return
        if (st.isCapturing || st.docBusy || _docEdit.value != null) return
        _state.update { it.copy(isCapturing = true, shutterBlink = System.currentTimeMillis()) }
        if (settings.value.shutterSound) sound.play(MediaActionSound.SHUTTER_CLICK)
        ic.takePicture(Dispatchers.Default.asExecutor(), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val raw = image.toBitmap()
                    val m = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }
                    // Högst 3000 bildpunkter på längden räcker för en A4-sida i 200 dpi
                    val long = maxOf(raw.width, raw.height)
                    if (long > 3000) (3000f / long).let { k -> m.postScale(k, k) }
                    val upright = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
                    if (upright !== raw) raw.recycle()
                    val quad = DocumentPdf.detectInBitmap(upright)
                    Log.i(TAG, "Dokument: bild ${upright.width}x${upright.height}, hörn ${quad.joinToString { "%.2f".format(Locale.US, it) }}")
                    _docEdit.value = DocEdit(upright, quad)
                } catch (t: Throwable) {
                    Log.w(TAG, "Dokument: bilden kunde inte behandlas", t)
                    showMessage(R.string.msg_doc_failed)
                } finally {
                    image.close()
                    _state.update { it.copy(isCapturing = false) }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                Log.w(TAG, "Dokument: bilden kunde inte tas", exception)
                showMessage(R.string.msg_capture_failed)
                _state.update { it.copy(isCapturing = false) }
            }
        })
    }

    fun retakeDocument() {
        _docEdit.value = null
    }

    /** Rätar och förbättrar sidan i bakgrunden och lägger den sist bland sidorna. */
    fun addDocumentPage(quad: FloatArray, filter: DocFilter) {
        val edit = _docEdit.value ?: return
        _docEdit.value = null
        _state.update { it.copy(docBusy = true, lastPdf = null) }
        viewModelScope.launch {
            val n = docPageFiles.size + 1
            val file = File(DocumentPdf.pagesDir(context), String.format(Locale.US, "page_%03d_%d.jpg", n, System.currentTimeMillis()))
            val size = withContext(Dispatchers.Default) {
                runCatching { DocumentPdf.makePage(edit.bitmap, quad, filter, file) }
                    .onFailure { Log.w(TAG, "Dokument: sidan kunde inte göras", it) }
                    .getOrNull()
            }
            if (size != null) {
                docPageFiles += file
                Log.i(TAG, "Dokument: sida $n ${size.first}x${size.second} ($filter)")
            } else {
                showMessage(R.string.msg_doc_failed)
            }
            _state.update { it.copy(docPages = docPageFiles.size, docBusy = false) }
        }
    }

    /** Sätter ihop sidorna till en PDF i Dokument/SNC Camera. */
    fun saveDocumentPdf() {
        val st = _state.value
        if (docPageFiles.isEmpty() || st.docBusy) return
        _state.update { it.copy(docBusy = true) }
        val pages = docPageFiles.toList()
        viewModelScope.launch {
            val uri = withContext(Dispatchers.IO) {
                runCatching { DocumentPdf.savePdf(context, pages) }
                    .onFailure { Log.w(TAG, "Dokument: PDF:en kunde inte sparas", it) }
                    .getOrNull()
            }
            if (uri != null) {
                pages.forEach { runCatching { it.delete() } }
                docPageFiles.clear()
                Log.i(TAG, "Dokument: PDF sparad (${pages.size} sidor)")
                _state.update { it.copy(docPages = 0, docBusy = false, lastPdf = uri, lastPdfPages = pages.size) }
            } else {
                showMessage(R.string.msg_pdf_failed)
                _state.update { it.copy(docBusy = false) }
            }
        }
    }

    fun discardDocumentPages() {
        docPageFiles.forEach { runCatching { it.delete() } }
        docPageFiles.clear()
        _state.update { it.copy(docPages = 0) }
    }

    fun updatePro(transform: (ProState) -> ProState) {
        _state.update { it.copy(pro = transform(it.pro)) }
        applyProOptions()
    }

    fun selectProControl(control: ProControl?) {
        _state.update {
            it.copy(activeProControl = if (it.activeProControl == control) null else control, filterPanelOpen = false)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Live-filter
    // ---------------------------------------------------------------------------------------------

    fun toggleFilterPanel() {
        val st = _state.value
        if (!st.filterPanelOpen && st.mode.isVideo && st.videoStyle == VideoStyle.SLOW_MOTION) {
            showMessage(R.string.msg_slowmo_no_filter)
        }
        _state.update { it.copy(filterPanelOpen = !it.filterPanelOpen, activeProControl = null) }
    }

    fun closeFilterPanel() {
        if (_state.value.filterPanelOpen) _state.update { it.copy(filterPanelOpen = false) }
    }

    fun setFilterPreset(preset: PhotoFilter) {
        _state.update { it.copy(filter = it.filter.copy(preset = preset)) }
        onFilterChanged()
    }

    fun setFilterChannel(channel: ColorChannel, value: Float) {
        _state.update { it.copy(filter = it.filter.with(channel, value)) }
        onFilterChanged()
    }

    fun resetFilter() {
        _state.update { it.copy(filter = LiveFilter()) }
        onFilterChanged()
    }

    /**
     * Video: är filtret redan bundet ändras färgerna direkt (även under inspelning). Slås ett filter på
     * när det inte är bundet binds kameran om – under en inspelning först när den är klar.
     */
    private fun onFilterChanged() {
        val st = _state.value
        // Dubbelvideo: filtret i filmen ändras direkt (sökarna har det via RenderEffect)
        if (st.mode == CameraMode.DUAL) dualRenderer?.setMatrix(if (st.filter.isActive) st.filter.matrix() else null)
        if (!st.mode.isVideo) return
        // Tidsförlopp: effekten på videon är alltid bunden – bara färgerna ändras (sökaren via RenderEffect)
        if (st.videoStyle == VideoStyle.TIMELAPSE) {
            if (timelapseBound) timelapseEffect?.setMatrix(if (st.filter.isActive) st.filter.matrix() else null)
            return
        }
        // Slow motion spelas in utan live-filter
        if (st.videoStyle == VideoStyle.SLOW_MOTION) return
        if (videoFilterBound) {
            videoFilter?.setMatrix(if (st.filter.isActive) st.filter.matrix() else null)
        } else if (st.filter.isActive && videoFilterAllowed) {
            if (st.isRecording) rebindAfterRecording = true else rebind()
        }
    }

    /** Sökarens form från skärmen (lång sida / kort sida), för helskärmsbilder. */
    fun setViewfinderSize(width: Int, height: Int) {
        if (width > 0 && height > 0) viewfinderLongShort = maxOf(width, height).toFloat() / minOf(width, height)
    }

    /**
     * Gör färdigt en tagen JPEG i ett steg: beskär till 3:2, 16:9 eller helskärm, lägger på live-filtret och
     * sparar i valt filformat (HEIF eller PNG). Bilden avkodas och sparas bara en gång, i full upplösning,
     * och EXIF-uppgifterna följer med. Ger adressen till den färdiga bilden.
     */
    private suspend fun finishPhoto(
        uri: Uri,
        aspect: AspectOption,
        filter: LiveFilter,
        format: PhotoFormat,
        quality: Int,
        watermark: AppSettings? = null
    ): Uri =
        withContext(Dispatchers.Default) {
            val convert = format == PhotoFormat.HEIF || format == PhotoFormat.PNG
            val crop = aspect.croppedAfter
            val stamp = watermark?.watermark == true
            if (!convert && !crop && !filter.isActive && !stamp) return@withContext uri
            val media = sncApp.mediaRepository
            if (convert) showMessage(R.string.msg_saving_format)
            val exif = media.readExif(uri)
            val source = media.loadBitmap(uri, maxDimension = 8192) ?: run {
                showMessage(R.string.msg_filter_failed)
                return@withContext uri
            }
            var bmp = source
            if (crop) {
                val ratio = when (aspect) {
                    AspectOption.RATIO_3_2 -> 1.5f
                    AspectOption.RATIO_16_9 -> 16f / 9f
                    else -> viewfinderLongShort
                }
                bmp = bmp.centerCropToRatio(ratio).also { if (it !== source) source.recycle() }
            }
            if (filter.isActive) {
                val filtered = bmp.withColorMatrix(filter.matrix())
                bmp.recycle()
                bmp = filtered
            }
            // Vattenstämpeln sist, så att den varken beskärs eller färgas av filtret
            val spec = watermark?.let { Watermark.specFor(it, exif) }
            if (spec != null) {
                val stamped = runCatching { Watermark.apply(context, bmp, spec) }.getOrElse {
                    Log.w(TAG, "Vattenstämpeln kunde inte ritas: $it", it)
                    bmp
                }
                if (stamped !== bmp) bmp.recycle()
                bmp = stamped
            }
            val result = if (convert) {
                media.replaceWithFormat(uri, bmp, format, quality, exif) ?: run {
                    // Gick inte att spara i formatet: behåll en JPEG med samma innehåll
                    showMessage(R.string.msg_format_failed)
                    media.overwriteJpeg(uri, bmp, quality, exif)
                    uri
                }
            } else {
                if (!media.overwriteJpeg(uri, bmp, quality, exif)) showMessage(R.string.msg_filter_failed)
                uri
            }
            bmp.recycle()
            result
        }

    // ---------------------------------------------------------------------------------------------
    // Panorama
    // ---------------------------------------------------------------------------------------------

    /** Låser exponering och vitbalans under svepet, så att bilderna blir lika ljusa. */
    private fun lockExposure(lock: Boolean) {
        val cam = camera ?: return
        runCatching {
            val b = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, lock)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, lock)
            Camera2CameraControl.from(cam.cameraControl).addCaptureRequestOptions(b.build())
        }
    }

    private fun startPanorama() {
        val st = _state.value
        if (!st.isReady || panoAnalysis == null || panoFocal <= 0f) {
            showMessage(R.string.msg_pano_failed)
            return
        }
        if (!panoMotion.available) {
            showMessage(R.string.msg_pano_no_gyro)
            return
        }
        panoSession?.release()
        val session = PanoramaSession(
            motion = panoMotion,
            focalLong = panoFocal,
            onStatus = { status -> _state.update { it.copy(panoStatus = status) } },
            onPreview = { bmp -> _panoPreview.value = bmp },
            onFull = { viewModelScope.launch { if (_state.value.panoActive) stopPanorama() } }
        )
        panoSession = session
        lockExposure(true)
        panoMotion.start()
        _panoPreview.value = null
        _state.update { it.copy(panoActive = true, panoStatus = PanoramaStatus()) }
        session.start()
        if (settings.value.shutterSound) sound.play(MediaActionSound.START_VIDEO_RECORDING)
        Log.i(TAG, "Panorama: svepet börjar")
    }

    private fun stopPanorama() {
        val session = panoSession ?: return
        if (!_state.value.panoActive) return
        panoMotion.stop()
        lockExposure(false)
        val st = _state.value
        val s = settings.value
        if (s.shutterSound) sound.play(MediaActionSound.STOP_VIDEO_RECORDING)
        _state.update { it.copy(panoActive = false, panoMerging = true, isCapturing = true) }
        session.finish { result, frames, degrees ->
            viewModelScope.launch {
                try {
                    if (result == null) {
                        showMessage(R.string.msg_pano_short)
                        return@launch
                    }
                    val uri = withContext(Dispatchers.Default) {
                        val (px, w, h) = result
                        var bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
                        if (st.filter.isActive) {
                            val filtered = bmp.withColorMatrix(st.filter.matrix())
                            bmp.recycle()
                            bmp = filtered
                        }
                        val exif = baseExif("SNC Camera Panorama: $frames frames, $degrees°", 0L, 0)
                        Watermark.specFor(s, exif)?.let { spec ->
                            val stamped = runCatching { Watermark.apply(context, bmp, spec) }.getOrDefault(bmp)
                            if (stamped !== bmp) bmp.recycle()
                            bmp = stamped
                        }
                        val format = if (s.photoFormat.isRaw) PhotoFormat.JPEG else s.photoFormat
                        val saved = media.saveNewPhoto(bmp, format, s.jpegQuality, exif, prefix = "PANO", location = currentLocation(s))
                        Log.i(TAG, "Panorama: sparat ${bmp.width}x${bmp.height} (${if (saved != null) "ja" else "nej"})")
                        bmp.recycle()
                        saved
                    }
                    if (uri != null) {
                        _state.update { it.copy(shutterBlink = System.currentTimeMillis()) }
                        onMediaSaved(uri, isVideo = false)
                    } else {
                        showMessage(R.string.msg_pano_failed)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.e(TAG, "Panorama: kunde inte sparas: $t", t)
                    showMessage(R.string.msg_pano_failed)
                } finally {
                    _state.update { it.copy(panoMerging = false, isCapturing = false, panoStatus = PanoramaStatus()) }
                    _panoPreview.value = null
                    session.release()
                    if (panoSession === session) panoSession = null
                }
            }
        }
    }

    /** Avbryter ett pågående panorama utan att spara (kameran binds om eller skärmen stängs). */
    private fun cancelPanorama() {
        val session = panoSession ?: return
        if (!_state.value.panoActive && !_state.value.panoMerging) return
        if (_state.value.panoMerging) return // sparas klart av sig självt
        panoMotion.stop()
        session.cancel()
        session.release()
        panoSession = null
        _panoPreview.value = null
        _state.update { it.copy(panoActive = false, panoStatus = PanoramaStatus()) }
        Log.i(TAG, "Panorama: avbrutet")
    }

    // ---------------------------------------------------------------------------------------------
    // Plats i foton och filmer
    // ---------------------------------------------------------------------------------------------

    /** Lyssnar på platsen när inställningen är på och kameraskärmen är öppen, annars inte. */
    private fun updateLocationTracking(enabled: Boolean = settings.value.saveLocation) {
        if (enabled && lifecycleOwner != null) locationTracker.start() else locationTracker.stop()
    }

    /** Platsen till fotot eller filmen (null när inställningen är av eller ingen färsk position finns). */
    private fun currentLocation(s: AppSettings): Location? {
        if (!s.saveLocation) return null
        val loc = locationTracker.fresh()
        if (loc == null) Log.i(TAG, "Plats: ingen färsk position – sparas utan plats")
        else Log.i(TAG, String.format(Locale.US, "Plats: %.5f, %.5f (±%.0f m, %s)", loc.latitude, loc.longitude, loc.accuracy, loc.provider))
        return loc
    }

    // ---------------------------------------------------------------------------------------------
    // Användaråtgärder
    // ---------------------------------------------------------------------------------------------

    /** Används av t.ex. VIDEO_CAMERA-intent; fungerar även innan kameran startat. */
    fun requestMode(mode: CameraMode) {
        // Snabbinställningsrutan öppnar läget Skanna för QR-koder
        if (mode == CameraMode.SCAN) _state.update { it.copy(scanKind = ScanKind.CODE) }
        if (!initialized) pendingMode = mode else setMode(mode)
    }

    fun setMode(mode: CameraMode) {
        val st = _state.value
        if (st.isRecording || st.countdown > 0 || mode == st.mode) return
        if (st.panoActive || st.panoMerging) return // ett panorama tas eller sys ihop
        if (st.isCapturing && stackSession != null) return // natt- eller HDR-bilden tas fortfarande
        if (burstJob?.isActive == true) return // serietagning pågår
        _state.update { it.copy(mode = mode, activeProControl = null) }
        viewModelScope.launch { settingsRepo.update { it.copy(lastMode = mode.name) } }
        rebind()
    }

    fun switchLens() {
        val st = _state.value
        if (st.isRecording || st.countdown > 0) return
        if (st.mode == CameraMode.PANORAMA) return
        if (st.isCapturing && stackSession != null) return
        if (burstJob?.isActive == true) return
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
        if (stackSession == null) nightAnalysis?.targetRotation = surfaceRotation
        if (!_state.value.isRecording) videoCapture?.targetRotation = surfaceRotation
    }

    /** Avtryckaren: foto, start/stopp av video eller avbryt nedräkning. */
    fun onShutter() {
        val st = _state.value
        if (st.mode == CameraMode.DUAL && st.dualAvailable == false) {
            showMessage(R.string.msg_dual_unsupported)
            return
        }
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
        if (st.mode == CameraMode.DUAL && st.dualVideo) {
            if (st.isRecording) stopDualRecording() else runWithTimer { startDualRecording() }
            return
        }
        if (st.isCapturing) return
        if (st.mode == CameraMode.PANORAMA) {
            if (st.panoActive) stopPanorama() else startPanorama()
            return
        }
        if (st.mode == CameraMode.DUAL) {
            runWithTimer { takeDualShot() }
            return
        }
        if (st.mode == CameraMode.SCAN) {
            // QR-koder läses av sig själva; avtryckaren tar en dokumentsida
            if (st.scanKind == ScanKind.DOCUMENT) takeDocumentPhoto()
            return
        }
        if (st.mode == CameraMode.NIGHT && nightAnalysis != null) {
            runWithTimer { takeStackShot() }
        } else if (st.mode == CameraMode.PHOTO && nightAnalysis != null) {
            runWithTimer { takeHdrShot() }
        } else {
            runWithTimer { takePhoto() }
        }
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
            val raw = rawFormat
            val baseName = MediaRepository.newFileName("IMG", "x").substringBeforeLast('.')
            val metadata = ImageCapture.Metadata().apply {
                isReversedHorizontal = st.lensFront && s.mirrorFront
                location = currentLocation(s)
            }
            fun outputOptions(extension: String, mime: String) = ImageCapture.OutputFileOptions.Builder(
                context.contentResolver,
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "$baseName.$extension")
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, MediaRepository.RELATIVE_PATH)
                }
            ).setMetadata(metadata).build()
            val options = if (raw == PhotoFormat.RAW) outputOptions("dng", "image/x-adobe-dng") else outputOptions("jpg", "image/jpeg")
            val rawOptions = if (raw == PhotoFormat.RAW_JPEG) outputOptions("dng", "image/x-adobe-dng") else null
            // RAW + JPEG ger två filer – vänta på båda innan kameran är redo igen
            var remaining = if (rawOptions != null) 2 else 1
            var jpegUri: Uri? = null
            var dngUri: Uri? = null

            if (s.shutterSound) sound.play(MediaActionSound.SHUTTER_CLICK)
            _state.update { it.copy(shutterBlink = System.currentTimeMillis()) }

            val callback = object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    _state.update { it.copy(screenFlash = false) }
                    val saved = output.savedUri
                    val isDng = saved != null && runCatching { context.contentResolver.getType(saved) }.getOrNull()
                        ?.contains("dng") == true || (raw == PhotoFormat.RAW && saved != null)
                    if (isDng) dngUri = saved else jpegUri = saved
                    remaining--
                    if (remaining > 0) return
                    val uri = jpegUri
                    if (uri == null) {
                        // Bara RAW (DNG): ingen efterbehandling
                        _state.update { it.copy(isCapturing = false) }
                        dngUri?.let { onMediaSaved(it, isVideo = false) }
                        return
                    }
                    viewModelScope.launch {
                        if (st.mode == CameraMode.PORTRAIT && !st.extensionActive) {
                            showMessage(R.string.msg_processing_portrait)
                            portrait.process(uri, s.jpegQuality)
                        }
                        // Beskärning (3:2, 16:9, helskärm), live-filter och filformat i ett steg
                        val format = if (raw != null) PhotoFormat.JPEG else s.photoFormat
                        val final = runCatching {
                            finishPhoto(uri, if (raw != null) AspectOption.RATIO_4_3 else s.aspect, st.filter, format, s.jpegQuality, watermark = s)
                        }.getOrDefault(uri)
                        _state.update { it.copy(isCapturing = false) }
                        onMediaSaved(final, isVideo = false)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Fotografering misslyckades", exception)
                    _state.update { it.copy(isCapturing = false, screenFlash = false) }
                    showMessage(R.string.msg_capture_failed)
                }
            }
            if (rawOptions != null) {
                ic.takePicture(rawOptions, options, ContextCompat.getMainExecutor(context), callback)
            } else {
                ic.takePicture(options, ContextCompat.getMainExecutor(context), callback)
            }
        }
    }

    /**
     * Serietagning: så länge avtryckaren hålls in tas bilder i följd (högst [BURST_MAX]), två åt gången
     * så att kameran aldrig väntar på att en fil skrivs. Blixten är av under serien. Beskärning, live-filter
     * och filformat görs en bild i taget i bakgrunden, så att serien inte bromsas.
     */
    fun startBurst() {
        val st = _state.value
        if (!st.isReady || !st.burstAvailable || st.isCapturing || st.countdown > 0 || st.isRecording) return
        val ic = imageCapture ?: return
        if (rawFormat != null) {
            showMessage(R.string.msg_burst_raw)
            return
        }
        val s = settings.value
        burstHeld = true
        burstJob?.cancel()
        burstJob = viewModelScope.launch {
            _state.update { it.copy(isCapturing = true, burstCount = 0) }
            runCatching { ic.flashMode = ImageCapture.FLASH_MODE_OFF }
            val group = MediaRepository.newFileName("IMG", "x").substringBeforeLast('.')
            val metadata = ImageCapture.Metadata().apply {
                isReversedHorizontal = st.lensFront && s.mirrorFront
                location = currentLocation(s)
            }
            val finished = Channel<Uri?>(Channel.UNLIMITED)
            val post = Channel<Uri>(Channel.UNLIMITED)
            val needsPost = s.aspect.croppedAfter || st.filter.isActive || s.watermark ||
                s.photoFormat == PhotoFormat.HEIF || s.photoFormat == PhotoFormat.PNG
            viewModelScope.launch {
                for (uri in post) {
                    val final = if (needsPost) {
                        runCatching { finishPhoto(uri, s.aspect, st.filter, s.photoFormat, s.jpegQuality, watermark = s) }.getOrDefault(uri)
                    } else uri
                    onMediaSaved(final, isVideo = false)
                }
            }
            var issued = 0
            var inFlight = 0
            var saved = 0
            var errors = 0
            fun issue() {
                issued++
                inFlight++
                val n = issued
                val name = String.format(Locale.US, "%s_BURST%03d.jpg", group, n)
                val options = ImageCapture.OutputFileOptions.Builder(
                    context.contentResolver,
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, MediaRepository.RELATIVE_PATH)
                    }
                ).setMetadata(metadata).build()
                if (s.shutterSound) sound.play(MediaActionSound.SHUTTER_CLICK)
                ic.takePicture(options, ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        finished.trySend(output.savedUri)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.w(TAG, "Serie: bild $n misslyckades", exception)
                        finished.trySend(null)
                    }
                })
            }
            fun fill() {
                while (burstHeld && issued < BURST_MAX && inFlight < BURST_IN_FLIGHT && errors < 3) issue()
            }
            try {
                issue() // första bilden tas alltid, även om fingret redan har släppt
                fill()
                while (inFlight > 0) {
                    val uri = finished.receive()
                    inFlight--
                    if (uri != null) {
                        saved++
                        _state.update { it.copy(burstCount = saved) }
                        post.trySend(uri)
                    } else {
                        errors++
                    }
                    fill()
                }
            } finally {
                post.close()
                burstHeld = false
                if (imageCapture === ic) applyFlashMode()
                _state.update { it.copy(isCapturing = false, burstCount = 0) }
            }
            Log.i(TAG, "Serie: $saved bilder sparade ($issued tagna, $errors fel)")
            if (saved > 0) {
                _state.update { it.copy(shutterBlink = System.currentTimeMillis()) }
                showMessage(R.string.msg_burst_saved, saved.toString())
            } else {
                showMessage(R.string.msg_capture_failed)
            }
        }
    }

    /** Avtryckaren släpptes: serien slutar när bilderna som är på väg har sparats. */
    fun stopBurst() {
        burstHeld = false
    }

    /**
     * Nattfoto, ljusspår eller silkesvatten: analysen slås på, bildrutorna läggs ihop medan de kommer
     * (NightStack.kt) och resultatet sparas i valt bildformat och filformat.
     */
    private fun takeStackShot() {
        if (nightAnalysis == null) return
        val st = _state.value
        val s = settings.value
        if (st.isCapturing) return
        val style = st.nightStyle
        val frames = NightFrames.forSteady(st.steady)
        val seconds = st.exposureSeconds
        viewModelScope.launch {
            _state.update { it.copy(isCapturing = true, stackProgress = 0f, stackMerging = false, stackFrames = frames) }
            if (s.shutterSound) sound.play(MediaActionSound.SHUTTER_CLICK)
            val session = StackSession(style, frames, seconds * 1_000_000_000L, stackPool, stackParts) { p ->
                _state.update { it.copy(stackProgress = p) }
            }
            var saved: Uri? = null
            try {
                stackSession = session
                val timeoutMs = if (style == StackStyle.NIGHT) 5_000L + frames * 1_200L else seconds * 1_000L + 6_000L
                val result = withTimeoutOrNull(timeoutMs) { session.result.await() } ?: run {
                    // Kom inte alla bilder i tid: lägg ihop de som kom (utanför huvudtråden)
                    withContext(Dispatchers.Default) { session.finishNow() }
                    withTimeoutOrNull(5_000L) { session.result.await() }
                }
                if (stackSession === session) stackSession = null
                Log.i(TAG, "Natt: $style klar med ${result?.frames ?: 0} bilder (${session.seen} bildrutor kom)")
                if (result != null) {
                    _state.update { it.copy(stackProgress = null, stackMerging = true) }
                    saved = withContext(Dispatchers.Default) { saveStack(result, style, st, s) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "Natt: bilden kunde inte tas ($style, ${session.seen} bildrutor kom): $t", t)
            } finally {
                if (stackSession === session) stackSession = null
                _state.update { it.copy(isCapturing = false, stackProgress = null, stackMerging = false) }
                applyNightOptions()
            }
            val uri = saved
            if (uri != null) {
                _state.update { it.copy(shutterBlink = System.currentTimeMillis()) }
                onMediaSaved(uri, isVideo = false)
            } else {
                showMessage(R.string.msg_stack_failed)
            }
        }
    }

    /** Bildformatet som förhållandet lång/kort sida (null = hela 4:3-bilden). */
    private fun cropRatioFor(aspect: AspectOption): Float? = when (aspect) {
        AspectOption.RATIO_4_3 -> null
        AspectOption.RATIO_1_1 -> 1f
        AspectOption.RATIO_3_2 -> 1.5f
        AspectOption.RATIO_16_9 -> 16f / 9f
        AspectOption.FULL -> viewfinderLongShort
    }

    /** Gör bild av de ihoplagda bildrutorna: beskärning, rotation, live-filter, EXIF och filformat. */
    private suspend fun saveStack(r: StackSession.Result, style: StackStyle, st: CameraUiState, s: AppSettings): Uri? {
        val description = when (style) {
            StackStyle.NIGHT -> "SNC Camera Night: ${r.frames} frames"
            StackStyle.TRAILS -> "SNC Camera Light trails: ${st.exposureSeconds} s, ${r.frames} frames"
            StackStyle.SILK -> "SNC Camera Silky water: ${st.exposureSeconds} s, ${r.frames} frames"
        }
        val exposureNs = if (style == StackStyle.NIGHT) lastExposureNs else st.exposureSeconds * 1_000_000_000L
        return saveProcessed(r.frame, r.rotationDegrees, null, baseExif(description, exposureNs, lastIso), st, s)
    }

    /**
     * Sparar en bild som appen har räknat fram (natt, ljusspår, silkesvatten, HDR). [gain] = gain map för
     * Ultra HDR (ett värde per 2 × 2-block), läggs bara till i JPEG.
     */
    private suspend fun saveProcessed(
        frame: YuvFrame,
        rotation: Int,
        gain: ByteArray?,
        exif: Map<String, String>,
        st: CameraUiState,
        s: AppSettings
    ): Uri? {
        val ratio = cropRatioFor(s.aspect)
        val mirror = st.lensFront && s.mirrorFront
        var bmp = StackRender.toBitmap(frame, ratio, rotation, mirror, stackPool, stackParts)
        var gainBmp: Bitmap? = null
        try {
            if (st.filter.isActive) {
                val filtered = bmp.withColorMatrix(st.filter.matrix())
                bmp.recycle()
                bmp = filtered
            }
            // Vattenstämpeln före gain map (Ultra HDR), så att den följer med i bilden
            Watermark.specFor(s, exif)?.let { spec ->
                val stamped = runCatching { Watermark.apply(context, bmp, spec) }.getOrDefault(bmp)
                if (stamped !== bmp) bmp.recycle()
                bmp = stamped
            }
            val format = if (s.photoFormat.isRaw) PhotoFormat.JPEG else s.photoFormat
            if (gain != null && format == PhotoFormat.JPEG && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                runCatching {
                    val crop = centerCropRect(frame.width, frame.height, ratio)
                    val g = StackRender.gainBitmap(gain, frame.width, crop, rotation, mirror)
                    gainBmp = g
                    UltraHdr.attach(bmp, g, HdrMerge.MAX_BOOST)
                    Log.i(TAG, "HDR: gain map ${g.width}x${g.height} på bild ${bmp.width}x${bmp.height}")
                }.onFailure { Log.w(TAG, "HDR: Ultra HDR kunde inte läggas till: $it", it) }
            }
            Log.i(TAG, "HDR/Natt: sparar ${bmp.width}x${bmp.height} som $format")
            val uri = media.saveNewPhoto(bmp, format, s.jpegQuality, exif, location = currentLocation(s))
            Log.i(TAG, "HDR/Natt: sparad ${uri != null}")
            return uri
        } finally {
            bmp.recycle()
            gainBmp?.recycle()
        }
    }

    /** EXIF för bilder som appen har räknat fram: datum, tidszon, kamera, beskrivning, exponering och ISO. */
    private fun baseExif(description: String, exposureNs: Long, iso: Int): Map<String, String> {
        val now = Date()
        val date = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(now)
        // Tidszon som "+01:00" (Androids SimpleDateFormat saknar mönstret "xxx")
        val offsetMin = java.util.TimeZone.getDefault().getOffset(now.time) / 60_000
        val absMin = kotlin.math.abs(offsetMin)
        val offset = String.format(Locale.US, "%s%02d:%02d", if (offsetMin < 0) "-" else "+", absMin / 60, absMin % 60)
        val exif = mutableMapOf(
            ExifInterface.TAG_DATETIME to date,
            ExifInterface.TAG_DATETIME_ORIGINAL to date,
            ExifInterface.TAG_DATETIME_DIGITIZED to date,
            ExifInterface.TAG_OFFSET_TIME to offset,
            ExifInterface.TAG_OFFSET_TIME_ORIGINAL to offset,
            ExifInterface.TAG_MAKE to Build.MANUFACTURER,
            ExifInterface.TAG_MODEL to Build.MODEL,
            ExifInterface.TAG_SOFTWARE to "SNC Camera ${BuildConfig.VERSION_NAME}",
            ExifInterface.TAG_IMAGE_DESCRIPTION to description
        )
        // ExposureTime skrivs som decimaltal (androidx ExifInterface räknar om det)
        if (exposureNs > 0) exif[ExifInterface.TAG_EXPOSURE_TIME] = String.format(Locale.US, "%.6f", exposureNs / 1e9)
        if (iso > 0) exif[ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY] = iso.toString()
        return exif
    }

    /**
     * HDR: 3 exponeringar i handen (0, −2, +2 EV) eller 5 på stativ (0, ±1,5, ±3 EV). Kameran ställer in
     * en exponering i taget och en bildruta sparas för varje; sedan läggs de ihop (HdrStack.kt) och
     * sparas, med gain map för Ultra HDR om det är påslaget och bilden sparas som JPEG.
     */
    private fun takeHdrShot() {
        if (nightAnalysis == null) return
        val cam = camera ?: return
        val st = _state.value
        val s = settings.value
        if (st.isCapturing) return
        val evs = if (st.steady) HDR_EVS_STEADY else HDR_EVS_HAND
        val step = st.exposureStep.takeIf { it > 0f } ?: (1f / 3f)
        val base = st.exposureIndex
        val indices = if (st.exposureSupported) {
            evs.map { (base + Math.round(it / step)).coerceIn(st.exposureMin, st.exposureMax) }.distinct()
        } else {
            listOf(base, base, base)
        }
        viewModelScope.launch {
            _state.update { it.copy(isCapturing = true, stackProgress = 0f, stackMerging = false, stackFrames = indices.size) }
            if (s.shutterSound) sound.play(MediaActionSound.SHUTTER_CLICK)
            val session = BracketSession(indices.size)
            var saved: Uri? = null
            try {
                stackSession = session
                var refExposureNs = 0L
                var refIso = 0
                for ((k, idx) in indices.withIndex()) {
                    if (st.exposureSupported && idx != (if (k == 0) base else indices[k - 1])) {
                        withTimeoutOrNull(1_500L) { runCatching { cam.cameraControl.setExposureCompensationIndex(idx).await() } }
                    }
                    // Efter en ny exposering kastas två bildrutor så att den sparade säkert har den nya
                    val got = withTimeoutOrNull(3_000L) { session.arm(k, if (k == 0) 0 else 2).await() }
                    if (got == null) break
                    if (k == 0) {
                        refExposureNs = lastExposureNs
                        refIso = lastIso
                    }
                    _state.update { it.copy(stackProgress = (k + 1f) / indices.size) }
                }
                session.done()
                if (stackSession === session) stackSession = null
                if (st.exposureSupported) runCatching { cam.cameraControl.setExposureCompensationIndex(base) }
                val taken = indices.indices.filter { session.frames[it] != null }
                Log.i(TAG, "HDR: ${taken.size} av ${indices.size} bilder (index $indices, ${session.seen} bildrutor kom)")
                if (taken.firstOrNull() == 0 && taken.size >= 2) {
                    _state.update { it.copy(stackProgress = null, stackMerging = true) }
                    val frames = taken.map { session.frames[it]!! }
                    val nominal = FloatArray(taken.size) { i -> 2f.pow((indices[taken[i]] - base) * step) }
                    val evText = taken.joinToString(", ") { i ->
                        val ev = (indices[i] - base) * step
                        if (ev == 0f) "0" else String.format(Locale.US, "%+.1f", ev).removeSuffix(".0")
                    }
                    val wantGain = s.ultraHdr && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                        (s.photoFormat == PhotoFormat.JPEG || s.photoFormat.isRaw)
                    saved = withContext(Dispatchers.Default) {
                        val res = HdrMerge.merge(stackPool, stackParts, frames, nominal, wantGain)
                        Log.i(TAG, "HDR: ljusskillnader ${res.ratios.joinToString { String.format(Locale.US, "%.3f", it) }}")
                        val description = "SNC Camera HDR: ${frames.size} exposures ($evText EV)" +
                            if (res.gain != null) ", Ultra HDR" else ""
                        saveProcessed(res.frame, session.rotationDegrees, res.gain, baseExif(description, refExposureNs, refIso), st, s)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "HDR: bilden kunde inte tas (${session.seen} bildrutor kom): $t", t)
            } finally {
                session.done()
                if (stackSession === session) stackSession = null
                if (st.exposureSupported && camera === cam) runCatching { cam.cameraControl.setExposureCompensationIndex(base) }
                _state.update { it.copy(isCapturing = false, stackProgress = null, stackMerging = false) }
            }
            val uri = saved
            if (uri != null) {
                _state.update { it.copy(shutterBlink = System.currentTimeMillis()) }
                onMediaSaved(uri, isVideo = false)
            } else {
                showMessage(R.string.msg_stack_failed)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        val st = _state.value
        val s = settings.value
        val style = st.videoStyle
        if (style == VideoStyle.SLOW_MOTION) {
            when (st.slowEngine) {
                SlowEngine.CAMERA2 -> {
                    // Camera2: SlowMotionCamera spelar in själv (utan ljud)
                    val opt = slowCamera2Options.firstOrNull { it.fps == st.slowFps } ?: return
                    if (s.shutterSound) sound.play(MediaActionSound.START_VIDEO_RECORDING)
                    val sensor = SlowMotionProbe.sensorOrientation(context, opt.cameraId)
                    val device = when (targetRotation) {
                        Surface.ROTATION_90 -> 90
                        Surface.ROTATION_180 -> 180
                        Surface.ROTATION_270 -> 270
                        else -> 0
                    }
                    slowController().startRecording((sensor - device + 360) % 360, currentLocation(s))
                    return
                }
                SlowEngine.NONE -> {
                    showMessage(R.string.msg_slowmo_unsupported)
                    return
                }
                SlowEngine.CAMERAX -> Unit
            }
        }
        if (style == VideoStyle.TIMELAPSE && !st.timelapseReady) {
            showMessage(R.string.msg_timelapse_failed)
            return
        }
        val vc = videoCapture ?: return
        val suffix = when (style) {
            VideoStyle.NORMAL -> ""
            VideoStyle.SLOW_MOTION -> "_SLOMO${st.slowFps}"
            VideoStyle.TIMELAPSE -> "_TIMELAPSE"
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, MediaRepository.newFileName("VID", "mp4").replace(".mp4", "$suffix.mp4"))
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, MediaRepository.RELATIVE_PATH)
        }
        val output = MediaStoreOutputOptions.Builder(
            context.contentResolver,
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        ).setContentValues(values).setLocation(currentLocation(s)).build()

        var pending = vc.output.prepareRecording(context, output)
        // Ljud bara i vanlig video (slow motion och tidsförlopp spelas upp i en annan takt)
        val audioAllowed = style == VideoStyle.NORMAL && s.recordAudio && ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (audioAllowed) pending = pending.withAudioEnabled()

        if (s.shutterSound) sound.play(MediaActionSound.START_VIDEO_RECORDING)
        recordingStyle = style
        recordingFps = st.slowFps
        if (style == VideoStyle.TIMELAPSE) {
            timelapseGate.intervalNs = st.timelapseIntervalMs * 1_000_000L
            timelapseGate.arm()
        }
        recording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    _state.update { it.copy(isRecording = true, isPaused = false, recordingMs = 0L, recordingOutputMs = 0L) }
                    if (recordingStyle != VideoStyle.NORMAL) startTicker()
                }
                is VideoRecordEvent.Pause -> _state.update { it.copy(isPaused = true) }
                is VideoRecordEvent.Resume -> _state.update { it.copy(isPaused = false) }
                is VideoRecordEvent.Status -> if (recordingStyle == VideoStyle.NORMAL) {
                    _state.update { it.copy(recordingMs = event.recordingStats.recordedDurationNanos / 1_000_000L) }
                }
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    timelapseGate.disarm()
                    stopTicker()
                    if (recordingStyle == VideoStyle.TIMELAPSE) {
                        Log.i(TAG, "Tidsförlopp: ${timelapseGate.kept} bilder (${timelapseGate.outputMs} ms film), fel ${event.error}")
                    }
                    _state.update { it.copy(isRecording = false, isPaused = false, recordingMs = 0L, recordingOutputMs = 0L) }
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
        val st = _state.value
        if (st.slowEngine == SlowEngine.CAMERA2 && st.isRecording) {
            if (settings.value.shutterSound) sound.play(MediaActionSound.STOP_VIDEO_RECORDING)
            slowCam?.stopRecording()
            return
        }
        val r = recording ?: return
        if (settings.value.shutterSound) sound.play(MediaActionSound.STOP_VIDEO_RECORDING)
        // Tidsförlopp: inga fler bildrutor till filmen från och med nu
        timelapseGate.disarm()
        r.stop()
    }

    fun togglePause() {
        if (recordingStyle != VideoStyle.NORMAL) return // paus bara i vanlig video
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

    fun showMessage(@StringRes res: Int, arg: String? = null) {
        _state.update { it.copy(message = res, messageArg = arg, messageId = System.nanoTime()) }
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

/** Ultra HDR (Android 14+): gain map som gör att HDR-skärmar visar ljusa partier upp till [maxBoost] × ljusare. */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
private object UltraHdr {
    fun attach(sdr: Bitmap, gain: Bitmap, maxBoost: Float) {
        val gm = Gainmap(gain)
        gm.setRatioMin(1f, 1f, 1f)
        gm.setRatioMax(maxBoost, maxBoost, maxBoost)
        gm.setGamma(1f, 1f, 1f)
        gm.setEpsilonSdr(0f, 0f, 0f)
        gm.setEpsilonHdr(0f, 0f, 0f)
        gm.setMinDisplayRatioForHdrTransition(1f)
        gm.setDisplayRatioForFullHdr(maxBoost)
        sdr.gainmap = gm
    }
}
