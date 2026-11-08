package com.nox.music.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** 6 MiB in-memory album artwork cache, sampled to reduce bitmap allocations. */
class CoverLoader {
    private val pool = Executors.newFixedThreadPool(2)
    private val memory = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun into(url: String, view: ImageView) {
        if (!url.startsWith("https://")) return
        view.tag = url
        synchronized(memory) { memory.get(url) }?.let { view.setImageBitmap(it); return }
        val reference = WeakReference(view)
        pool.execute {
            val bitmap = runCatching {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 4500
                c.readTimeout = 6500
                c.instanceFollowRedirects = true
                try {
                    if (c.responseCode !in 200..299) return@runCatching null
                    if (c.contentLengthLong > 2_500_000L) return@runCatching null
                    val bytes = c.inputStream.use { input ->
                        val buffer = ByteArray(4096)
                        val output = java.io.ByteArrayOutputStream()
                        while (output.size() < 2_500_000) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    val factor = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / 300)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                        BitmapFactory.Options().apply { inSampleSize = factor })
                } finally {
                    c.disconnect()
                }
            }.getOrNull() ?: return@execute
            synchronized(memory) { memory.put(url, bitmap) }
            reference.get()?.post {
                val target = reference.get()
                if (target?.tag == url) target.setImageBitmap(bitmap)
            }
        }
    }

    fun shutdown() = pool.shutdownNow()
}
