package com.nox.music.data

import com.nox.music.model.Source
import com.nox.music.model.Track
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.SongItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

/**
 * GPL-3.0 integration adapter for InnerTune's innertube module.
 * Search and stream URLs are loaded on background threads; do not call from the UI thread.
 */
class YouTubeMusicRepository {
    data class Stream(val uri: String, val expiresAtMs: Long)
    private val streams = ConcurrentHashMap<String, Stream>()

    fun search(query: String, limit: Int = 40): List<Track> {
        if (query.isBlank()) return emptyList()
        return runBlocking(Dispatchers.IO) {
            val found = YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrThrow()
            found.items.asSequence().filterIsInstance<SongItem>()
                .filter { it.id.matches(Regex("[A-Za-z0-9_-]{11}")) }
                .take(limit.coerceIn(1, 100))
                .map { item ->
                    Track(
                        id = "youtube:${item.id}",
                        title = item.title,
                        artist = item.artists.joinToString(", ") { it.name }.ifBlank { "YouTube Music" },
                        uri = "https://music.youtube.com/watch?v=${item.id}",
                        durationMs = (item.duration ?: 0).coerceAtLeast(0) * 1000L,
                        source = Source.YOUTUBE,
                        album = item.album?.name.orEmpty(),
                        artworkUrl = item.thumbnail.takeIf { it.startsWith("https://") }.orEmpty(),
                        artistId = item.artists.firstOrNull()?.id.orEmpty(),
                        albumId = item.album?.id.orEmpty()
                    )
                }.distinctBy { it.id }.toList()
        }
    }

    /**
     * Resolve just-in-time. YouTube's delivery URLs expire and must never be
     * persisted as playlist URIs; a playlist stores the video ID instead.
     */
    fun resolve(id: String): String {
        require(id.matches(Regex("[A-Za-z0-9_-]{11}"))) { "ID inválido" }
        val cached = streams[id]
        if (cached != null && cached.expiresAtMs - System.currentTimeMillis() > 60_000L) {
            return cached.uri
        }
        return runBlocking(Dispatchers.IO) {
            val response = YouTube.player(id).getOrThrow()
            if (response.playabilityStatus.status != "OK") {
                error(response.playabilityStatus.reason ?: "Faixa indisponível no YouTube Music")
            }
            val data = response.streamingData ?: error("YouTube Music não retornou áudio")
            val format = data.adaptiveFormats.asSequence()
                .filter { it.isAudio && it.mimeType.startsWith("audio/") && it.url?.startsWith("https://") == true }
                .maxByOrNull { it.bitrate }
                ?: error("Nenhum formato de áudio disponível")
            val url = format.url ?: error("URL da faixa indisponível")
            val expiry = System.currentTimeMillis() +
                (data.expiresInSeconds.coerceIn(60, 21_600).toLong() * 1000L)
            streams[id] = Stream(url, expiry)
            url
        }
    }

    fun invalidate(id: String) { streams.remove(id) }
}
