package se.snc.camera.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import se.snc.camera.info.LensKind
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** En fysisk lins: typ och brännvidd i 35 mm-ekvivalent. */
data class LensBadge(val id: String, val kind: LensKind, val eqMm: Int?)

/**
 * Val av bakkamera. Många telefoner har flera "logiska" bakkameror som byter lins själva när man zoomar.
 * OnePlus 13R med LineageOS har t.ex. kamera 0 (vidvinkel + ultravidvinkel) och kamera 5
 * (vidvinkel + ultravidvinkel + tele). Appen tar den som har flest linser, så att 0,6× och 2×
 * använder de riktiga linserna.
 */
object LensSelection {

    fun equivalentFocal(ch: CameraCharacteristics): Int? {
        val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return null
        val size = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        val diag = sqrt(size.width * size.width + size.height * size.height)
        return if (diag > 0f) (focal * 43.27f / diag).roundToInt() else null
    }

    fun kindFor(front: Boolean, eq: Int?): LensKind = when {
        front -> LensKind.FRONT
        eq == null -> LensKind.OTHER
        eq <= 19 -> LensKind.ULTRA_WIDE
        eq >= 40 -> LensKind.TELE
        else -> LensKind.WIDE
    }

    /** ID för den bakkamera appen använder, eller null om Camera2 inte går att läsa. */
    fun preferredBackCameraId(context: Context): String? {
        val manager = context.getSystemService(CameraManager::class.java) ?: return null
        val ids = runCatching { manager.cameraIdList.toList() }.getOrDefault(emptyList())
        var best: String? = null
        var bestCount = -1
        for (id in ids) {
            val ch = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
            if (ch.get(CameraCharacteristics.LENS_FACING) != CameraMetadata.LENS_FACING_BACK) continue
            val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
            if (!caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)) continue
            val count = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
            ) ch.physicalCameraIds.size else 0
            // Strikt större: vid lika antal behålls den först listade (som CameraX standardval)
            if (count > bestCount) {
                best = id
                bestCount = count
            }
        }
        return best
    }

    /** Linserna bakom en kamera (för etiketten som visar vilken lins som är aktiv). */
    fun lensesOf(context: Context, cameraId: String): Map<String, LensBadge> {
        val manager = context.getSystemService(CameraManager::class.java) ?: return emptyMap()
        val ch = runCatching { manager.getCameraCharacteristics(cameraId) }.getOrNull() ?: return emptyMap()
        val physical = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) ch.physicalCameraIds else emptySet()
        val front = ch.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT
        return (physical.ifEmpty { setOf(cameraId) }).mapNotNull { id ->
            val pch = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: return@mapNotNull null
            val eq = equivalentFocal(pch)
            id to LensBadge(id, kindFor(front, eq), eq)
        }.toMap()
    }
}
