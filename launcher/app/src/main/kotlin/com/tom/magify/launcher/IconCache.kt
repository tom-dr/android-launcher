package com.tom.magify.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.collection.LruCache

/**
 * Decodes app icons once into a fixed-size bitmap (instead of keeping whatever
 * oversized bitmap an adaptive icon drawable defaults to) and keeps them in an
 * LRU cache keyed by "packageName/activityName", shared across every folder's
 * grid so scrolling never re-decodes an icon already seen.
 */
object IconCache {

    private var cache: LruCache<String, Drawable>? = null
    private var iconSizePx: Int = 0

    @Synchronized
    private fun ensureInitialized(context: Context) {
        if (cache != null) return
        val density = context.resources.displayMetrics.density
        iconSizePx = (48 * density).toInt()

        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memoryClassBytes = activityManager.memoryClass * 1024 * 1024
        // Budget ~1/16th of the app's memory class for cached icon bitmaps.
        val cacheBytes = (memoryClassBytes / 16).coerceAtLeast(4 * 1024 * 1024)

        cache = object : LruCache<String, Drawable>(cacheBytes) {
            override fun sizeOf(key: String, value: Drawable): Int {
                val bitmap = (value as? BitmapDrawable)?.bitmap
                return bitmap?.byteCount ?: (iconSizePx * iconSizePx * 4)
            }
        }
    }

    fun get(context: Context, key: String, load: () -> Drawable): Drawable {
        ensureInitialized(context)
        cache!!.get(key)?.let { return it }

        val source = load()
        val resized = resizeIcon(source)
        cache!!.put(key, resized)
        return resized
    }

    /**
     * Grows the cache so every icon the repository is about to warm up fits
     * at once. Without this, a memoryClass-based guess can end up smaller
     * than the real icon count on a device with many installed apps, so the
     * LRU evicts early folders' icons before the user ever scrolls back to
     * them — turning what should be a pure cache hit into a synchronous
     * decode on the main thread during a bind, i.e. a scroll jank spike.
     */
    @Synchronized
    fun ensureCapacity(context: Context, expectedIconCount: Int) {
        ensureInitialized(context)
        val perIconBytes = iconSizePx * iconSizePx * 4
        val neededBytes = (expectedIconCount * perIconBytes * 1.2).toInt()
            .coerceAtMost(64 * 1024 * 1024)
        if (neededBytes > cache!!.maxSize()) {
            cache!!.resize(neededBytes)
        }
    }

    private fun resizeIcon(source: Drawable): Drawable {
        val bitmap = Bitmap.createBitmap(iconSizePx, iconSizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        source.setBounds(0, 0, iconSizePx, iconSizePx)
        source.draw(canvas)
        return BitmapDrawable(null, bitmap)
    }
}
