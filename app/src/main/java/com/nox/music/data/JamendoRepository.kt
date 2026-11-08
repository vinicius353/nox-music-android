package com.nox.music.data

import com.nox.music.model.Source
import com.nox.music.model.Track
import org.json.JSONObject

/**
 * Official Jamendo read-only API. Requires an app-specific free client_id.
 * Do not ship Jamendo's documentation-only testing key in production.
 */
class JamendoRepository(private val clientId: String, private val highQuality: Boolean = true) {
    private val base = "https://api.jamendo.com/v3.0/tracks/"

    fun search(query: String, limit: Int = 30): List<Track> =
        tracks(mapOf("search" to query, "limit" to limit.toString()))

    fun popular(limit: Int = 20): List<Track> =
        tracks(mapOf("order" to "popularity_week", "limit" to limit.toString()))

    fun artistTracks(id: String): List<Track> =
        tracks(mapOf("artist_id" to id, "limit" to "80"))

    fun albumTracks(id: String): List<Track> =
        tracks(mapOf("album_id" to id, "limit" to "80"))

    private fun tracks(filters: Map<String, String>): List<Track> {
        require(clientId.matches(Regex("[a-zA-Z0-9]{4,64}"))) { "Cadastre um client_id válido do Jamendo" }
        val params = mapOf(
            "client_id" to clientId,
            "format" to "json",
            "audioformat" to if (highQuality) "mp32" else "mp31",
            "imagesize" to "300",
            "type" to "single albumtrack"
        ) + filters
        val body: JSONObject = MusicHttp.get(base, params)
        val headers = body.optJSONObject("headers")
        if (headers?.optString("status") != "success") {
            error(headers?.optString("error_message")?.ifBlank { "Jamendo indisponível" } ?: "Jamendo indisponível")
        }
        val results = body.optJSONArray("results") ?: return emptyList()
        val output = ArrayList<Track>(results.length())
        for (i in 0 until results.length()) {
            val entry = results.optJSONObject(i) ?: continue
            val id = entry.optString("id")
            val audio = entry.optString("audio")
            if (id.isBlank() || !audio.startsWith("https://")) continue
            output += Track(
                id = "jamendo:$id",
                title = entry.optString("name").ifBlank { "Sem título" },
                artist = entry.optString("artist_name").ifBlank { "Artista Jamendo" },
                uri = audio,
                durationMs = entry.optLong("duration", 0).coerceAtLeast(0) * 1000L,
                source = Source.JAMENDO,
                album = entry.optString("album_name"),
                artworkUrl = entry.optString("image").takeIf { it.startsWith("https://") }.orEmpty(),
                artistId = entry.optString("artist_id"),
                albumId = entry.optString("album_id")
            )
        }
        return output
    }
}
