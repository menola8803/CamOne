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
    PORTRAIT(R.string.mode_portrait);

    val isVideo get() = this == VIDEO
}

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

enum class ProControl { EV, ISO, SHUTTER, WB, FOCUS }

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

    val isRecording: Boolean = false,
    val isPaused: Boolean = false,
    val recordingMs: Long = 0L,

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
    val messageId: Long = 0L
)
