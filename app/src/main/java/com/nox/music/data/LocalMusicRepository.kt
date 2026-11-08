package com.nox.music.data

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.nox.music.model.Source
import com.nox.music.model.Track

class LocalMusicRepository(private val context: Context) {
    fun load(): List<Track> {
        val found = mutableListOf<Track>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val columns = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION
        )
        context.contentResolver.query(
            collection,
            columns,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val title = cursor.getString(titleCol)?.takeIf { it.isNotBlank() } ?: "Sem título"
                val artist = cursor.getString(artistCol)?.takeIf { it.isNotBlank() && it != "<unknown>" }
                    ?: "Artista desconhecido"
                found += Track(
                    id = "local:$id",
                    title = title,
                    artist = artist,
                    uri = ContentUris.withAppendedId(collection, id).toString(),
                    durationMs = cursor.getLong(durationCol).coerceAtLeast(0L),
                    source = Source.LOCAL
                )
            }
        }
        return found
    }
}
