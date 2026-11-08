package com.nox.music.ui

import android.content.Context
import android.widget.ImageView
import coil3.ImageLoader
import coil3.load
import coil3.memory.MemoryCache
import coil3.disk.DiskCache
import java.io.File

/** Two-level bounded caching with image request lifecycle management. */
class CoverLoader(context: Context) {
    private val appContext = context.applicationContext
    private val imageCacheDir = File(appContext.cacheDir, "nox_artwork")
    private val loader = ImageLoader.Builder(appContext)
        .memoryCache {
            MemoryCache.Builder()
                .maxSizeBytes(8L * 1024 * 1024)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(imageCacheDir)
                .maxSizeBytes(80L * 1024 * 1024)
                .build()
        }
        .crossfade(true)
        .build()

    fun into(url: String, view: ImageView) {
        if (!url.startsWith("https://") && !url.startsWith("content://")) return
        view.load(url, loader) { crossfade(180) }
    }

    /** Compute off the main thread for a large cache. */
    fun diskUsageBytes(): Long =
        if (imageCacheDir.exists()) imageCacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        else 0L

    fun clear() {
        loader.memoryCache?.clear()
        loader.diskCache?.clear()
    }

    fun shutdown() = loader.shutdown()
}
