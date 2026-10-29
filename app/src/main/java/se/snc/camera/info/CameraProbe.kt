package se.snc.camera.info

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraExtensionCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import se.snc.camera.camera.LensSelection
import se.snc.camera.util.await
import java.util.Locale
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Typ av lins, räknad från brännvidden (35 mm-ekvivalent). */
enum class LensKind { ULTRA_WIDE, WIDE, TELE, FRONT, OTHER }

/** Allt appen kan läsa om en kamera (lins) via Camera2. */
data class LensInfo(
    val id: String,
    /** Logisk kamera som linsen tillhör (om den bara nås som fysisk kamera). */
    val parentId: String?,
    /** Finns i listan som appar får se. Dolda linser kan systemet ha reserverat för en annan app. */
    val listed: Boolean,
    val front: Boolean,
    val kind: LensKind,
    val focalMm: Float?,
    val eqMm: Int?,
    val fovDeg: Int?,
    val aperture: Float?,
    val megapixels: Float,
    /** Megapixlar i högupplöst läge (t.ex. 50 MP på sensorer som annars binnar till 12,5 MP). */
    val maxResMegapixels: Float?,
    val sensorInch: String?,
    val maxJpeg: Size?,
    val ois: Boolean,
    val eis: Boolean,
    val manualSensor: Boolean,
    val raw: Boolean,
    val logical: Boolean,
    val physicalIds: List<String>,
    val ultraHighRes: Boolean,
    val maxFps4k: Int?,
    val maxFps1080: Int?,
    /** Slow motion: (bredd×höjd, fps), högsta först. */
    val highSpeed: List<Pair<Size, Int>>,
    val zoomMin: Float?,
    val zoomMax: Float?,
    val flash: Boolean,
    val fixedFocus: Boolean,
    val minFocusCm: Int?,
    val maxExposureSec: Float?,
    val isoMin: Int?,
    val isoMax: Int?,
    val hardwareLevel: String,
    /** Tillverkarens tillägg via Camera2 (Android 12+): NIGHT, BOKEH, HDR, AUTO, FACE_RETOUCH. */
    val camera2Extensions: List<String>
)

/** Vad appen faktiskt kan använda via CameraX-tilläggen (tillverkarens nattläge, porträtt, HDR …). */
data class ExtensionSupport(val back: List<String>, val front: List<String>)

data class CameraReport(
    val lenses: List<LensInfo>,
    /** Par av kameror som kan vara igång samtidigt (fram + bak för dual-view). */
    val concurrent: List<Set<String>>,
    val extensions: ExtensionSupport,
    val device: String,
    val android: String,
    /** Bakkameran som appen använder (den logiska kameran med flest linser). */
    val appBackCameraId: String? = null
) {
    val appBackCamera: LensInfo? get() = lenses.firstOrNull { it.id == appBackCameraId }

    /** Linserna appen når genom zoom på sin bakkamera. */
    val appBackLenses: List<LensInfo>
        get() {
            val cam = appBackCamera ?: return emptyList()
            val members = cam.physicalIds.mapNotNull { pid -> lenses.firstOrNull { it.id == pid } }
            return members.ifEmpty { listOf(cam) }.sortedBy { it.eqMm ?: 999 }
        }

    val back get() = lenses.filter { !it.front }
    val backListed get() = back.filter { it.listed || it.parentId != null }

    private fun usable(l: LensInfo) = l.listed || (l.parentId != null && lenses.any { it.id == l.parentId && it.listed })

    /** Hur appen kommer åt en lins av en viss typ. */
    fun access(kind: LensKind): Access {
        if (appBackLenses.any { it.kind == kind }) return Access.ZOOM
        val logicalZoom = lenses.filter { !it.front && it.listed }
        when (kind) {
            LensKind.ULTRA_WIDE -> if (logicalZoom.any { (it.zoomMin ?: 1f) < 0.95f }) return Access.ZOOM
            LensKind.TELE -> if (logicalZoom.any { it.logical && it.physicalIds.isNotEmpty() && (it.zoomMax ?: 1f) >= 2f } &&
                back.any { it.kind == LensKind.TELE && it.parentId != null }
            ) return Access.ZOOM
            else -> Unit
        }
        val candidates = back.filter { it.kind == kind }
        return when {
            candidates.any { it.listed } -> Access.OWN_CAMERA
            candidates.any { usable(it) } -> Access.PHYSICAL
            candidates.isNotEmpty() -> Access.HIDDEN
            else -> Access.NONE
        }
    }

    val bestHighSpeed: Pair<Size, Int>? get() = lenses.filter { usable(it) }.flatMap { it.highSpeed }.maxByOrNull { it.second }
    val maxFps4k: Int? get() = back.filter { usable(it) }.mapNotNull { it.maxFps4k }.maxOrNull()
    val maxFps1080: Int? get() = back.filter { usable(it) }.mapNotNull { it.maxFps1080 }.maxOrNull()
    val maxResMp: Float? get() = back.filter { usable(it) }.mapNotNull { it.maxResMegapixels }.maxOrNull()
    val manual: Boolean get() = back.any { usable(it) && it.manualSensor }
    val raw: Boolean get() = back.any { usable(it) && it.raw }
    val dualView: Boolean
        get() = concurrent.any { set ->
            set.any { id -> lenses.any { it.id == id && it.front } } && set.any { id -> lenses.any { it.id == id && !it.front } }
        }
}

