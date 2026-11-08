package com.nox.music.data

import android.content.Context
import com.nox.music.model.Track
import org.json.JSONArray
import org.json.JSONObject

class FavoritesStore(context: Context) {
    private val prefs = context.getSharedPreferences("nox_favorites", Context.MODE_PRIVATE)

    @Synchronized
    fun all(): List<Track> {
        val result = mutableListOf<Track>()
        val array = runCatching { JSONArray(prefs.getString("tracks", "[]")) }.getOrDefault(JSONArray())
        for (i in 0 until array.length()) {
            runCatching { Track.fromJson(array.getJSONObject(i)) }.getOrNull()?.let(result::add)
        }
        return result
    }

    @Synchronized
    fun contains(id: String): Boolean = all().any { it.id == id }

    @Synchronized
    fun toggle(track: Track): Boolean {
        val tracks = all().toMutableList()
        val current = tracks.indexOfFirst { it.id == track.id }
        val added = current < 0
        if (added) tracks.add(0, track) else tracks.removeAt(current)
        val array = JSONArray()
        tracks.forEach { array.put(it.toJson()) }
        prefs.edit().putString("tracks", array.toString()).apply()
        return added
    }
}
