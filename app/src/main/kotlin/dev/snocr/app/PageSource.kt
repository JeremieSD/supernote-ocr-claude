package dev.snocr.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import dev.snocr.notekit.NoteParser
import dev.snocr.notekit.Notebook
import dev.snocr.notekit.PageRenderer
import dev.snocr.notekit.PngBitmapDecoder
import dev.snocr.notekit.RgbaBitmap
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException

/**
 * A uniform page source over both `.note` files (rendered by notekit) and
 * PDFs (rendered by Android's PdfRenderer). Renders any page to a Bitmap for
 * thumbnails and the reader, and encodes pages as PNG/JPEG for the API.
 *
 * Not thread-safe for PDF rendering: PdfRenderer allows only one open page at
 * a time, so callers must serialize renderPage() for a PDF source.
 */
sealed class PageSource : Closeable {
    abstract val pageCount: Int

    /** Renders [pageIndex] to an ARGB bitmap no larger than [maxLongEdge] px. */
    abstract fun renderPage(pageIndex: Int, maxLongEdge: Int): Bitmap

    override fun close() {}

    companion object {
        fun open(file: File): PageSource =
            if (file.extension.equals("pdf", ignoreCase = true)) PdfPageSource(file)
            else NotePageSource(file)
    }
}

private class NotePageSource(file: File) : PageSource() {
    private val notebook: Notebook = NoteParser.parse(file.readBytes())
    private val renderer = PageRenderer(androidPngDecoder)

    override val pageCount: Int get() = notebook.totalPages

    override fun renderPage(pageIndex: Int, maxLongEdge: Int): Bitmap {
        val rendered = renderer.render(notebook, pageIndex)
        val full = Bitmap.createBitmap(
            rendered.argb, rendered.width, rendered.height, Bitmap.Config.ARGB_8888
        )
        return full.scaledToLongEdge(maxLongEdge)
    }

    private val androidPngDecoder = PngBitmapDecoder { data ->
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
            ?: throw IOException("cannot decode template PNG")
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val result = RgbaBitmap(pixels, bitmap.width, bitmap.height)
        bitmap.recycle()
        result
    }
}

private class PdfPageSource(file: File) : PageSource() {
    private val descriptor: ParcelFileDescriptor =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val pdf = PdfRenderer(descriptor)

    override val pageCount: Int get() = pdf.pageCount

    @Synchronized
    override fun renderPage(pageIndex: Int, maxLongEdge: Int): Bitmap {
        pdf.openPage(pageIndex).use { page ->
            val longEdge = maxOf(page.width, page.height).coerceAtLeast(1)
            val scale = minOf(1f, maxLongEdge.toFloat() / longEdge)
            val w = (page.width * scale).toInt().coerceAtLeast(1)
            val h = (page.height * scale).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE) // PDFs render with transparency otherwise
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bitmap
        }
    }

    @Synchronized
    override fun close() {
        runCatching { pdf.close() }
        runCatching { descriptor.close() }
    }
}

/** Scales a bitmap down so its long edge is at most [maxLongEdge] (never up). */
fun Bitmap.scaledToLongEdge(maxLongEdge: Int): Bitmap {
    val longEdge = maxOf(width, height)
    if (longEdge <= maxLongEdge) return this
    val scale = maxLongEdge.toFloat() / longEdge
    val scaled = Bitmap.createScaledBitmap(
        this, (width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1), true
    )
    if (scaled != this) recycle()
    return scaled
}

/** Encodes a bitmap as PNG bytes. */
fun Bitmap.toPng(): ByteArray = ByteArrayOutputStream().use {
    compress(Bitmap.CompressFormat.PNG, 100, it)
    it.toByteArray()
}

/** Encodes a bitmap as JPEG bytes at the given quality. */
fun Bitmap.toJpeg(quality: Int = 85): ByteArray = ByteArrayOutputStream().use {
    compress(Bitmap.CompressFormat.JPEG, quality, it)
    it.toByteArray()
}