enum class Access { ZOOM, OWN_CAMERA, PHYSICAL, HIDDEN, NONE }

object CameraProbe {
    private const val TAG = "SncCameraInfo"

    suspend fun probe(context: Context): CameraReport = withContext(Dispatchers.IO) {
        val manager = context.getSystemService(CameraManager::class.java)
        val listed = runCatching { manager.cameraIdList.toList() }.getOrDefault(emptyList())
        val result = linkedMapOf<String, LensInfo>()

        fun add(id: String, parent: String?, isListed: Boolean) {
            if (result.containsKey(id)) return
            val ch = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: return
            result[id] = readLens(manager, id, ch, parent, isListed)
            // Fysiska linser bakom en logisk kamera (t.ex. vidvinkel + tele bakom huvudkameran)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ch.physicalCameraIds.forEach { pid -> add(pid, id, listed.contains(pid)) }
            }
        }
        listed.forEach { add(it, null, true) }
        // Linser som finns men inte visas för appar (LineageOS kan reservera dem för systemkameran)
        for (i in 0..15) {
            val id = i.toString()
            if (!result.containsKey(id)) add(id, null, false)
        }

        val concurrent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { manager.concurrentCameraIds.map { it.toSet() } }.getOrDefault(emptyList())
        } else emptyList()

        val ext = withContext(Dispatchers.Main) { cameraXExtensions(context) }

        CameraReport(
            lenses = result.values.sortedWith(compareBy<LensInfo>({ it.front }, { !it.listed }, { it.eqMm ?: 999 })),
            concurrent = concurrent,
            extensions = ext,
            device = "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})",
            android = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.DISPLAY}",
            appBackCameraId = LensSelection.preferredBackCameraId(context)
        )
    }

    private suspend fun cameraXExtensions(context: Context): ExtensionSupport = try {
        val provider = ProcessCameraProvider.getInstance(context).await()
        val ext = ExtensionsManager.getInstanceAsync(context, provider).await()
        val modes = listOf(
            ExtensionMode.NIGHT to "NIGHT",
            ExtensionMode.BOKEH to "BOKEH",
            ExtensionMode.HDR to "HDR",
            ExtensionMode.AUTO to "AUTO",
            ExtensionMode.FACE_RETOUCH to "FACE_RETOUCH"
        )
        fun list(sel: CameraSelector) = if (runCatching { provider.hasCamera(sel) }.getOrDefault(false)) {
            modes.filter { (m, _) -> runCatching { ext.isExtensionAvailable(sel, m) }.getOrDefault(false) }.map { it.second }
        } else emptyList()
        ExtensionSupport(list(CameraSelector.DEFAULT_BACK_CAMERA), list(CameraSelector.DEFAULT_FRONT_CAMERA))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Kunde inte läsa kameratillägg", e)
        ExtensionSupport(emptyList(), emptyList())
    }

    private fun readLens(
        manager: CameraManager,
        id: String,
        ch: CameraCharacteristics,
        parent: String?,
        listed: Boolean
    ): LensInfo {
        val front = ch.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT
        val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        val phys = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val diag = phys?.let { sqrt(it.width * it.width + it.height * it.height) }
        val eq = if (focal != null && diag != null && diag > 0f) (focal * 43.27f / diag).roundToInt() else null
        val fov = if (focal != null && diag != null && focal > 0f) {
            Math.toDegrees(2.0 * atan(diag / (2.0 * focal))).roundToInt()
        } else null
        val pixels = ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val mp = if (pixels != null) pixels.width.toFloat() * pixels.height / 1_000_000f else 0f
        val maxRes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION)
                ?.let { it.width.toFloat() * it.height / 1_000_000f }
                ?.takeIf { it > mp * 1.5f }
        } else null
        val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val ultraHigh = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR)
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val maxJpeg = map?.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width.toLong() * it.height }

        fun maxFpsFor(minWidth: Int, minHeight: Int): Int? {
            val m = map ?: return null
            val sizes = m.getOutputSizes(MediaRecorder::class.java) ?: return null
            return sizes.filter { max(it.width, it.height) >= minWidth && minOf(it.width, it.height) >= minHeight }
                .mapNotNull { s ->
                    val d = runCatching { m.getOutputMinFrameDuration(MediaRecorder::class.java, s) }.getOrDefault(0L)
                    if (d > 0) (1_000_000_000.0 / d).roundToInt() else null
                }
                .maxOrNull()
        }
        val aeMax = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.maxOfOrNull { it.upper } ?: 30
        val fps4k = maxFpsFor(3840, 2160)?.let { minOf(it, aeMax) }
        val fps1080 = maxFpsFor(1920, 1080)?.let { minOf(it, aeMax) }

        val hsMap = map
        val highSpeed = if (caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO) && hsMap != null) {
            runCatching {
                hsMap.highSpeedVideoSizes.map { s ->
                    s to (hsMap.getHighSpeedVideoFpsRangesFor(s).maxOfOrNull { it.upper } ?: 0)
                }.filter { it.second > 60 }
                    .sortedWith(compareByDescending<Pair<Size, Int>> { it.second }.thenByDescending { it.first.width })
                    .distinctBy { it.second to it.first.width }
            }.getOrDefault(emptyList())
        } else emptyList()

        val zoom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) else null
        val minFocus = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val exposure = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val iso = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val ois = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            ?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true
        val eis = ch.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
            ?.any { it != CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF } == true
        val level = when (ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
            else -> "?"
        }
        val camera2Ext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && listed) {
            runCatching {
                manager.getCameraExtensionCharacteristics(id).supportedExtensions.map {
                    when (it) {
                        CameraExtensionCharacteristics.EXTENSION_NIGHT -> "NIGHT"
                        CameraExtensionCharacteristics.EXTENSION_BOKEH -> "BOKEH"
                        CameraExtensionCharacteristics.EXTENSION_HDR -> "HDR"
                        CameraExtensionCharacteristics.EXTENSION_AUTOMATIC -> "AUTO"
                        else -> "FACE_RETOUCH"
                    }
                }
            }.getOrDefault(emptyList())
        } else emptyList()

        val kind = when {
            front -> LensKind.FRONT
            eq == null -> LensKind.OTHER
            eq <= 19 -> LensKind.ULTRA_WIDE
            eq >= 40 -> LensKind.TELE
            else -> LensKind.WIDE
        }
        val inch = diag?.takeIf { it > 0f }?.let { String.format(Locale.US, "1/%.2f\"", 16f / it).replace(".00", "") }

        return LensInfo(
            id = id,
            parentId = parent,
            listed = listed,
            front = front,
            kind = kind,
            focalMm = focal,
            eqMm = eq,
            fovDeg = fov,
            aperture = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull(),
            megapixels = mp,
            maxResMegapixels = maxRes,
            sensorInch = inch,
            maxJpeg = maxJpeg,
            ois = ois,
            eis = eis,
            manualSensor = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR),
            raw = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW),
            logical = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA),
            physicalIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) ch.physicalCameraIds.toList() else emptyList(),
            ultraHighRes = ultraHigh,
            maxFps4k = fps4k,
            maxFps1080 = fps1080,
            highSpeed = highSpeed,
            zoomMin = zoom?.lower,
            zoomMax = zoom?.upper,
            flash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
            fixedFocus = minFocus == 0f,
            minFocusCm = if (minFocus > 0f) (100f / minFocus).roundToInt() else null,
            maxExposureSec = exposure?.upper?.let { it / 1_000_000_000f },
            isoMin = iso?.lower,
            isoMax = iso?.upper,
            hardwareLevel = level,
            camera2Extensions = camera2Ext
        )
    }

    /** Textrapport att kopiera eller dela (t.ex. för att visa vad telefonen klarar). */
    fun report(r: CameraReport, appVersion: String): String = buildString {
        appendLine("SNC Camera $appVersion – kamerarapport")
        appendLine(r.device)
        appendLine(r.android)
        appendLine()
        appendLine("Appens bakkamera: ID ${r.appBackCameraId ?: "-"} ${r.appBackLenses.map { "${it.id}:${it.kind}" }}")
        appendLine("Ultravidvinkel: ${r.access(LensKind.ULTRA_WIDE)}")
        appendLine("Tele: ${r.access(LensKind.TELE)}")
        appendLine("Högupplöst läge: ${r.maxResMp?.let { String.format(Locale.US, "%.1f MP", it) } ?: "nej"}")
        appendLine("4K max fps: ${r.maxFps4k ?: "-"} · 1080p max fps: ${r.maxFps1080 ?: "-"}")
        appendLine("Slow motion: ${r.bestHighSpeed?.let { "${it.first.width}x${it.first.height} @ ${it.second}" } ?: "nej"}")
        appendLine("Fram + bak samtidigt: ${if (r.dualView) "ja" else "nej"} ${r.concurrent}")
        appendLine("Manuell sensor: ${r.manual} · RAW: ${r.raw}")
        appendLine("CameraX-tillägg bak: ${r.extensions.back.ifEmpty { listOf("-") }.joinToString()}")
        appendLine("CameraX-tillägg fram: ${r.extensions.front.ifEmpty { listOf("-") }.joinToString()}")
        appendLine()
        r.lenses.forEach { l ->
            append("ID ${l.id}")
            if (l.parentId != null) append(" (fysisk i ${l.parentId})")
            if (!l.listed) append(" [inte listad]")
            appendLine(" ${if (l.front) "fram" else "bak"} ${l.kind}")
            appendLine(
                "  ${l.eqMm ?: "?"} mm ekv · f/${l.aperture ?: "?"} · ${l.fovDeg ?: "?"}° · " +
                    String.format(Locale.US, "%.1f MP", l.megapixels) +
                    (l.maxResMegapixels?.let { String.format(Locale.US, " (max %.1f MP)", it) } ?: "") +
                    " · ${l.sensorInch ?: "?"}"
            )
            appendLine(
                "  JPEG ${l.maxJpeg?.let { "${it.width}x${it.height}" } ?: "?"} · 4K ${l.maxFps4k ?: "-"} fps · 1080p ${l.maxFps1080 ?: "-"} fps" +
                    " · slowmo ${l.highSpeed.take(3).joinToString { "${it.first.height}p${it.second}" }.ifEmpty { "-" }}"
            )
            appendLine(
                "  OIS ${l.ois} · EIS ${l.eis} · manuell ${l.manualSensor} · RAW ${l.raw} · logisk ${l.logical} ${l.physicalIds}" +
                    " · zoom ${l.zoomMin ?: "-"}–${l.zoomMax ?: "-"} · ${l.hardwareLevel}"
            )
            appendLine(
                "  blixt ${l.flash} · fokus ${if (l.fixedFocus) "fast" else "${l.minFocusCm} cm"} · " +
                    "längsta exp ${l.maxExposureSec?.let { String.format(Locale.US, "%.1f s", it) } ?: "-"} · ISO ${l.isoMin}-${l.isoMax}" +
                    " · tillägg ${l.camera2Extensions.ifEmpty { listOf("-") }.joinToString()}"
            )
        }
    }
}
