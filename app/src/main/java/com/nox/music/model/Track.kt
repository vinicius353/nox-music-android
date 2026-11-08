package com.nox.music.model

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.json.JSONObject

/** Each song carries its own provider identity; saved playlists never depend on a search page. */
enum class Source { LOCAL, AUDIOUS, JAMENDO, RADIO }

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val uri: String,
    val durationMs: Long = 0L,
    val source: Source = Source.LOCAL,
    val album: String = "",
    val artworkUrl: String = "",
    val artistId: String = "",
    val albumId: String = ""
) {
    val isLive: Boolean get() = source == Source.RADIO
    val providerName: String get() = when (source) {
        Source.LOCAL -> "No dispositivo"
        Source.AUDIOUS -> "Audius"
        Source.JAMENDO -> "Jamendo"
        Source.RADIO -> "Rádio ao vivo"
    }

    fun mediaItem(): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album.ifBlank { providerName })
            .setIsPlayable(true)
        if (artworkUrl.startsWith("https://") || artworkUrl.startsWith("content://")) {
            metadata.setArtworkUri(Uri.parse(artworkUrl))
        }
        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(Uri.parse(uri))
            .setMediaMetadata(metadata.build())
            .build()
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("artist", artist)
        put("uri", uri)
        put("durationMs", durationMs)
        put("source", source.name)
        put("album", album)
        put("artworkUrl", artworkUrl)
        put("artistId", artistId)
        put("albumId", albumId)
    }

    companion object {
        fun fromJson(obj: JSONObject): Track = Track(
            id = obj.optString("id"),
            title = obj.optString("title"),
            artist = obj.optString("artist"),
            uri = obj.optString("uri"),
            durationMs = obj.optLong("durationMs", 0),
            source = runCatching { Source.valueOf(obj.optString("source", "LOCAL")) }.getOrDefault(Source.LOCAL),
            album = obj.optString("album"),
            artworkUrl = obj.optString("artworkUrl"),
            artistId = obj.optString("artistId"),
            albumId = obj.optString("albumId")
        )
    }
}
