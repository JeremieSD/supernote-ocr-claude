package dev.snocr.app

import android.graphics.Bitmap
import android.util.LruCache
import java.io.File

/** Small in-memory cache of rendered page thumbnails, keyed by file + mtime + size. */
object ThumbnailCache {
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) { // ~12 MB
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private fun key(file: File, sizePx: Int) =
        "${file.absolutePath}:${file.lastModified()}:$sizePx"

    fun get(file: File, sizePx: Int): Bitmap? = cache.get(key(file, sizePx))

    /**
     * Renders the first page of [file] to a thumbnail no larger than [sizePx],
     * caching the result. Returns null if the file can't be read. Call on a
     * background thread.
     */
    fun firstPage(file: File, sizePx: Int): Bitmap? {
        get(file, sizePx)?.let { return it }
        return try {
            PageSource.open(file).use { source ->
                if (source.pageCount == 0) return null
                val bitmap = source.renderPage(0, sizePx)
                cache.put(key(file, sizePx), bitmap)
                bitmap
            }
        } catch (e: Exception) {
            null
        }
    }
}
