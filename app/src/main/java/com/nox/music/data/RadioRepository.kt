package com.nox.music.data

import android.net.Uri
import com.nox.music.model.Source
import com.nox.music.model.Track
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * Community directory of radio stations (not an on-demand copyrighted music catalog).
 * Only metadata/stream URLs are retrieved; no third-party content is republished.
 */
class RadioRepository {
    fun search(query: String = "", tag: String = "", brazilOnly: Boolean = false): List<Track> {
        val builder = Uri.parse("https://de1.api.radio-browser.info/json/stations/search")
            .buildUpon()
            .appendQueryParameter("limit", "60")
            .appendQueryParameter("hidebroken", "true")
            .appendQueryParameter("order", "votes")
            .appendQueryParameter("reverse", "true")
        if (query.isNotBlank()) builder.appendQueryParameter("name", query)
        if (tag.isNotBlank()) builder.appendQueryParameter("tag", tag)
        if (brazilOnly) builder.appendQueryParameter("countrycode", "BR")

        val connection = (URL(builder.build().toString()).openConnection() as HttpURLConnection)
        connection.connectTimeout = 9000
        connection.readTimeout = 11000
        connection.setRequestProperty("User-Agent", "NOXMusic/0.1.0 (Android; open-source radio client)")
        connection.setRequestProperty("Accept", "application/json")
        try {
            if (connection.responseCode !in 200..299) {
                error("Catálogo indisponível: HTTP ${connection.responseCode}")
            }
            val json = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val array = JSONArray(json)
            val output = mutableListOf<Track>()
            val seen = mutableSetOf<String>()
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val stream = item.optString("url_resolved").ifBlank { item.optString("url") }
                val id = item.optString("stationuuid")
                val title = item.optString("name").trim()
                if (id.isBlank() || title.isBlank() || !(stream.startsWith("https://") || stream.startsWith("http://"))) continue
                if (!seen.add(id)) continue
                val country = item.optString("country")
                output += Track(
                    id = "radio:$id",
                    title = title,
                    artist = if (country.isBlank()) "Rádio ao vivo" else "$country • ao vivo",
                    uri = stream,
                    source = Source.RADIO
                )
            }
            return output
        } finally {
            connection.disconnect()
        }
    }
}
