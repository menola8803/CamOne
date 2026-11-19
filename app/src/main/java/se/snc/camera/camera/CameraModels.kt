package se.snc.camera.camera

import android.graphics.Bitmap
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import androidx.annotation.StringRes
import se.snc.camera.R

/** Lägen i samma ordning som karusellen i OnePlus kamera (vänster → höger). */
enum class CameraMode(@StringRes val label: Int) {
    PRO(R.string.mode_pro),
    NIGHT(R.string.mode_night),
    VIDEO(R.string.mode_video),
    PHOTO(R.string.mode_photo),
    PORTRAIT(R.string.mode_portrait),
    /** Fram- och bakkameran samtidigt, i var sin halva. */
    DUAL(R.string.mode_dual);

    val isVideo get() = this == VIDEO
}

/** Videoläget: vanlig video, slow motion eller tidsförlopp. */
enum class VideoStyle(@StringRes val label: Int) {
    NORMAL(R.string.video_style_normal),
    SLOW_MOTION(R.string.video_style_slowmo),
    TIMELAPSE(R.string.video_style_timelapse)
}

/** Hur slow motion spelas in: CameraX (höghastighetssession), egen Camera2-session, eller inte alls. */
enum class SlowEngine { NONE, CAMERAX, CAMERA2 }

enum class WhiteBalance(@StringRes val label: Int, val awbMode: Int) {
    AUTO(R.string.wb_auto, CaptureRequest.CONTROL_AWB_MODE_AUTO),
    DAYLIGHT(R.string.wb_daylight, CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT),
    CLOUDY(R.string.wb_cloudy, CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT),
    FLUORESCENT(R.string.wb_fluorescent, CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT),
    INCANDESCENT(R.string.wb_incandescent, CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT)
}

/** Manuella reglage i Pro-läget. */
data class ProState(
    val supported: Boolean = false,
    val isoAuto: Boolean = true,
    val iso: Int = 100,
    val isoMin: Int = 100,
    val isoMax: Int = 3200,
    val shutterAuto: Boolean = true,
    val shutterNs: Long = 16_666_667L,
    val shutterMinNs: Long = 100_000L,
    val shutterMaxNs: Long = 250_000_000L,
    val whiteBalance: WhiteBalance = WhiteBalance.AUTO,
    val focusAuto: Boolean = true,
    /** Fokusavstånd i dioptrier, 0 = oändligt. */
    val focusDiopters: Float = 0f,
    val focusMaxDiopters: Float = 10f
)

enum class ProControl { EV, ISO, SHUTTER, WB, FOCUS, ASSIST }

