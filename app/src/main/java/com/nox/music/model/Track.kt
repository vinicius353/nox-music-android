package com.nox.music.model

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.json.JSONObject

enum class Source { LOCAL, RADIO }

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val uri: String,
    val durationMs: Long = 0L,
    val source: Source = Source.LOCAL
) {
    val isLive: Boolean get() = source == Source.RADIO

    fun mediaItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setUri(Uri.parse(uri))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(if (isLive) "Rádio online" else "Biblioteca local")
                    .setIsPlayable(true)
                    .build()
            )
            .build()

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("artist", artist)
        put("uri", uri)
        put("durationMs", durationMs)
        put("source", source.name)
    }

    companion object {
        fun fromJson(obj: JSONObject): Track = Track(
            id = obj.optString("id"),
            title = obj.optString("title"),
            artist = obj.optString("artist"),
            uri = obj.optString("uri"),
            durationMs = obj.optLong("durationMs", 0L),
            source = runCatching { Source.valueOf(obj.optString("source")) }.getOrDefault(Source.LOCAL)
        )
    }
}
