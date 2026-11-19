package se.snc.camera.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.location.Location
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
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
import androidx.heifwriter.HeifWriter
import android.util.Log

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val isVideo: Boolean,
    val dateTaken: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val sizeBytes: Long,
    val isFavorite: Boolean = false,
    /** Ligger i papperskorgen (tas bort för gott [expiresAt]). */
    val trashed: Boolean = false,
    /** När Android tar bort den ur papperskorgen (ms), 0 = okänt. */
    val expiresAt: Long = 0L,
    /** Adressen utan tillägg – för Androids dialoger (ta bort, papperskorg, favorit). */
    val requestUri: Uri = uri
)

/** Resultatet av en åtgärd på bilder: klar, Android måste fråga användaren först, eller misslyckades. */
sealed interface MediaOp {
    data object Done : MediaOp
    class NeedsConsent(val intent: IntentSender) : MediaOp
    data object Failed : MediaOp
}

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

    /** Bilder och filmer i papperskorgen (Android tar bort dem för gott efter 30 dagar). */
    suspend fun loadTrash(): List<MediaItem> = withContext(Dispatchers.IO) {
        (query(isVideo = false, trashed = true) + query(isVideo = true, trashed = true)).sortedByDescending { it.expiresAt }
    }

    private val trashedPrefix = Regex("^\\.trashed-\\d+-")

    private fun query(isVideo: Boolean, trashed: Boolean = false): List<MediaItem> {
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
            add(MediaStore.MediaColumns.IS_FAVORITE)
            add(MediaStore.MediaColumns.DATE_EXPIRES)
            if (isVideo) add(MediaStore.Video.Media.DURATION)
        }.toTypedArray()
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.IS_PENDING} = 0"
        val args = arrayOf("$RELATIVE_PATH%")
        val sort = "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        val queryArgs = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sort)
            if (trashed) putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
        }
        val result = mutableListOf<MediaItem>()
        runCatching {
            resolver.query(collection, projection, queryArgs, null)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val takenCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
                val addedCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val wCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
                val hCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val durCol = if (isVideo) c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION) else -1
                val favCol = c.getColumnIndex(MediaStore.MediaColumns.IS_FAVORITE)
                val expCol = c.getColumnIndex(MediaStore.MediaColumns.DATE_EXPIRES)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val taken = c.getLong(takenCol).takeIf { it > 0 } ?: (c.getLong(addedCol) * 1000L)
                    val plain = ContentUris.withAppendedId(collection, id)
                    result += MediaItem(
                        id = id,
                        // Bilder i papperskorgen öppnas med en adress som säger att de får visas
                        uri = if (trashed) includeTrashed(plain) else plain,
                        name = (c.getString(nameCol) ?: "").replace(trashedPrefix, ""),
                        isVideo = isVideo,
                        dateTaken = taken,
                        width = c.getInt(wCol),
                        height = c.getInt(hCol),
                        durationMs = if (durCol >= 0) c.getLong(durCol) else 0L,
                        sizeBytes = c.getLong(sizeCol),
                        isFavorite = favCol >= 0 && c.getInt(favCol) == 1,
                        trashed = trashed,
                        expiresAt = if (expCol >= 0) c.getLong(expCol) * 1000L else 0L,
                        requestUri = plain
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

    /**
     * Sparar [bitmap] som en ny bild i [format] (HEIF eller PNG) bredvid originalet och tar bort
     * originalet (en JPEG som appen själv tog). Datum och, för PNG, bilduppgifterna följer med.
     * Ger den nya bildens adress, eller null om det inte gick (då ligger originalet kvar).
     */
    suspend fun replaceWithFormat(
        original: Uri,
        bitmap: Bitmap,
        format: PhotoFormat,
        quality: Int,
        exif: Map<String, String>
    ): Uri? = withContext(Dispatchers.IO) {
        if (format != PhotoFormat.HEIF && format != PhotoFormat.PNG) return@withContext null
        val baseName = runCatching {
            resolver.query(original, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()?.substringBeforeLast('.') ?: newFileName("IMG", "x").substringBeforeLast('.')
        val taken = runCatching {
            resolver.query(original, arrayOf(MediaStore.MediaColumns.DATE_TAKEN), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getLong(0) else null
            }
        }.getOrNull()?.takeIf { it > 0 } ?: System.currentTimeMillis()
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$baseName.${format.extension}")
            put(MediaStore.MediaColumns.MIME_TYPE, format.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.MediaColumns.DATE_TAKEN, taken)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = runCatching { resolver.insert(collection, values) }.getOrNull() ?: return@withContext null
        val ok = writeEncoded(uri, bitmap, format, quality)
        if (!ok) {
            runCatching { resolver.delete(uri, null, null) }
            return@withContext null
        }
        if (format == PhotoFormat.PNG && exif.isNotEmpty()) {
            runCatching {
                resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                    val e = ExifInterface(pfd.fileDescriptor)
                    exif.forEach { (tag, value) -> e.setAttribute(tag, value) }
                    e.saveAttributes()
                }
            }
        }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        runCatching { resolver.delete(original, null, null) }
        notifyChanged()
        uri
    }

    /** Skriver [bitmap] till [uri] som JPEG, PNG eller HEIF. RAW-format skrivs som JPEG. */
    private fun writeEncoded(uri: Uri, bitmap: Bitmap, format: PhotoFormat, quality: Int): Boolean = runCatching {
        when (format) {
            PhotoFormat.PNG -> resolver.openOutputStream(uri, "w")?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            } ?: false
            PhotoFormat.HEIF -> resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val writer = HeifWriter.Builder(pfd.fileDescriptor, bitmap.width, bitmap.height, HeifWriter.INPUT_MODE_BITMAP)
                    .setQuality(quality.coerceIn(50, 100))
                    .build()
                try {
                    writer.start()
                    writer.addBitmap(bitmap)
                    writer.stop(20_000L)
                } finally {
                    writer.close()
                }
                true
            } ?: false
            else -> resolver.openOutputStream(uri, "w")?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(50, 100), out)
            } ?: false
        }
    }.getOrElse { e ->
        Log.w("MediaRepository", "Kunde inte spara som ${format.label}", e)
        false
    }

    /**
     * Sparar en ny bild som appen själv har räknat fram (nattfoto, ljusspår, silkesvatten) i valt
     * filformat, med EXIF-uppgifterna [exif] (inte i HEIF). Ger bildens adress, eller null.
     */
    suspend fun saveNewPhoto(
        bitmap: Bitmap,
        format: PhotoFormat,
        quality: Int,
        exif: Map<String, String>,
        prefix: String = "IMG",
        location: Location? = null
    ): Uri? = withContext(Dispatchers.IO) {
        val fmt = if (format == PhotoFormat.HEIF || format == PhotoFormat.PNG) format else PhotoFormat.JPEG
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, newFileName(prefix, fmt.extension))
            put(MediaStore.MediaColumns.MIME_TYPE, fmt.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.MediaColumns.DATE_TAKEN, System.currentTimeMillis())
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = runCatching { resolver.insert(collection, values) }.getOrNull() ?: return@withContext null
        if (!writeEncoded(uri, bitmap, fmt, quality)) {
            runCatching { resolver.delete(uri, null, null) }
            return@withContext null
        }
        if (fmt != PhotoFormat.HEIF && (exif.isNotEmpty() || location != null)) {
            runCatching {
                resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                    val e = ExifInterface(pfd.fileDescriptor)
                    exif.forEach { (tag, value) -> e.setAttribute(tag, value) }
                    e.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                    if (location != null) e.setGpsInfo(location)
                    e.saveAttributes()
                }
            }.onFailure { Log.w("MediaRepository", "EXIF kunde inte skrivas", it) }
        }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        notifyChanged()
        uri
    }

    /** Systemdialog för att ta bort filer (krävs för filer appen inte själv äger). */
    fun deleteRequest(uris: List<Uri>): IntentSender =
        MediaStore.createDeleteRequest(resolver, uris).intentSender

    @Suppress("DEPRECATION")
    private fun includeTrashed(uri: Uri): Uri = MediaStore.setIncludeTrashed(uri)

    /**
     * Sätter en kolumn (favorit eller papperskorg) direkt på appens egna filer. Filer som appen inte äger
     * (t.ex. från en tidigare installation) kräver Androids dialog – då ges den i stället.
     */
    private suspend fun updateFlag(
        items: List<MediaItem>,
        column: String,
        value: Boolean,
        includeTrashedItems: Boolean,
        request: (List<Uri>) -> IntentSender
    ): MediaOp = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext MediaOp.Done
        val values = ContentValues().apply { put(column, if (value) 1 else 0) }
        val notDone = mutableListOf<MediaItem>()
        for (item in items) {
            val target = if (includeTrashedItems || item.trashed) includeTrashed(item.requestUri) else item.requestUri
            val count = try {
                resolver.update(target, values, null, null)
            } catch (e: SecurityException) {
                0
            } catch (e: Exception) {
                Log.w("MediaRepository", "$column kunde inte ändras på ${item.name}: $e")
                0
            }
            if (count <= 0) notDone += item
        }
        if (notDone.size < items.size) notifyChanged()
        if (notDone.isEmpty()) return@withContext MediaOp.Done
        runCatching { MediaOp.NeedsConsent(request(notDone.map { it.requestUri })) }.getOrElse {
            Log.w("MediaRepository", "$column: Androids dialog kunde inte skapas: $it")
            MediaOp.Failed
        }
    }

    /** Favorit eller inte. */
    suspend fun setFavorite(items: List<MediaItem>, favorite: Boolean): MediaOp =
        updateFlag(items, MediaStore.MediaColumns.IS_FAVORITE, favorite, includeTrashedItems = false) { uris ->
            MediaStore.createFavoriteRequest(resolver, uris, favorite).intentSender
        }

    /** Flyttar till papperskorgen ([trashed] = true) eller tillbaka. */
    suspend fun setTrashed(items: List<MediaItem>, trashed: Boolean): MediaOp =
        updateFlag(items, MediaStore.MediaColumns.IS_TRASHED, trashed, includeTrashedItems = !trashed) { uris ->
            MediaStore.createTrashRequest(resolver, uris, trashed).intentSender
        }

    /** Tar bort för gott (från papperskorgen). */
    suspend fun deleteForever(items: List<MediaItem>): MediaOp = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext MediaOp.Done
        val notDone = mutableListOf<MediaItem>()
        for (item in items) {
            val target = if (item.trashed) includeTrashed(item.requestUri) else item.requestUri
            val count = try {
                resolver.delete(target, null, null)
            } catch (e: SecurityException) {
                0
            } catch (e: Exception) {
                Log.w("MediaRepository", "${item.name} kunde inte tas bort: $e")
                0
            }
            if (count <= 0) notDone += item
        }
        if (notDone.size < items.size) notifyChanged()
        if (notDone.isEmpty()) return@withContext MediaOp.Done
        runCatching { MediaOp.NeedsConsent(deleteRequest(notDone.map { it.requestUri })) }.getOrDefault(MediaOp.Failed)
    }

    /** Platsen i en bild (EXIF) eller film (MP4), som (latitud, longitud). Null om den saknas. */
    suspend fun readLocation(item: MediaItem): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        runCatching {
            if (item.isVideo) {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(context, item.uri)
                    parseIso6709(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION))
                } finally {
                    r.release()
                }
            } else {
                // Utan setRequireOriginal skulle Android kunna dölja platsen
                val source = runCatching { MediaStore.setRequireOriginal(item.uri) }.getOrDefault(item.uri)
                val stream = runCatching { resolver.openInputStream(source) }.getOrNull() ?: resolver.openInputStream(item.uri)
                stream?.use { input ->
                    ExifInterface(input).latLong?.let { it[0] to it[1] }
                }
            }
        }.getOrNull()?.takeIf { !(it.first == 0.0 && it.second == 0.0) }
    }

    /** "+59.3293+018.0686/" → (59.3293, 18.0686). */
    private fun parseIso6709(v: String?): Pair<Double, Double>? {
        val m = Regex("([+-]\\d+(?:\\.\\d+)?)([+-]\\d+(?:\\.\\d+)?)").find(v ?: return null) ?: return null
        val lat = m.groupValues[1].toDoubleOrNull() ?: return null
        val lon = m.groupValues[2].toDoubleOrNull() ?: return null
        return lat to lon
    }

    fun mimeType(uri: Uri): String = resolver.getType(uri) ?: "image/jpeg"
}
