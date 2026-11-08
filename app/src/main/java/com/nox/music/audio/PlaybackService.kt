package com.nox.music.audio

import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.nox.music.data.YouTubeMusicRepository

/**
 * Single native Media3 session for local files, Audius, Jamendo, radio
 * and InnerTune YouTube Music. Android owns the media notification and lock-screen controls.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var player: ExoPlayer? = null
    private val youtube = YouTubeMusicRepository()

    override fun onCreate() {
        super.onCreate()
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("NOXMusic/0.5.0 (Android)")
        val base = DefaultDataSource.Factory(this, http)
        val resolving = ResolvingDataSource.Factory(base) { dataSpec ->
            // Media3 invokes this from its loader thread, never the Activity UI thread.
            val uri = dataSpec.uri
            if (uri.scheme == "https" && uri.host == "music.youtube.com" && uri.path == "/watch") {
                val videoId = uri.getQueryParameter("v")
                    ?: throw IllegalArgumentException("Faixa YouTube sem identificador")
                val nativeStreamUri = youtube.resolve(videoId)
                dataSpec.withUri(Uri.parse(nativeStreamUri))
            } else {
                dataSpec
            }
        }
        val audioPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolving))
            .build().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .setUsage(C.USAGE_MEDIA)
                        .build(),
                    true
                )
                setHandleAudioBecomingNoisy(true)
            }
        player = audioPlayer
        session = MediaSession.Builder(this, audioPlayer).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        session?.release()
        session = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
