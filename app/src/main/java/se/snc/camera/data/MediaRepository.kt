package se.snc.camera.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import androidx.exifinterface.media.ExifInterface

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val isVideo: Boolean,
    val dateTaken: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val sizeBytes: Long
)

class MediaRepository(private val context: Context) {

    companion object {
        const val ALBUM = "SNC Camera"
        const val RELATIVE_PATH = "DCIM/$ALBUM"

        /** EXIF-uppgifter som behålls när en bild skrivs om (filter, porträtt). */
        private val KEEP_EXIF_TAGS = listOf(
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_OFFSET_TIME,
            ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
            ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
            ExifInterface.TAG_SUBSEC_TIME,
            ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
            ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_APERTURE_VALUE,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
            ExifInterface.TAG_FLASH,
            ExifInterface.TAG_WHITE_BALANCE,
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_DATESTAMP
        )

        fun newFileName(prefix: String, extension: String): String {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            return "${prefix}_$stamp.$extension"
        }
    }

    private val resolver get() = context.contentResolver

    private val _changes = MutableStateFlow(0)
    /** Räknare som ökar när media sparas eller tas bort – skärmar laddar om när den ändras. */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    fun notifyChanged() {
        _changes.value = _changes.value + 1
    }

    suspend fun loadMedia(): List<MediaItem> = withContext(Dispatchers.IO) {
        (query(isVideo = false) + query(isVideo = true)).sortedByDescending { it.dateTaken }
    }

    private fun query(isVideo: Boolean): List<MediaItem> {
        val collection = if (isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        }
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.DATE_TAKEN)
            add(MediaStore.MediaColumns.DATE_ADDED)
            add(MediaStore.MediaColumns.WIDTH)
            add(MediaStore.MediaColumns.HEIGHT)
            add(MediaStore.MediaColumns.SIZE)
            if (isVideo) add(MediaStore.Video.Media.DURATION)
        }.toTypedArray()
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.IS_PENDING} = 0"
        val args = arrayOf("$RELATIVE_PATH%")
        val sort = "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        val result = mutableListOf<MediaItem>()
        runCatching {
            resolver.query(collection, projection, selection, args, sort)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val takenCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
                val addedCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val wCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
                val hCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val durCol = if (isVideo) c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION) else -1
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val taken = c.getLong(takenCol).takeIf { it > 0 } ?: (c.getLong(addedCol) * 1000L)
                    result += MediaItem(
                        id = id,
                        uri = ContentUris.withAppendedId(collection, id),
                        name = c.getString(nameCol) ?: "",
                        isVideo = isVideo,
                        dateTaken = taken,
                        width = c.getInt(wCol),
                        height = c.getInt(hCol),
                        durationMs = if (durCol >= 0) c.getLong(durCol) else 0L,
                        sizeBytes = c.getLong(sizeCol)
                    )
                }
            }
        }
        return result
    }

    suspend fun latest(): MediaItem? = loadMedia().firstOrNull()

    /**
     * Miniatyr för galleriknappen. Direkt efter att en bild sparats kan systemets miniatyr saknas
     * en kort stund – då görs några nya försök, och för bilder avkodas filen själv som reserv.
     */
    suspend fun loadThumbnail(uri: Uri, sizePx: Int = 256): Bitmap? = withContext(Dispatchers.IO) {
        val isVideo = runCatching { resolver.getType(uri) }.getOrNull()?.startsWith("video") == true
        repeat(3) { attempt ->
            runCatching { resolver.loadThumbnail(uri, Size(sizePx, sizePx), null) }.getOrNull()
                ?.let { return@withContext it }
            if (!isVideo) loadBitmap(uri, sizePx)?.let { return@withContext it }
            delay(250L * (attempt + 1))
        }
        null
    }

    /**
     * Läser in en bild nedskalad till max [maxDimension] px. ImageDecoder tar hänsyn till EXIF-rotation.
     * Bitmappen är en mjukvarubitmapp så att den kan filtreras och skickas till textigenkänning.
     */
    suspend fun loadBitmap(uri: Uri, maxDimension: Int = 3000): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val source = ImageDecoder.createSource(resolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
                val w = info.size.width
                val h = info.size.height
                val largest = max(w, h)
                if (largest > maxDimension) {
                    val scale = maxDimension.toFloat() / largest
                    decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                }
            }
        }.getOrNull()
    }

    /** Sparar en redigerad bild som en ny JPEG i appens album. */
    suspend fun saveBitmap(bitmap: Bitmap, quality: Int = 95): Uri? = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, newFileName("IMG_EDIT", "jpg"))
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.MediaColumns.DATE_TAKEN, System.currentTimeMillis())
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: return@withContext null
        val ok = runCatching {
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(50, 100), out)
            } ?: false
        }.getOrDefault(false)
        if (!ok) {
            resolver.delete(uri, null, null)
            return@withContext null
        }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        notifyChanged()
        uri
    }

    /** Läser de EXIF-uppgifter som ska följa med när en bild skrivs om (datum, kamera, exponering, plats). */
    suspend fun readExif(uri: Uri): Map<String, String> = withContext(Dispatchers.IO) {
        runCatching {
            resolver.openInputStream(uri)?.use { input ->
                val exif = ExifInterface(input)
                KEEP_EXIF_TAGS.mapNotNull { tag -> exif.getAttribute(tag)?.let { tag to it } }.toMap()
            } ?: emptyMap()
        }.getOrDefault(emptyMap())
    }

    /**
     * Skriver över en JPEG med nya pixlar (rotationen är redan inbakad i bitmappen) och lägger
     * tillbaka EXIF-uppgifterna, så att datum, kamera och exponering syns under Detaljer.
     */
    suspend fun overwriteJpeg(uri: Uri, bitmap: Bitmap, quality: Int, exif: Map<String, String>): Boolean =
        withContext(Dispatchers.IO) {
            val ok = runCatching {
                resolver.openOutputStream(uri, "wt")?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(50, 100), out)
                } ?: false
            }.getOrDefault(false)
            if (ok && exif.isNotEmpty()) {
                runCatching {
                    resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                        val e = ExifInterface(pfd.fileDescriptor)
                        exif.forEach { (tag, value) -> e.setAttribute(tag, value) }
                        e.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                        e.saveAttributes()
                    }
                }
            }
            ok
        }

    /** Systemdialog för att ta bort filer (krävs för filer appen inte själv äger). */
    fun deleteRequest(uris: List<Uri>): IntentSender =
        MediaStore.createDeleteRequest(resolver, uris).intentSender

    fun mimeType(uri: Uri): String = resolver.getType(uri) ?: "image/jpeg"
}
