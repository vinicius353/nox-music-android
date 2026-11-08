package com.nox.music.data

import com.nox.music.model.Source
import com.nox.music.model.Track
import org.json.JSONArray
import org.json.JSONObject

/** Public Audius tracks only. Premium/gated tracks are not bypassed. */
class AudiusRepository {
    private val base = "https://api.audius.co/v1"

    fun trending(limit: Int = 24): List<Track> =
        parse(MusicHttp.get("$base/tracks/trending", mapOf("limit" to limit.toString())))

    fun search(query: String, limit: Int = 35): List<Track> {
        if (query.isBlank()) return emptyList()
        return parse(MusicHttp.get("$base/tracks/search",
            mapOf("query" to query, "limit" to limit.toString())))
    }

    fun artistTracks(id: String): List<Track> {
        if (!id.matches(Regex("[a-zA-Z0-9_-]{1,100}"))) return emptyList()
        return parse(MusicHttp.get("$base/users/$id/tracks", mapOf("limit" to "75")))
    }

    fun albumTracks(id: String): List<Track> {
        if (!id.matches(Regex("[a-zA-Z0-9_-]{1,100}"))) return emptyList()
        return parse(MusicHttp.get("$base/playlists/$id/tracks", mapOf("limit" to "75")))
    }

    private fun parse(json: JSONObject): List<Track> {
        val data = json.optJSONArray("data") ?: return emptyList()
        val tracks = ArrayList<Track>(data.length())
        for (i in 0 until data.length()) {
            val obj = data.optJSONObject(i) ?: continue
            val id = obj.optString("id")
            if (id.isBlank()) continue
            if (obj.optBoolean("is_stream_gated", false)) continue
            if (obj.optBoolean("is_delete", false)) continue
            val user = obj.optJSONObject("user")
            val artistName = user?.optString("name").orEmpty().ifBlank { "Artista Audius" }
            val artwork = obj.optJSONObject("artwork")
            val image = listOf("480x480", "1000x1000", "150x150").firstNotNullOfOrNull { key ->
                artwork?.optString(key)?.takeIf { it.startsWith("https://") }
            }.orEmpty()
            tracks += Track(
                id = "audius:$id",
                title = obj.optString("title").ifBlank { "Sem título" },
                artist = artistName,
                uri = "$base/tracks/$id/stream",
                durationMs = obj.optLong("duration", 0).coerceAtLeast(0) * 1000L,
                source = Source.AUDIOUS,
                album = obj.optString("album").ifBlank { obj.optString("playlist_name") },
                artworkUrl = image,
                artistId = user?.optString("id").orEmpty(),
                albumId = obj.optString("playlist_id")
            )
        }
        return tracks.distinctBy { it.id }
    }
}
