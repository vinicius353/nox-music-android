package com.nox.music.data

import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Lightweight JSON transport. Only HTTPS API endpoints are accepted. */
object MusicHttp {
    fun get(endpoint: String, params: Map<String, String> = emptyMap()): JSONObject {
        require(endpoint.startsWith("https://")) { "Music APIs require HTTPS" }
        val builder = Uri.parse(endpoint).buildUpon()
        for ((key, value) in params) builder.appendQueryParameter(key, value)
        val connection = (URL(builder.build().toString()).openConnection() as HttpURLConnection).apply {
            connectTimeout = 7500
            readTimeout = 10500
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "NOXMusic/0.2.0 (Android; personal music client)")
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${connection.responseCode} ao consultar catálogo")
            }
            // Protect low-RAM phones against unexpectedly huge API replies.
            val input = connection.inputStream.bufferedReader(Charsets.UTF_8)
            val body = input.use { it.readText().take(2_000_000) }
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }
}
