package com.nox.music.tests

import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.InnerTube
import com.zionhuang.innertube.models.YouTubeClient
import io.ktor.client.statement.bodyAsText
import com.zionhuang.innertube.models.SongItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/** Optional real-network probe. Does not download or retain a complete song. */
class InnerTubeLiveSmokeTest {
    @Test(timeout = 120000)
    fun searchAndResolveAudio() {
        if (System.getenv("NOX_YOUTUBE_SMOKE") != "1") return
        runBlocking {
            val client=InnerTube()
            val filters=listOf(
                "Unfiltered" to null,
                "SongFilter" to YouTube.SearchFilter.FILTER_SONG.value,
                "EmptyFilter" to ""
            )
            for ((label,params) in filters) {
                try {
                    val response=client.search(YouTubeClient.WEB_REMIX,"Coldplay Yellow",params)
                    println("NOX raw search $label: HTTP " + response.status.value
                        + " bytes=" + response.bodyAsText().length)
                } catch(e: Exception) {
                    println("NOX raw search $label failed: " + e::class.java.simpleName
                        + " " + e.localizedMessage?.take(180))
                }
            }
            val result=YouTube.search("Coldplay Yellow",YouTube.SearchFilter.FILTER_SONG).getOrThrow()
            val sample=result.items.filterIsInstance<SongItem>().firstOrNull()
                ?: error("YouTube Music search returned no SongItem")
            println("NOX smoke search: found YouTube Music song ID=" + sample.id)
            val response=YouTube.player(sample.id).getOrThrow()
            println("NOX smoke player status: " + response.playabilityStatus.status)
            assertTrue("YouTube playback status not OK: " + response.playabilityStatus.reason,
                response.playabilityStatus.status == "OK")
            val formats=response.streamingData?.adaptiveFormats.orEmpty()
            val candidate=formats.firstOrNull { it.isAudio && !it.url.isNullOrBlank() }
                ?: error("No direct audio URL found for the selected track")
            println("NOX smoke audio: bitrate=" + candidate.bitrate + " mime=" + candidate.mimeType)
            val http=URL(candidate.url!!).openConnection() as HttpURLConnection
            try {
                http.connectTimeout=10000
                http.readTimeout=10000
                http.instanceFollowRedirects=true
                http.setRequestProperty("Range","bytes=0-63")
                val status=http.responseCode
                println("NOX smoke stream HTTP status: " + status)
                assertTrue("Audio endpoint returned HTTP $status",status in 200..299)
                val count=http.inputStream.use { it.read(ByteArray(64)) }
                assertTrue("Stream empty",count > 0)
            } finally {
                http.disconnect()
            }
        }
    }
}
