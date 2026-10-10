package com.testplaybyte.loom.data.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.testplaybyte.loom.data.workspace.Workspace

/**
 * Bitmap loading for real (non-demo) photos in the workspace.
 *
 * Deliberately dependency-free (no Coil): the app only needs
 *  · bounded full-size decodes for the annotate canvas, and
 *  · small thumbnails for the masonry grid / filmstrip / home cards,
 * and both must read through the [Workspace] abstraction (SAF streams or
 * private files) rather than a path.
 *
 * A tiny LRU cache keeps thumbnails cheap; keys include the requested size
 * so canvas and thumbnail variants never collide.
 */
object ImageStore {

    /** Max decoded dimension for the canvas (memory cap). */
    const val MAX_CANVAS_DIM = 2048

    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** Decoded image bounds without allocating pixels. */
    fun bounds(ws: Workspace, dir: String, name: String): Pair<Int, Int>? {
        val stream = ws.openInput(dir, name) ?: return null
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            stream.use { BitmapFactory.decodeStream(it, null, opts) }
            if (opts.outWidth <= 0 || opts.outHeight <= 0) null else opts.outWidth to opts.outHeight
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Decode [name] from [dir], downsampled so the longest edge ≤ [maxDim].
     * @param cacheKey when non-null the result is cached under this key.
     */
    fun load(ws: Workspace, dir: String, name: String, maxDim: Int, cacheKey: String? = null): Bitmap? {
        cacheKey?.let { key -> cache.get("$key@$maxDim")?.let { return it } }
        val bounds = bounds(ws, dir, name) ?: return null
        var sample = 1
        while (maxOf(bounds.first, bounds.second) / sample > maxDim) sample *= 2
        val stream = ws.openInput(dir, name) ?: return null
        val bitmap = try {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            stream.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (e: Exception) {
            null
        }
        if (bitmap != null && cacheKey != null) cache.put("$cacheKey@$maxDim", bitmap)
        return bitmap
    }

    /** Drop cached bitmaps for one project folder (after delete/rescan). */
    fun evict(dir: String) {
        val keys = cache.snapshot().keys.filter { it.startsWith("$dir/") }
        keys.forEach { cache.remove(it) }
    }

    fun evictAll() = cache.evictAll()
}
