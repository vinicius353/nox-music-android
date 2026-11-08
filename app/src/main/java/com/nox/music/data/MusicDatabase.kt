package com.nox.music.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.nox.music.model.Track
import org.json.JSONArray
import org.json.JSONObject

data class MusicPlaylist(val id: Long, val name: String, val count: Int)

/** Small native SQLite store; transactions keep playlist ordering and queue snapshots consistent. */
class MusicDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "nox_music.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE history (track_id TEXT PRIMARY KEY, payload TEXT NOT NULL, played_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX history_recent ON history(played_at DESC)")
        db.execSQL("CREATE TABLE playlists (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL COLLATE NOCASE)")
        db.execSQL("CREATE TABLE playlist_tracks (id INTEGER PRIMARY KEY AUTOINCREMENT, playlist_id INTEGER NOT NULL, position INTEGER NOT NULL, payload TEXT NOT NULL)")
        db.execSQL("CREATE INDEX playlist_tracks_order ON playlist_tracks(playlist_id, position)")
        db.execSQL("CREATE TABLE state (name TEXT PRIMARY KEY, payload TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun history(max: Int = 40): List<Track> {
        val output = mutableListOf<Track>()
        readableDatabase.rawQuery(
            "SELECT payload FROM history ORDER BY played_at DESC LIMIT ?",
            arrayOf(max.coerceIn(1, 150).toString())
        ).use { rows ->
            while (rows.moveToNext()) safeTrack(rows.getString(0))?.let(output::add)
        }
        return output
    }

    @Synchronized
    fun recordPlayed(track: Track) {
        val values = ContentValues().apply {
            put("track_id", track.id)
            put("payload", track.toJson().toString())
            put("played_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("history", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        writableDatabase.execSQL("DELETE FROM history WHERE track_id NOT IN (SELECT track_id FROM history ORDER BY played_at DESC LIMIT 120)")
    }

    @Synchronized
    fun playlists(): List<MusicPlaylist> {
        val result = mutableListOf<MusicPlaylist>()
        readableDatabase.rawQuery(
            "SELECT p.id, p.name, COUNT(t.id) FROM playlists p LEFT JOIN playlist_tracks t ON t.playlist_id=p.id GROUP BY p.id ORDER BY p.id DESC",
            null
        ).use { rows ->
            while (rows.moveToNext()) result += MusicPlaylist(rows.getLong(0), rows.getString(1), rows.getInt(2))
        }
        return result
    }

    @Synchronized
    fun createPlaylist(name: String): Long {
        val clean = name.trim().take(64)
        require(clean.isNotEmpty()) { "Nome obrigatório" }
        return writableDatabase.insertOrThrow("playlists", null, ContentValues().apply { put("name", clean) })
    }

    @Synchronized
    fun deletePlaylist(id: Long) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("playlist_tracks", "playlist_id=?", arrayOf(id.toString()))
            writableDatabase.delete("playlists", "id=?", arrayOf(id.toString()))
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    @Synchronized
    fun playlistTracks(id: Long): List<Track> {
        val result = mutableListOf<Track>()
        readableDatabase.rawQuery(
            "SELECT payload FROM playlist_tracks WHERE playlist_id=? ORDER BY position ASC,id ASC",
            arrayOf(id.toString())
        ).use { rows ->
            while (rows.moveToNext()) safeTrack(rows.getString(0))?.let(result::add)
        }
        return result
    }

    @Synchronized
    fun addToPlaylist(id: Long, track: Track) {
        val db = writableDatabase
        val pos = db.rawQuery(
            "SELECT COALESCE(MAX(position),-1)+1 FROM playlist_tracks WHERE playlist_id=?",
            arrayOf(id.toString())
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
        db.insertOrThrow("playlist_tracks", null, ContentValues().apply {
            put("playlist_id", id)
            put("position", pos)
            put("payload", track.toJson().toString())
        })
    }

    @Synchronized
    fun removeFromPlaylist(id: Long, position: Int) {
        val db = writableDatabase
        val itemId = db.rawQuery(
            "SELECT id FROM playlist_tracks WHERE playlist_id=? ORDER BY position ASC,id ASC LIMIT 1 OFFSET ?",
            arrayOf(id.toString(), position.toString())
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else -1L }
        if (itemId >= 0) db.delete("playlist_tracks", "id=?", arrayOf(itemId.toString()))
    }

    @Synchronized
    fun reorderPlaylist(id: Long, from: Int, to: Int) {
        val list = playlistTracks(id).toMutableList()
        if (from !in list.indices || to !in list.indices || from == to) return
        val move = list.removeAt(from)
        list.add(to, move)
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("playlist_tracks", "playlist_id=?", arrayOf(id.toString()))
            list.forEachIndexed { i, track ->
                db.insertOrThrow("playlist_tracks", null, ContentValues().apply {
                    put("playlist_id", id)
                    put("position", i)
                    put("payload", track.toJson().toString())
                })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun saveQueue(queue: List<Track>, index: Int) {
        val array = JSONArray()
        queue.take(250).forEach { array.put(it.toJson()) }
        val payload = JSONObject().put("tracks", array).put("index", index.coerceAtLeast(0)).toString()
        writableDatabase.insertWithOnConflict("state", null, ContentValues().apply {
            put("name", "queue")
            put("payload", payload)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun restoreQueue(): Pair<List<Track>, Int> {
        val raw = readableDatabase.rawQuery("SELECT payload FROM state WHERE name='queue'", null)
            .use { if (it.moveToFirst()) it.getString(0) else null }
            ?: return emptyList<Track>() to 0
        return runCatching {
            val obj = JSONObject(raw)
            val array = obj.getJSONArray("tracks")
            val output = ArrayList<Track>(array.length())
            for (i in 0 until array.length()) safeTrack(array.getJSONObject(i).toString())?.let(output::add)
            output to obj.optInt("index", 0).coerceIn(0, (output.size - 1).coerceAtLeast(0))
        }.getOrDefault(emptyList<Track>() to 0)
    }

    private fun safeTrack(json: String): Track? =
        runCatching { Track.fromJson(JSONObject(json)) }.getOrNull()?.takeIf { it.id.isNotBlank() && it.uri.isNotBlank() }
}