data class CameraUiState(
    val mode: CameraMode = CameraMode.PHOTO,
    val lensFront: Boolean = false,
    val hasFrontCamera: Boolean = true,
    val hasBackCamera: Boolean = true,
    val isReady: Boolean = false,

    /** Den fysiska lins som är aktiv just nu (logisk kamera som byter lins vid zoom). */
    val activeLens: LensBadge? = null,
    /** Ändras när linsen byts, så att etiketten visas en stund. */
    val lensChangeTick: Long = 0L,

    val zoomRatio: Float = 1f,
    val minZoom: Float = 1f,
    val maxZoom: Float = 1f,

    val hasFlashUnit: Boolean = false,
    val torchOn: Boolean = false,

    val nightAvailable: Boolean = false,
    val bokehAvailable: Boolean = false,
    val hdrAvailable: Boolean = false,
    /** True om läget körs med tillverkarens extension (natt/porträtt/HDR) – annars standardbild. */
    val extensionActive: Boolean = false,

    val exposureSupported: Boolean = false,
    val exposureIndex: Int = 0,
    val exposureMin: Int = 0,
    val exposureMax: Int = 0,
    val exposureStep: Float = 1f,

    val pro: ProState = ProState(),
    val activeProControl: ProControl? = null,

    /** Pro-hjälpmedlen (histogram, fokusmarkering, zebra) har en analysbild kopplad till sökaren. */
    val assistActive: Boolean = false,

    /** Live-filter i sökaren (foton och, med OpenGL, video). */
    val filter: LiveFilter = LiveFilter(),
    val filterPanelOpen: Boolean = false,

    val isRecording: Boolean = false,
    val isPaused: Boolean = false,
    val recordingMs: Long = 0L,

    /** Live-filtret är bundet i video (OpenGL) och syns i sökaren och filmen. */
    val videoFilterOn: Boolean = false,

    /** Video, slow motion eller tidsförlopp. */
    val videoStyle: VideoStyle = VideoStyle.NORMAL,
    /** Slow motion: bildfrekvenserna kameran klarar (tom = inte undersökt än eller stöds inte). */
    val slowFpsOptions: List<Int> = emptyList(),
    val slowFps: Int = 0,
    /** Slow motion har undersökts (då betyder tom [slowFpsOptions] att det inte stöds). */
    val slowProbed: Boolean = false,
    /** Slow motion är bunden just nu, och på vilket sätt. */
    val slowEngine: SlowEngine = SlowEngine.NONE,
    /** Upplösningen för vald bildfrekvens, t.ex. "1080p". */
    val slowSizeLabel: String? = null,
    /** Camera2-sökarens bildstorlek (för beskärningen i TextureView). */
    val slowWidth: Int = 0,
    val slowHeight: Int = 0,
    /** Tidsförlopp: tid mellan bilderna. */
    val timelapseIntervalMs: Int = 1000,
    /** Tidsförloppets OpenGL-grind är bunden (annars går det inte att spela in tidsförlopp). */
    val timelapseReady: Boolean = false,
    /** Tidsförlopp och slow motion: filmens längd i millisekunder medan den spelas in. */
    val recordingOutputMs: Long = 0L,
    /** Nattläget lägger ihop flera bilder (ingen tillverkarextension): Natt, Ljusspår eller Silkesvatten. */
    val stackAvailable: Boolean = false,
    val nightStyle: StackStyle = StackStyle.NIGHT,
    /** Exponeringstid för ljusspår och silkesvatten. */
    val exposureSeconds: Int = 2,
    /** Telefonen står stilla (stativ eller bord) enligt gyrot. */
    val steady: Boolean = false,
    /** Antal bilder i den pågående nattbilden. */
    val stackFrames: Int = 0,
    /** 0–1 medan bilderna tas, annars null. */
    val stackProgress: Float? = null,
    /** Bilderna läggs ihop och sparas. */
    val stackMerging: Boolean = false,

    /** Läget Dubbel: true = fram och bak samtidigt fungerar, false = stöds inte, null = inte bundet än. */
    val dualAvailable: Boolean? = null,
    /** Läget Dubbel: frontkameran ligger överst (annars bakkameran). */
    val dualSwapped: Boolean = false,

    /** Håll in avtryckaren för serietagning (Foto och Pro, inte med HDR-sammanslagning eller RAW). */
    val burstAvailable: Boolean = false,
    /** Antal bilder i den pågående serien (0 = ingen serie). */
    val burstCount: Int = 0,

    val countdown: Int = 0,
    val isCapturing: Boolean = false,
    /** Vit skärm som blixt för frontkameran. */
    val screenFlash: Boolean = false,
    /** Kort svart blink när en bild tas. */
    val shutterBlink: Long = 0L,

    val lastThumbnail: Bitmap? = null,
    val lastUri: Uri? = null,
    val lastIsVideo: Boolean = false,

    /** Kort meddelande (strängresurs) som visas som en "toast" i kameravyn. */
    @StringRes val message: Int? = null,
    /** Värde som sätts in i meddelandet (t.ex. antal bilder), annars null. */
    val messageArg: String? = null,
    val messageId: Long = 0L
)
