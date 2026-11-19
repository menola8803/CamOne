package se.snc.camera.scan

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.provider.MediaStore
import se.snc.camera.data.MediaRepository
import java.io.File

/** Hur en sida i dokumentskannern ska se ut. */
enum class DocFilter { DOCUMENT, COLOR, ORIGINAL }

/**
 * Dokumentskannerns Android-del: rätar papperet (perspektiv med fyra hörn), jämnar ut belysningen
 * (DocumentMath.enhance) och sätter ihop sidorna till en PDF i Dokument/SNC Camera.
 */
object DocumentPdf {

    /** Sidorna sparas med högst så här många bildpunkter på längden (ca 200 dpi för A4). */
    const val MAX_LONG = 2200

    /** A4 i punkter (1/72 tum). */
    private const val A4_W = 595
    private const val A4_H = 842

    const val RELATIVE_PATH = "Documents/SNC Camera"

    /**
     * Rätar papperet i [src] mellan hörnen [quad] (normaliserade, övre vänster medurs) till en rak sida.
     */
    fun warp(src: Bitmap, quad: FloatArray, maxLong: Int = MAX_LONG): Bitmap {
        val (w, h) = DocumentMath.outputSize(quad, src.width, src.height, maxLong)
        val from = FloatArray(8) { k -> quad[k] * (if (k % 2 == 0) src.width else src.height) }
        val to = floatArrayOf(0f, 0f, w.toFloat(), 0f, w.toFloat(), h.toFloat(), 0f, h.toFloat())
        val m = Matrix()
        m.setPolyToPoly(from, 0, to, 0, 4)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    /** Rätar, förbättrar och sparar en sida som JPEG i [file]. Ger sidans storlek. */
    fun makePage(src: Bitmap, quad: FloatArray, filter: DocFilter, file: File): Pair<Int, Int> {
        val page = warp(src, quad)
        if (filter != DocFilter.ORIGINAL) {
            val px = IntArray(page.width * page.height)
            page.getPixels(px, 0, page.width, 0, 0, page.width, page.height)
            DocumentMath.enhance(px, page.width, page.height, color = filter == DocFilter.COLOR)
            page.setPixels(px, 0, page.width, 0, 0, page.width, page.height)
        }
        file.parentFile?.mkdirs()
        file.outputStream().use { page.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        val size = page.width to page.height
        page.recycle()
        return size
    }

    /** Hittar papperet i en tagen bild (litet rutnät av ljusstyrkan). Ger standardhörn om inget hittas. */
    fun detectInBitmap(src: Bitmap): FloatArray {
        val gw = DocumentMath.GRID
        val gh = (gw * src.height / src.width.toFloat()).toInt().coerceAtLeast(8)
        val small = Bitmap.createScaledBitmap(src, gw, gh, true)
        val px = IntArray(gw * gh)
        small.getPixels(px, 0, gw, 0, 0, gw, gh)
        if (small !== src) small.recycle()
        return DocumentMath.detect(DocumentMath.luminance(px), gw, gh) ?: DocumentMath.defaultQuad()
    }

    /**
     * Sätter ihop [pages] (JPEG-filer) till en PDF med en A4-sida per bild (liggande A4 för breda sidor)
     * och sparar den i Dokument/SNC Camera. Ger filens adress, eller null om det inte gick.
     */
    fun savePdf(context: Context, pages: List<File>): Uri? {
        if (pages.isEmpty()) return null
        val doc = PdfDocument()
        try {
            pages.forEachIndexed { i, f ->
                val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: return@forEachIndexed
                val landscape = bmp.width > bmp.height
                val pw = if (landscape) A4_H else A4_W
                val ph = if (landscape) A4_W else A4_H
                val page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, i + 1).create())
                val c = page.canvas
                c.drawColor(Color.WHITE)
                // Bilden fyller sidan med bibehållet format, centrerad
                val s = minOf(pw / bmp.width.toFloat(), ph / bmp.height.toFloat())
                val dw = bmp.width * s
                val dh = bmp.height * s
                val dst = RectF((pw - dw) / 2f, (ph - dh) / 2f, (pw + dw) / 2f, (ph + dh) / 2f)
                c.drawBitmap(bmp, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
                doc.finishPage(page)
                bmp.recycle()
            }
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, MediaRepository.newFileName("SCAN", "pdf"))
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: return null
            try {
                resolver.openOutputStream(uri)?.use { doc.writeTo(it) } ?: error("filen kunde inte öppnas")
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                return uri
            } catch (t: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw t
            }
        } finally {
            doc.close()
        }
    }

    /** Mappen för sidorna som väntar på att bli en PDF (rensas när PDF:en sparas eller sidorna slängs). */
    fun pagesDir(context: Context): File = File(context.cacheDir, "doc-pages")
}
