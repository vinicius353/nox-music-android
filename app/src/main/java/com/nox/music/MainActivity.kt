package com.nox.music

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.nox.music.data.FavoritesStore
import com.nox.music.data.AudiusRepository
import com.nox.music.data.JamendoRepository
import com.nox.music.data.MusicDatabase
import com.nox.music.data.MusicPlaylist
import com.nox.music.ui.CoverLoader
import com.nox.music.ui.YouTubeMusicPanel
import com.nox.music.data.LocalMusicRepository
import com.nox.music.data.RadioRepository
import com.nox.music.audio.PlaybackService
import com.nox.music.model.Source
import com.nox.music.model.Track
import java.util.concurrent.Executors
import java.util.concurrent.Executor
import kotlin.math.min

class MainActivity : Activity() {
    private val bgColor = Color.rgb(5, 5, 5)
    private val card = Color.rgb(22, 22, 22)
    private val surface = Color.rgb(37, 37, 37)
    private val light = Color.rgb(245, 245, 245)
    private val secondary = Color.rgb(145, 145, 145)
    private val accent = Color.rgb(255, 255, 255)
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(2)
    private val stateIo = Executors.newSingleThreadExecutor()

    private lateinit var favorites: FavoritesStore
    private lateinit var libraryDb: MusicDatabase
    private val audius = AudiusRepository()
    private lateinit var covers: CoverLoader
    private var trendingTracks: List<Track> = emptyList()
    private var jamendoPopularTracks: List<Track> = emptyList()
    private var jamendoLoading = false
    private var jamendoError: String? = null
    private var searchPendingSources = 0
    private var onlineTracks: List<Track> = emptyList()
    private var onlineLoading = false
    private var onlineError: String? = null
    private var searchText = ""
    private var searchRequest = 0
    private var searchDebounce: Runnable? = null
    private var searchResults: LinearLayout? = null
    private var selectedPlaylistId = -1L
    private var detailTrack: Track? = null
    private var detailAlbum = false
    private var detailResults: List<Track> = emptyList()
    private var detailLoading = false
    private var priorTab = 0
    private var lastHistoryId = ""
    private var miniArtworkId = ""
    private var miniProgress: View? = null
    private var miniContainer: FrameLayout? = null
    private var youtubePanel: YouTubeMusicPanel? = null
    private var youtubeSearchQuery: String? = null
    private lateinit var root: FrameLayout
    private lateinit var content: FrameLayout
    private lateinit var nav: LinearLayout
    private lateinit var mini: LinearLayout
    private lateinit var miniTitle: TextView
    private lateinit var miniArtist: TextView
    private lateinit var miniPlay: ImageView
    private var miniArt: FrameLayout? = null

    private var localTracks: List<Track> = emptyList()
    private var radios: List<Track> = emptyList()
    private var activeQueue: List<Track> = emptyList()
    private var tab = 0
    private var radioTag = ""
    private var radioBrazil = true
    private var radioSearch = ""
    private var radioLoading = false
    private var radioError: String? = null
    private var radioRequest = 0
    private var radioContainer: LinearLayout? = null
    private var radioDebounce: Runnable? = null

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var player: MediaController? = null
    private var overlay: FrameLayout? = null
    private var playerTitle: TextView? = null
    private var playerArtist: TextView? = null
    private var playerPlay: ImageView? = null
    private var playerFavorite: TextView? = null
    private var playerShuffle: TextView? = null
    private var playerRepeat: TextView? = null
    private var playerProgress: SeekBar? = null
    private var playerElapsed: TextView? = null
    private var playerTotal: TextView? = null
    private var seeking = false

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = syncPlayback()
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            Toast.makeText(this@MainActivity,
                "Falha ao reproduzir. Verifique a conexão ou tente outra faixa.",
                Toast.LENGTH_LONG).show()
        }
    }
    private val ticker = object : Runnable {
        override fun run() {
            syncPlayback()
            handler.postDelayed(this, 750)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        covers = CoverLoader(applicationContext)
        favorites = FavoritesStore(this)
        libraryDb = MusicDatabase(this)
        @Suppress("DEPRECATION")
        window.statusBarColor = bgColor
        @Suppress("DEPRECATION")
        window.navigationBarColor = bgColor
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = 0
        buildShell()
        connectPlayback()
        if (hasAudioPermission()) loadLocal() else requestAudioPermission()
        loadTrending()
        loadJamendoPopular()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        if (tab == 10 && youtubePanel == null && ::content.isInitialized) renderPage()
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        youtubePanel?.close()
        youtubePanel = null
        super.onPause()
    }

    override fun onDestroy() {
        youtubePanel?.close()
        youtubePanel = null
        handler.removeCallbacksAndMessages(null)
        player?.removeListener(listener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        io.shutdownNow()
        covers.shutdown()
        stateIo.execute { libraryDb.close() }
        stateIo.shutdown()
        super.onDestroy()
    }

    @Deprecated("Legacy permissions callback for Android 8–12 support")
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            loadLocal()
        } else if (requestCode == 101) {
            renderPage()
        }
    }

    @Deprecated("Handled by native player overlay")
    override fun onBackPressed() {
        when {
            overlay != null -> closePlayer()
            tab == 10 && youtubePanel?.canGoBack() == true -> youtubePanel?.goBack()
            tab == 10 -> openTab(0)
            tab in 6..9 -> openTab(if (priorTab in 0..5) priorTab else 3)
            tab != 0 -> openTab(0)
            else -> super.onBackPressed()
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun shape(color: Int, radius: Int = 18): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun gradient(vararg colors: Int, radius: Int = 22): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
            cornerRadius = dp(radius).toFloat()
        }
    private fun lp(w: Int = -1, h: Int = -2, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) =
        LinearLayout.LayoutParams(if (w < 0) w else dp(w), if (h < 0) h else dp(h)).apply {
            setMargins(dp(l), dp(t), dp(r), dp(b))
        }
    private fun text(
        value: String, size: Float = 14f, color: Int = light, bold: Boolean = false
    ): TextView = TextView(this).apply {
        this.text = value
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.BOLD)
                   else Typeface.create("sans-serif", Typeface.NORMAL)
        includeFontPadding = false
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    private fun icon(resource: Int, size: Int = 24, tint: Int = light): ImageView =
        ImageView(this).apply {
            setImageResource(resource)
            imageTintList = ColorStateList.valueOf(tint)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = lp(size, size)
        }

    private fun buildShell() {
        root = FrameLayout(this).apply { setBackgroundColor(bgColor) }
        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(column, FrameLayout.LayoutParams(-1, -1))

        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(12), dp(18), dp(10))
        }
        val mark = ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher_foreground)
            background = shape(Color.rgb(18,18,18), 12)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "NOX Music"
        }
        header.addView(mark, lp(42, 42, 0, 0, 12, 0))
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(text("NOX", 19f, light, true))
        val tagline = text("M U S I C", 8.5f, secondary, true)
        brand.addView(tagline, lp(-1, -2, 0, 2))
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        val settings = icon(R.drawable.ic_settings, 23, secondary).apply {
            contentDescription = "Configurações"
            setPadding(dp(1), dp(1), dp(1), dp(1))
            setOnClickListener { openTab(5) }
        }
        header.addView(settings, lp(28, 28))
        column.addView(header, lp(-1, 68))

        content = FrameLayout(this)
        column.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        mini = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(Color.rgb(26, 26, 26), 14)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            visibility = View.GONE
            setOnClickListener { showPlayer() }
        }
        miniArt = artwork(null, 46, 11)
        mini.addView(miniArt!!, lp(46, 46, 0, 0, 11))
        val miniMeta = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        miniTitle = text("Nenhuma música", 13.5f, light, true).apply { maxLines = 1 }
        miniArtist = text("", 11f, secondary).apply { maxLines = 1 }
        miniMeta.addView(miniTitle)
        miniMeta.addView(miniArtist, lp(-1, -2, 0, 5))
        mini.addView(miniMeta, LinearLayout.LayoutParams(0, -2, 1f))
        miniPlay = icon(R.drawable.ic_play, 26, light).apply {
            contentDescription = "Reproduzir ou pausar"
            setOnClickListener { togglePlayback() }
        }
        mini.addView(miniPlay, lp(27, 27, 6, 0, 10))
        val next = icon(R.drawable.ic_next, 24, light).apply {
            contentDescription = "Próxima música"
            setOnClickListener { player?.seekToNextMediaItem() }
        }
        mini.addView(next, lp(27, 27))
        val miniFrame = FrameLayout(this).apply {
            visibility = View.GONE
            clipToPadding = false
        }
        miniContainer = miniFrame
        mini.visibility = View.VISIBLE
        miniFrame.addView(mini, FrameLayout.LayoutParams(-1, dp(63), Gravity.CENTER))
        miniProgress = View(this).apply {
            setBackgroundColor(Color.rgb(232,232,232))
            pivotX = 0f
            scaleX = 0f
            visibility = View.INVISIBLE
        }
        miniFrame.addView(miniProgress,FrameLayout.LayoutParams(-1,dp(2),Gravity.BOTTOM))
        column.addView(miniFrame,lp(-1,65,12,1,12,2))

        nav = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(6), dp(12), dp(8))
        }
        column.addView(nav, lp(-1, 69))
        setContentView(root)
        renderPage()
        renderNav()
    }

    private fun openYouTubeMusic(query: String? = null) {
        player?.pause()
        youtubeSearchQuery = query?.trim()?.takeIf { it.isNotBlank() }
        openTab(10)
    }

    private fun renderNav() {
        nav.removeAllViews()
        val entries = listOf(
            Triple("Início", R.drawable.ic_home, 0),
            Triple("Buscar", R.drawable.ic_search, 1),
            Triple("YT Music", R.drawable.ic_youtube_music, 10),
            Triple("Offline", R.drawable.ic_library, 2),
            Triple("Coleção", R.drawable.ic_heart, 3)
        )
        for ((label, drawable, index) in entries) {
            val selected = tab == index
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = if (selected) shape(card, 11) else shape(bgColor, 11)
                contentDescription = label
                setOnClickListener { openTab(index) }
            }
            cell.addView(icon(drawable, 20, if (selected) light else secondary), lp(20, 20))
            val name = text(label, 10f, if (selected) light else secondary, selected)
            name.gravity = Gravity.CENTER
            cell.addView(name, lp(-1, -2, 0, 5))
            nav.addView(cell, LinearLayout.LayoutParams(0, -1, 1f).apply {
                marginStart = dp(2); marginEnd = dp(2)
            })
        }
    }

    private fun openTab(index: Int) {
        if (overlay != null) closePlayer(immediate = true)
        if (tab == 10 && youtubePanel != null) {
            youtubePanel?.close()
            youtubePanel = null
        }
        if (index >= 6 && tab <= 5) priorTab = tab
        tab = index
        searchResults = null
        radioContainer = null
        renderPage()
        renderNav()
    }

    private fun renderPage() {
        if (!::content.isInitialized) return
        content.removeAllViews()
        searchResults = null
        radioContainer = null
        if (tab == 10) {
            val surface = YouTubeMusicPanel(this)
            youtubePanel = surface
            content.addView(surface.create(youtubeSearchQuery), FrameLayout.LayoutParams(-1, -1))
            youtubeSearchQuery = null
            return
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            clipToPadding = false
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(12), dp(22), dp(27))
        }
        scroll.addView(column, FrameLayout.LayoutParams(-1, -2))
        content.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        when (tab) {
            0 -> home(column)
            1 -> searchPage(column)
            2 -> libraryPage(column)
            3 -> collectionPage(column)
            4 -> radioPage(column)
            5 -> settingsPage(column)
            6 -> playlistDetailPage(column)
            7 -> favoritesPage(column)
            8 -> queuePage(column)
            9 -> detailPage(column)
            else -> home(column)
        }
        if (getSharedPreferences("nox_settings", MODE_PRIVATE).getBoolean("animations", true)) {
            scroll.alpha = 0f
            scroll.translationY = dp(11).toFloat()
            scroll.animate().alpha(1f).translationY(0f).setDuration(200)
                .setInterpolator(DecelerateInterpolator()).start()
        }
    }

    private fun heading(column: LinearLayout, eyebrow: String, title: String, subtitle: String) {
        val cap = text(eyebrow.uppercase(), 10f, accent, true).apply { letterSpacing = 0.18f }
        column.addView(cap, lp(-1, -2, 0, 5))
        column.addView(text(title, 30f, light, true), lp(-1, -2, 0, 4))
        column.addView(text(subtitle, 13f, secondary), lp(-1, -2, 0, 4, 0, 23))
    }

    private fun section(column: LinearLayout, name: String, action: String? = null, onAction: (() -> Unit)? = null) {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(text(name, 19f, light, true), LinearLayout.LayoutParams(0, -2, 1f))
        if (action != null && onAction != null) {
            val link = text(action, 12f, accent, true).apply {
                setPadding(dp(8), dp(8), 0, dp(8))
                setOnClickListener { onAction() }
            }
            row.addView(link)
        }
        column.addView(row, lp(-1, -2, 0, 4, 0, 10))
    }

    private fun home(column: LinearLayout) {
        val greeting = when (java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)) {
            in 5..11 -> "Bom dia"
            in 12..17 -> "Boa tarde"
            else -> "Boa noite"
        }
        column.addView(text(greeting.uppercase(),10f,secondary,true).apply { letterSpacing = 0.15f },
            lp(-1,-2,0,3,0,9))
        column.addView(text("Tudo em um só lugar.",29f,light,true),lp(-1,-2,0,0,0,7))
        column.addView(text("Música sem distrações.",13f,secondary),lp(-1,-2,0,0,0,22))

        val latest = libraryDb.history(1).firstOrNull()
        val recommended = latest ?: jamendoPopularTracks.firstOrNull() ?: trendingTracks.firstOrNull()
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(17),dp(18),dp(13),dp(18))
            background = gradient(Color.rgb(37,37,37),Color.rgb(18,18,18),radius=22)
            setOnClickListener {
                if (recommended != null) startTrack(listOf(recommended),0) else openTab(1)
            }
        }
        hero.addView(artwork(recommended,91,14),lp(91,91,0,0,15))
        val heroInfo = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        heroInfo.addView(text(if(latest != null) "CONTINUE OUVINDO" else "DESTAQUE NOX",10f,secondary,true)
            .apply { letterSpacing = 0.14f })
        heroInfo.addView(text(recommended?.title ?: "Encontre seu som.",17f,light,true)
            .apply { maxLines=2 },lp(-1,-2,0,8))
        heroInfo.addView(text(recommended?.artist ?: "Explore músicas online",11f,secondary),
            lp(-1,-2,0,7))
        heroInfo.addView(text("▶  REPRODUZIR",11f,light,true),lp(-1,-2,0,12))
        hero.addView(heroInfo,LinearLayout.LayoutParams(0,-2,1f))
        column.addView(hero,lp(-1,-2,0,0,0,18))

        val shortcuts = LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        val links=listOf(Triple("Buscar",R.drawable.ic_search,1),Triple("Offline",R.drawable.ic_library,2),
            Triple("Playlists",R.drawable.ic_heart,3))
        for ((name,drawable,destination) in links) {
            val chip=LinearLayout(this).apply {
                gravity=Gravity.CENTER
                orientation=LinearLayout.HORIZONTAL
                background=shape(card,14)
                setOnClickListener { openTab(destination) }
            }
            chip.addView(icon(drawable,17,light),lp(17,17,0,0,7))
            chip.addView(text(name,12f,light,true))
            shortcuts.addView(chip,LinearLayout.LayoutParams(0,dp(51),1f).apply {
                marginEnd=dp(7)
            })
        }
        column.addView(shortcuts,lp(-1,51,0,0,0,15))

        val youtubeCard=LinearLayout(this).apply {
            gravity=Gravity.CENTER_VERTICAL
            orientation=LinearLayout.HORIZONTAL
            background=shape(Color.rgb(30,30,30),19)
            setPadding(dp(17),dp(17),dp(15),dp(17))
            setOnClickListener { openYouTubeMusic() }
        }
        val youtubeText=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        youtubeText.addView(text("YOUTUBE MUSIC",11f,light,true).apply { letterSpacing=.10f })
        youtubeText.addView(text("Mais músicas para explorar.",16f,light,true),lp(-1,-2,0,7))
        youtubeText.addView(text("Catálogo oficial • reprodução pelo serviço",11f,secondary),lp(-1,-2,0,5))
        youtubeCard.addView(youtubeText,LinearLayout.LayoutParams(0,-2,1f))
        youtubeCard.addView(icon(R.drawable.ic_youtube_music,31,light),lp(31,31,11))
        column.addView(youtubeCard,lp(-1,-2,0,0,0,27))

        val recent=libraryDb.history(8)
        if(recent.isNotEmpty()) {
            section(column,"Recentes","Ver coleção") { openTab(3) }
            val horizontal=HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled=false
                overScrollMode=View.OVER_SCROLL_NEVER
            }
            val tiles=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
            for(track in recent.take(8)) {
                val tile=LinearLayout(this).apply {
                    orientation=LinearLayout.VERTICAL
                    setOnClickListener { startTrack(recent, recent.indexOf(track)) }
                }
                tile.addView(artwork(track,102,16),lp(102,102,0,0,0,9))
                tile.addView(text(track.title,12f,light,true).apply { maxLines=1 },lp(102,-2))
                tile.addView(text(track.artist,10f,secondary).apply { maxLines=1 },lp(102,-2,0,5))
                tiles.addView(tile,lp(102,-2,0,0,13,0))
            }
            horizontal.addView(tiles)
            column.addView(horizontal,lp(-1,-2,0,0,0,27))
        }
        section(column,"Descobertas Jamendo","Buscar") { openTab(1) }
        when {
            jamendoLoading && jamendoPopularTracks.isEmpty() -> sectionSkeleton(column,3)
            jamendoPopularTracks.isEmpty() -> infoCard(column,
                "Jamendo indisponível",
                if (jamendoError != null) "Não conseguimos carregar esse catálogo agora."
                else "Novas descobertas aparecerão aqui.",
                "TENTAR DE NOVO") { loadJamendoPopular() }
            else -> addRows(column,jamendoPopularTracks.take(5),jamendoPopularTracks)
        }
        column.addView(View(this),lp(-1,23))
        section(column,"Em alta no Audius","Explorar") { openTab(1) }
        when {
            onlineLoading && trendingTracks.isEmpty() -> sectionSkeleton(column,3)
            trendingTracks.isEmpty() -> infoCard(column,"Audius indisponível",
                "Confira sua conexão e tente novamente.","RECARREGAR") { loadTrending() }
            else -> addRows(column,trendingTracks.take(5),trendingTracks)
        }
        column.addView(View(this),lp(-1,25))
        section(column,"Sua biblioteca","Ver tudo") { openTab(2) }
        if(localTracks.isEmpty()) {
            infoCard(column,"Arquivos do dispositivo","MP3, FLAC e outros formatos compatíveis.",
                "ABRIR BIBLIOTECA") { openTab(2) }
        } else addRows(column,localTracks.take(4),localTracks)
        column.addView(View(this),lp(-1,23))
        val playlists=libraryDb.playlists().take(3)
        if(playlists.isNotEmpty()) {
            section(column,"Suas playlists","Ver todas") { openTab(3) }
            for(list in playlists) column.addView(actionLabel("☷   "+list.name) {
                selectedPlaylistId=list.id
                openTab(6)
            },lp(-1,48,0,0,0,7))
        }
    }

    private fun searchPage(column: LinearLayout) {
        heading(column, "Streaming gratuito", "Encontre sua próxima música.", "Busque por faixa, álbum ou artista.")
        val input = searchInput("Nome da música ou artista...") { value ->
            if (value == searchText) return@searchInput
            searchText = value
            searchDebounce?.let(handler::removeCallbacks)
            if (value.isBlank()) {
                ++searchRequest
                onlineTracks = emptyList()
                onlineError = null
                onlineLoading = false
                renderSearchResults()
            } else {
                onlineLoading = true
                renderSearchResults()
                val pending = Runnable { performSearch(value) }
                searchDebounce = pending
                handler.postDelayed(pending, 420)
            }
        }
        column.addView(input, lp(-1, 52, 0, 0, 0, 12))
        column.addView(actionLabel("PESQUISAR NO YOUTUBE MUSIC  ↗") {
            openYouTubeMusic(searchText)
        },lp(-1,46,0,0,0,16))
        if (searchText.isNotBlank()) {
            input.setText(searchText)
            input.setSelection(input.length())
        }
        val filters = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val filterRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        for (genre in listOf("Eletrônica", "Rock", "Hip-Hop", "Lo-fi", "Pop")) {
            val chip = text(genre, 12f, light).apply {
                gravity = Gravity.CENTER
                background = shape(card, 30)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                setOnClickListener {
                    input.setText(genre)
                    input.setSelection(input.length())
                }
            }
            filterRow.addView(chip, lp(-2, -2, 0, 0, 8))
        }
        filters.addView(filterRow)
        column.addView(filters, lp(-1, 46, 0, 0, 0, 19))
        searchResults = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(searchResults)
        renderSearchResults()
        val id = jamendoClientId()
        if (id.isBlank()) {
            column.addView(View(this), lp(-1, 22))
            infoCard(column, "Quer ampliar o catálogo?", "O Jamendo também pode ser ativado gratuitamente com um client_id próprio.", "CONFIGURAR JAMENDO") {
                openTab(5)
            }
        }
    }

    private fun renderSearchResults() {
        val target = searchResults ?: return
        target.removeAllViews()
        val query = searchText.trim()
        if(query.isEmpty()) {
            section(target,"Músicas para descobrir","Atualizar") {
                loadTrending()
                loadJamendoPopular()
            }
            val combined=(jamendoPopularTracks.take(12)+trendingTracks.take(12)).distinctBy { it.id }
            if(combined.isNotEmpty()) addRows(target,combined,combined)
            else if(onlineLoading || jamendoLoading) sectionSkeleton(target,4)
            else infoCard(target,"Nenhuma faixa no momento",
                "As fontes online podem estar temporariamente indisponíveis.","TENTAR NOVAMENTE") {
                loadTrending()
                loadJamendoPopular()
            }
            return
        }
        val local=localTracks.filter {
            it.title.contains(query,true) || it.artist.contains(query,true) || it.album.contains(query,true)
        }
        if(local.isNotEmpty()) {
            section(target,"No dispositivo")
            addRows(target,local,local)
            target.addView(View(this),lp(-1,18))
        }
        section(target,"Resultados online")
        if(onlineTracks.isNotEmpty()) addRows(target,onlineTracks,onlineTracks)
        if(onlineLoading) sectionSkeleton(target,if(onlineTracks.isEmpty()) 4 else 2)
        if(!onlineLoading && onlineTracks.isEmpty()) {
            infoCard(target,if(onlineError!=null) "Não foi possível pesquisar" else "Nenhuma música encontrada",
                if(onlineError!=null) "Falha de conexão com os provedores. Tente novamente."
                else "Experimente outro nome ou artista.","PESQUISAR NOVAMENTE") {
                performSearch(query)
            }
        }
    }

    private fun performSearch(query: String) {
        val request=++searchRequest
        val clientId=jamendoClientId()
        val highQuality=getSharedPreferences("nox_settings",MODE_PRIVATE).getBoolean("hq_audio",true)
        searchPendingSources=if(clientId.isNotBlank()) 2 else 1
        onlineLoading=true
        onlineTracks=emptyList()
        onlineError=null
        if(tab==1) renderSearchResults()
        fun handle(result: Result<List<Track>>) {
            runOnUiThread {
                if(isDestroyed || isFinishing || request!=searchRequest || searchText!=query) return@runOnUiThread
                result.onSuccess { found ->
                    onlineTracks=(onlineTracks+found).distinctBy { it.id }
                }.onFailure {
                    onlineError=it.localizedMessage ?: "Fonte indisponível"
                }
                searchPendingSources=(searchPendingSources-1).coerceAtLeast(0)
                onlineLoading=searchPendingSources>0
                if(tab==1) renderSearchResults()
            }
        }
        io.execute { handle(runCatching { audius.search(query) }) }
        if(clientId.isNotBlank()) {
            io.execute { handle(runCatching { JamendoRepository(clientId,highQuality).search(query) }) }
        }
    }

    private fun loadTrending() {
        onlineLoading=true
        if(tab==1 && searchText.isEmpty()) renderSearchResults()
        io.execute {
            val result=runCatching { audius.trending(24) }
            runOnUiThread {
                if(isDestroyed || isFinishing) return@runOnUiThread
                trendingTracks=result.getOrDefault(emptyList())
                if(searchText.isEmpty()) onlineLoading=false
                if(tab==0) renderPage()
                if(tab==1 && searchText.isEmpty()) renderSearchResults()
            }
        }
    }

    private fun loadJamendoPopular() {
        val id=jamendoClientId()
        if(id.isBlank()) {
            jamendoPopularTracks=emptyList()
            jamendoLoading=false
            if(tab==0) renderPage()
            return
        }
        jamendoLoading=true
        jamendoError=null
        io.execute {
            val quality=getSharedPreferences("nox_settings",MODE_PRIVATE).getBoolean("hq_audio",true)
            val result=runCatching { JamendoRepository(id,quality).popular(18) }
            runOnUiThread {
                if(isDestroyed || isFinishing) return@runOnUiThread
                jamendoPopularTracks=result.getOrDefault(emptyList())
                jamendoLoading=false
                jamendoError=result.exceptionOrNull()?.localizedMessage
                if(tab==0) renderPage()
                if(tab==1 && searchText.isEmpty()) renderSearchResults()
            }
        }
    }

    private fun libraryPage(column: LinearLayout) {
        heading(column,"Arquivos offline","Sua biblioteca",
            "${localTracks.size} faixas disponíveis no dispositivo")
        if(!hasAudioPermission()) {
            infoCard(column,"Permitir acesso às músicas",
                "O Android precisa autorizar o acesso aos arquivos de áudio.",
                "PERMITIR") { requestAudioPermission() }
            return
        }
        val prefs=getSharedPreferences("nox_settings",MODE_PRIVATE)
        var order=prefs.getString("local_sort","Título").orEmpty()
        var currentQuery=""
        val rows=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        fun renderFiltered() {
            rows.removeAllViews()
            val filtered=localTracks.filter {
                it.title.contains(currentQuery,true) || it.artist.contains(currentQuery,true) ||
                it.album.contains(currentQuery,true)
            }
            val sorted=when(order) {
                "Artista" -> filtered.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.artist })
                "Duração" -> filtered.sortedByDescending { it.durationMs }
                else -> filtered.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
            }
            if(sorted.isEmpty()) {
                infoCard(rows,"Nenhum áudio encontrado",
                    if(currentQuery.isNotBlank()) "Tente pesquisar por outro nome."
                    else "Adicione músicas ao dispositivo e atualize a biblioteca.",
                    "ATUALIZAR") { loadLocal() }
            } else addRows(rows,sorted,sorted)
        }
        column.addView(searchInput("Pesquisar em suas músicas...") { query ->
            currentQuery=query
            renderFiltered()
        },lp(-1,52,0,0,0,17))
        val control=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        for(label in listOf("Título","Artista","Duração")) {
            val chip=text(label,11.5f,if(order==label) bgColor else light,order==label).apply {
                gravity=Gravity.CENTER
                background=shape(if(order==label) light else card,20)
                setOnClickListener {
                    order=label
                    prefs.edit().putString("local_sort",label).apply()
                    renderPage()
                }
            }
            control.addView(chip,LinearLayout.LayoutParams(0,dp(36),1f).apply { marginEnd=dp(7) })
        }
        column.addView(control,lp(-1,36,0,0,0,18))
        val label=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        label.addView(text("TODAS AS MÚSICAS",10.5f,secondary,true),LinearLayout.LayoutParams(0,-2,1f))
        label.addView(text("↻  Atualizar",12f,light).apply {
            setOnClickListener { loadLocal() }
        })
        column.addView(label,lp(-1,-2,0,0,0,11))
        column.addView(rows)
        renderFiltered()
    }

    private fun radioPage(column: LinearLayout) {
        if (radioRequest == 0 && radios.isEmpty()) loadRadios()
        heading(column, "Streaming gratuito", "Rádio ao vivo", "Estações online. Sem conta ou assinatura.")
        column.addView(searchInput("Buscar estação de rádio...") { query ->
            radioSearch = query
            radioDebounce?.let(handler::removeCallbacks)
            val request = Runnable { loadRadios(query = query, tag = radioTag, brazilOnly = radioBrazil) }
            radioDebounce = request
            handler.postDelayed(request, 550)
        }, lp(-1, 50, 0, 0, 0, 14))

        val chips = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val chipRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val filters = listOf(
            Triple("Brasil", "", true),
            Triple("Pop", "pop", false),
            Triple("Rock", "rock", false),
            Triple("Eletrônica", "electronic", false),
            Triple("Jazz", "jazz", false),
            Triple("Lo-fi", "lofi", false)
        )
        for ((name, tag, brazil) in filters) {
            val selected = radioTag == tag && radioBrazil == brazil
            val chip = text(name, 12f, if (selected) bgColor else secondary, selected).apply {
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(10), dp(16), dp(10))
                this.background = shape(if (selected) accent else card, 50)
                setOnClickListener {
                    radioTag = tag
                    radioBrazil = brazil
                    loadRadios(query = radioSearch, tag = tag, brazilOnly = brazil)
                    renderPage()
                }
            }
            chipRow.addView(chip, lp(-2, -2, 0, 0, 8, 0))
        }
        chips.addView(chipRow)
        column.addView(chips, lp(-1, 46, 0, 0, 0, 16))
        section(column, "Estações disponíveis")
        radioContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(radioContainer)
        renderRadioRows()
        column.addView(text("Rádios são transmissões ao vivo: a estação decide qual música toca.", 11f, secondary), lp(-1, -2, 0, 20))
    }

    private fun renderRadioRows() {
        val target = radioContainer ?: return
        target.removeAllViews()
        if (radioLoading) {
            target.addView(text("Buscando rádios...", 13f, secondary), lp(-1, -2, 0, 12))
            return
        }
        if (radios.isEmpty()) {
            val msg = radioError?.let { "Não foi possível carregar as rádios. Verifique sua internet." }
                ?: "Nenhuma rádio encontrada. Experimente outro filtro."
            target.addView(text(msg, 13f, secondary), lp(-1, -2, 0, 14))
            target.addView(actionLabel("TENTAR NOVAMENTE") {
                loadRadios(query = radioSearch, tag = radioTag, brazilOnly = radioBrazil)
            }, lp(-1, 46))
            return
        }
        addRows(target, radios, radios)
    }

    private fun favoritesPage(column: LinearLayout) {
        val saved = favorites.all()
        heading(column, "Coleção", "Seus favoritos", "${saved.size} faixas e rádios salvas neste aparelho")
        if (saved.isEmpty()) {
            infoCard(column, "Ainda não há favoritos", "Toque no coração de uma música ou rádio para salvá-la aqui.", "EXPLORAR") { openTab(0) }
        } else {
            addRows(column, saved, saved)
        }
    }

    private fun collectionPage(column: LinearLayout) {
        heading(column, "Biblioteca pessoal", "Sua coleção", "Playlists, favoritos, fila e histórico.")
        val shortcuts = listOf(
            Triple("♥  Favoritos", "${favorites.all().size} faixas", 7),
            Triple("☷  Fila de reprodução", "${player?.mediaItemCount ?: activeQueue.size} na fila", 8)
        )
        for ((title, desc, destination) in shortcuts) {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(17), dp(16), dp(17), dp(16))
                background = shape(card, 15)
                setOnClickListener { openTab(destination) }
            }
            box.addView(text(title, 15f, light, true))
            box.addView(text(desc, 12f, secondary), lp(-1,-2,0,6))
            column.addView(box, lp(-1,-2,0,0,0,10))
        }
        column.addView(View(this), lp(-1,14))
        section(column, "Suas playlists", "+ Criar") { promptCreatePlaylist(null) }
        val playlists = libraryDb.playlists()
        if (playlists.isEmpty()) {
            infoCard(column, "Sua primeira playlist", "Crie uma coleção e salve músicas de qualquer fonte.", "NOVA PLAYLIST") { promptCreatePlaylist(null) }
        } else {
            for (list in playlists) {
                val row = LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    background = shape(card, 14)
                    setPadding(dp(15), dp(12), dp(12), dp(12))
                    setOnClickListener {
                        selectedPlaylistId = list.id
                        openTab(6)
                    }
                }
                val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                info.addView(text(list.name, 15f, light, true))
                info.addView(text("${list.count} faixas", 12f, secondary), lp(-1,-2,0,5))
                row.addView(info, LinearLayout.LayoutParams(0,-2,1f))
                row.addView(text("›", 26f, secondary), lp(20,-2))
                column.addView(row, lp(-1,-2,0,0,0,9))
            }
        }
        column.addView(View(this), lp(-1,17))
        section(column, "Reproduzidas recentemente")
        val recent = libraryDb.history(16)
        if (recent.isEmpty()) column.addView(text("O histórico aparece depois que você reproduzir algo.", 12f, secondary))
        else addRows(column, recent, recent)
    }

    private fun promptCreatePlaylist(addTrack: Track?) {
        val field = EditText(this).apply {
            hint = "Nome da playlist"
            setSingleLine(true)
            setTextColor(light)
            setHintTextColor(secondary)
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle("Nova playlist")
            .setView(field)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Criar") { _, _ ->
                val name = field.text.toString().trim()
                if (name.isBlank()) {
                    Toast.makeText(this, "Digite um nome para a playlist.", Toast.LENGTH_SHORT).show()
                } else {
                    val id = libraryDb.createPlaylist(name)
                    addTrack?.let { libraryDb.addToPlaylist(id, it) }
                    Toast.makeText(this, "Playlist criada.", Toast.LENGTH_SHORT).show()
                    if (tab == 3) renderPage()
                    if (tab == 6) {
                        selectedPlaylistId = id
                        renderPage()
                    }
                }
            }.show()
    }

    private fun addToPlaylistDialog(track: Track) {
        val choices = libraryDb.playlists()
        val labels = choices.map { it.name }.toMutableList().apply { add("+ Nova playlist") }
        AlertDialog.Builder(this)
            .setTitle("Adicionar à playlist")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == choices.size) promptCreatePlaylist(track)
                else {
                    libraryDb.addToPlaylist(choices[which].id, track)
                    Toast.makeText(this, "Adicionado a ${choices[which].name}.", Toast.LENGTH_SHORT).show()
                    if (tab == 6) renderPage()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun playlistDetailPage(column: LinearLayout) {
        val selected = libraryDb.playlists().firstOrNull { it.id == selectedPlaylistId }
        if (selected == null) {
            openTab(3)
            return
        }
        column.addView(actionLabel("‹  VOLTAR À COLEÇÃO") { openTab(3) }, lp(-1,46,0,0,0,17))
        heading(column, "Sua playlist", selected.name, "${selected.count} músicas")
        val songs = libraryDb.playlistTracks(selected.id)
        val buttons = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        buttons.addView(actionLabel("▶  REPRODUZIR") {
            if (songs.isNotEmpty()) startTrack(songs, 0)
        }, LinearLayout.LayoutParams(0,dp(48),1f))
        buttons.addView(actionLabel("EXCLUIR") {
            AlertDialog.Builder(this)
                .setTitle("Excluir playlist?")
                .setMessage("As músicas originais não serão apagadas.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Excluir") { _, _ ->
                    libraryDb.deletePlaylist(selected.id)
                    openTab(3)
                }.show()
        }, LinearLayout.LayoutParams(0,dp(48),1f).apply { marginStart = dp(10) })
        column.addView(buttons, lp(-1,48,0,0,0,10))
        column.addView(actionLabel("✎  RENOMEAR PLAYLIST") {
            val field=EditText(this).apply {
                setSingleLine(true)
                setTextColor(light)
                setHintTextColor(secondary)
                setText(selected.name)
                setSelection(text.length)
                setPadding(dp(16),dp(12),dp(16),dp(12))
            }
            AlertDialog.Builder(this).setTitle("Renomear playlist")
                .setView(field)
                .setNegativeButton("Cancelar",null)
                .setPositiveButton("Salvar") { _,_ ->
                    val updatedName=field.text.toString().trim()
                    if(updatedName.isNotBlank()) {
                        libraryDb.renamePlaylist(selected.id,updatedName)
                        renderPage()
                    } else {
                        Toast.makeText(this,"Digite um nome válido.",Toast.LENGTH_SHORT).show()
                    }
                }.show()
        },lp(-1,43,0,0,0,20))
        if (songs.isEmpty()) {
            infoCard(column, "Playlist vazia", "Use o menu ⋮ de qualquer música para adicioná-la.", "BUSCAR MÚSICAS") { openTab(1) }
        } else {
            songs.forEachIndexed { i, track ->
                val row = LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setOnClickListener { startTrack(songs, i) }
                }
                row.addView(artwork(track, 46), lp(46,46,0,0,12))
                val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                info.addView(text(track.title, 13f, light, true).apply { maxLines = 1 })
                info.addView(text(track.artist, 11f, secondary), lp(-1,-2,0,5))
                row.addView(info, LinearLayout.LayoutParams(0,-2,1f))
                row.addView(text("⋮", 26f, secondary).apply {
                    gravity = Gravity.CENTER
                    setOnClickListener {
                        AlertDialog.Builder(this@MainActivity).setTitle(track.title)
                            .setItems(arrayOf("Remover", "Mover para cima", "Mover para baixo", "Adicionar à fila")) { _, action ->
                                when (action) {
                                    0 -> libraryDb.removeFromPlaylist(selected.id, i)
                                    1 -> libraryDb.reorderPlaylist(selected.id, i, (i-1).coerceAtLeast(0))
                                    2 -> libraryDb.reorderPlaylist(selected.id, i, (i+1).coerceAtMost(songs.lastIndex))
                                    3 -> addToQueue(track)
                                }
                                if (action in 0..2) renderPage()
                            }.show()
                    }
                }, lp(36,46))
                column.addView(row, lp(-1,66))
            }
        }
    }

    private fun queuePage(column: LinearLayout) {
        heading(column, "Reprodução", "Fila atual", "Altere a ordem ou adicione músicas.")
        column.addView(actionLabel("‹  VOLTAR") { openTab(3) }, lp(-1,45,0,0,0,14))
        if (activeQueue.isEmpty()) {
            infoCard(column, "Fila vazia", "Escolha uma faixa para começar a ouvir.", "BUSCAR") { openTab(1) }
            return
        }
        val playingIndex = player?.currentMediaItemIndex ?: 0
        activeQueue.toList().forEachIndexed { i, track ->
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setOnClickListener { player?.seekToDefaultPosition(i); player?.play() }
            }
            row.addView(artwork(track, 42), lp(42,42,0,0,10))
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            info.addView(text((if (i == playingIndex) "♪  " else "") + track.title, 13f, light, i == playingIndex).apply { maxLines=1 })
            info.addView(text(track.artist, 11f, secondary), lp(-1,-2,0,5))
            row.addView(info, LinearLayout.LayoutParams(0,-2,1f))
            row.addView(text("⋮", 25f, secondary).apply {
                gravity = Gravity.CENTER
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity).setTitle(track.title)
                        .setItems(arrayOf("Mover para cima", "Mover para baixo", "Remover da fila")) { _, action ->
                            when (action) {
                                0 -> moveQueueItem(i, i-1)
                                1 -> moveQueueItem(i, i+1)
                                2 -> removeQueueItem(i)
                            }
                            renderPage()
                        }.show()
                }
            }, lp(38,46))
            column.addView(row, lp(-1,61))
        }
    }

    private fun moveQueueItem(from: Int, to: Int) {
        val playback = player ?: return
        if (from !in activeQueue.indices || to !in activeQueue.indices || from == to) return
        val changed = activeQueue.toMutableList()
        val item = changed.removeAt(from)
        changed.add(to,item)
        activeQueue = changed
        playback.moveMediaItem(from,to)
        persistQueue()
    }

    private fun removeQueueItem(index: Int) {
        val playback = player ?: return
        if (index !in activeQueue.indices) return
        activeQueue = activeQueue.toMutableList().apply { removeAt(index) }
        playback.removeMediaItem(index)
        persistQueue()
    }

    private fun addToQueue(track: Track) {
        val playback = player ?: return
        if (playback.mediaItemCount == 0 || activeQueue.isEmpty()) {
            startTrack(listOf(track), 0)
        } else {
            playback.addMediaItem(track.mediaItem())
            activeQueue = activeQueue + track
            persistQueue()
            Toast.makeText(this, "Adicionado à fila.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun persistQueue() {
        val items = activeQueue.toList()
        val index = player?.currentMediaItemIndex ?: 0
        stateIo.execute { libraryDb.saveQueue(items,index) }
    }

    private fun openDetail(track: Track, album: Boolean) {
        if (album && track.album.isBlank()) return
        detailTrack = track
        detailAlbum = album
        detailResults = emptyList()
        detailLoading = true
        openTab(9)
        val owner = track
        io.execute {
            val result = runCatching {
                when (owner.source) {
                    Source.AUDIOUS -> if (album) {
                        if (owner.albumId.isBlank()) audius.search(owner.album).filter { it.album == owner.album }
                        else audius.albumTracks(owner.albumId)
                    } else audius.artistTracks(owner.artistId)
                    Source.JAMENDO -> {
                        val prefs = getSharedPreferences("nox_settings", MODE_PRIVATE)
                        val clientId = jamendoClientId()
                        if (clientId.isEmpty()) emptyList()
                        else if (album) JamendoRepository(clientId).albumTracks(owner.albumId)
                        else JamendoRepository(clientId).artistTracks(owner.artistId)
                    }
                    Source.LOCAL -> localTracks.filter { if (album) it.album == owner.album else it.artist == owner.artist }
                    Source.RADIO -> emptyList()
                }
            }.getOrDefault(emptyList())
            runOnUiThread {
                if (isFinishing || isDestroyed || detailTrack?.id != owner.id || detailAlbum != album) return@runOnUiThread
                detailResults = result
                detailLoading = false
                if (tab == 9) renderPage()
            }
        }
    }

    private fun detailPage(column: LinearLayout) {
        val track = detailTrack ?: return
        column.addView(actionLabel("‹  VOLTAR") { openTab(priorTab) }, lp(-1,45,0,0,0,17))
        heading(column, if (detailAlbum) "Álbum" else "Artista",
            if (detailAlbum) track.album else track.artist,
            "${track.providerName} • ${detailResults.size} músicas")
        if (detailLoading) {
            column.addView(text("Carregando faixas...", 13f, secondary))
        } else if (detailResults.isEmpty()) {
            infoCard(column, "Não foi possível carregar mais faixas", "Esse provedor pode não fornecer os dados deste lançamento.", null, null)
        } else {
            column.addView(actionLabel("▶  REPRODUZIR TUDO") {
                startTrack(detailResults,0)
            }, lp(-1,48,0,0,0,15))
            addRows(column, detailResults, detailResults)
        }
    }

    private fun settingsPage(column: LinearLayout) {
        heading(column,"NOX • Preferências","Configurações","Personalize a experiência e gerencie seus dados.")
        val prefs=getSharedPreferences("nox_settings",MODE_PRIVATE)
        section(column,"Visual")
        val animations=LinearLayout(this).apply {
            gravity=Gravity.CENTER_VERTICAL
            setPadding(dp(17),dp(17),dp(17),dp(17))
            background=shape(card,17)
        }
        animations.addView(text("Transições suaves",14f,light,true),LinearLayout.LayoutParams(0,-2,1f))
        val state=text(if(prefs.getBoolean("animations",true)) "LIGADO" else "DESLIGADO",11f,secondary,true)
        animations.addView(state)
        animations.setOnClickListener {
            val enabled=!prefs.getBoolean("animations",true)
            prefs.edit().putBoolean("animations",enabled).apply()
            state.text=if(enabled) "LIGADO" else "DESLIGADO"
        }
        column.addView(animations,lp(-1,-2,0,0,0,22))

        section(column,"Fontes de streaming")
        val enabled=prefs.getBoolean("jamendo_enabled",true)
        val jamendoToggle=LinearLayout(this).apply {
            gravity=Gravity.CENTER_VERTICAL
            background=shape(card,17)
            setPadding(dp(17),dp(17),dp(17),dp(17))
        }
        val names=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        names.addView(text("Jamendo",15f,light,true))
        names.addView(text("Músicas grátis sob demanda",11f,secondary),lp(-1,-2,0,6))
        jamendoToggle.addView(names,LinearLayout.LayoutParams(0,-2,1f))
        val switchState=text(if(enabled) "ATIVO" else "INATIVO",12f,if(enabled) light else secondary,true)
        jamendoToggle.addView(switchState)
        jamendoToggle.setOnClickListener {
            prefs.edit().putBoolean("jamendo_enabled",!enabled).apply()
            if(!enabled) loadJamendoPopular() else {
                jamendoPopularTracks=emptyList()
                jamendoLoading=false
            }
            renderPage()
        }
        column.addView(jamendoToggle,lp(-1,-2,0,0,0,9))
        val configured=prefs.getString("jamendo_id",JamendoRepository.DEFAULT_ID)
            .orEmpty().ifBlank { JamendoRepository.DEFAULT_ID }
        infoCard(column,"Client ID padrão: "+configured,
            "ID fornecido pelo proprietário. Você pode trocar por outro da sua conta Jamendo.",
            "ALTERAR CLIENT ID") {
            val field=EditText(this).apply {
                setSingleLine(true)
                setTextColor(light)
                setHintTextColor(secondary)
                hint="Client ID"
                setText(configured)
                setPadding(dp(16),dp(12),dp(16),dp(12))
            }
            AlertDialog.Builder(this).setTitle("Jamendo • Client ID")
                .setView(field)
                .setNegativeButton("Cancelar",null)
                .setNeutralButton("Restaurar padrão") { _,_ ->
                    prefs.edit().putString("jamendo_id",JamendoRepository.DEFAULT_ID).apply()
                    loadJamendoPopular()
                    renderPage()
                }
                .setPositiveButton("Salvar") { _,_ ->
                    val newId=field.text.toString().trim()
                    if(newId.matches(Regex("[a-zA-Z0-9]{4,64}"))) {
                        prefs.edit().putString("jamendo_id",newId).apply()
                        loadJamendoPopular()
                        renderPage()
                    } else Toast.makeText(this,"Use um client ID válido.",Toast.LENGTH_SHORT).show()
                }.show()
        }
        val high=prefs.getBoolean("hq_audio",true)
        column.addView(actionLabel(if(high) "QUALIDADE DO STREAMING: ALTA" else "QUALIDADE DO STREAMING: ECONÔMICA") {
            prefs.edit().putBoolean("hq_audio",!high).apply()
            renderPage()
        },lp(-1,49,0,0,0,10))
        column.addView(text("O catálogo depende da disponibilidade dos servidores.",11f,secondary),lp(-1,-2,0,5,0,24))

        section(column,"Armazenamento")
        val usage=text("Calculando cache de capas...",12f,secondary)
        column.addView(usage,lp(-1,-2,0,0,0,11))
        io.execute {
            val bytes=runCatching { covers.diskUsageBytes() }.getOrDefault(0L)
            runOnUiThread {
                if(!isFinishing && !isDestroyed && tab==5)
                    usage.text="Capas armazenadas: "+String.format(java.util.Locale.getDefault(),"%.1f MB",bytes/1048576.0)+" / 80 MB"
            }
        }
        column.addView(actionLabel("LIMPAR CACHE DE CAPAS") {
            io.execute {
                val done=runCatching { covers.clear() }
                runOnUiThread {
                    if(!isFinishing && !isDestroyed) {
                        Toast.makeText(this,if(done.isSuccess) "Cache removido." else "Não foi possível limpar o cache.",Toast.LENGTH_SHORT).show()
                        if(tab==5) renderPage()
                    }
                }
            }
        },lp(-1,50,0,0,0,11))
        column.addView(actionLabel("RECARREGAR BIBLIOTECA LOCAL") {
            if(hasAudioPermission()) loadLocal() else requestAudioPermission()
        },lp(-1,50,0,0,0,11))
        column.addView(actionLabel("EXPLORAR RÁDIOS AO VIVO") { openTab(4) },lp(-1,50,0,0,0,21))

        section(column,"YouTube Music")
        infoCard(column,"Catálogo oficial do YouTube Music",
            "A reprodução usa a página oficial. Pode abrir no app do YouTube Music caso o navegador integrado seja bloqueado.",
            "ABRIR YOUTUBE MUSIC") { openYouTubeMusic() }
        section(column,"Sobre")
        column.addView(text("NOX MUSIC  0.4.0 ALPHA",12f,light,true))
        column.addView(text("Android nativo • Audius • Jamendo • arquivos locais",12f,secondary),lp(-1,-2,0,8))
        column.addView(text("Sem anúncios ou conta obrigatória. Nenhuma faixa comercial protegida é desbloqueada.",11f,secondary),lp(-1,-2,0,8))
    }

    private fun infoCard(
        column: LinearLayout, title: String, subtitle: String,
        action: String?, onClick: (() -> Unit)?
    ) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(17), dp(17), dp(17), dp(17))
            background = shape(card, 18)
        }
        box.addView(text(title, 15f, light, true), lp(-1, -2, 0, 0, 0, 7))
        box.addView(text(subtitle, 12f, secondary))
        if (action != null && onClick != null) {
            val link = text(action + "  →", 11f, accent, true).apply {
                setOnClickListener { onClick() }
                setPadding(0, dp(15), 0, dp(3))
            }
            box.addView(link)
        }
        column.addView(box, lp(-1, -2, 0, 0, 0, 9))
    }

    private fun jamendoClientId(): String {
        val prefs = getSharedPreferences("nox_settings", MODE_PRIVATE)
        if (!prefs.getBoolean("jamendo_enabled", true)) return ""
        return prefs.getString("jamendo_id", JamendoRepository.DEFAULT_ID)
            .orEmpty().ifBlank { JamendoRepository.DEFAULT_ID }
    }

    private fun sectionSkeleton(target: LinearLayout, rows: Int = 3) {
        repeat(rows) {
            val skeleton = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(7), dp(4), dp(7))
            }
            skeleton.addView(View(this).apply {
                background = shape(Color.rgb(38,38,38), 12)
            }, lp(49,49,0,0,13))
            val bars = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            bars.addView(View(this).apply { background = shape(Color.rgb(39,39,39),6) },lp(148,12,0,0,0,9))
            bars.addView(View(this).apply { background = shape(Color.rgb(29,29,29),6) },lp(92,8))
            skeleton.addView(bars)
            target.addView(skeleton,lp(-1,65))
        }
    }

    private fun actionLabel(label: String, action: () -> Unit): TextView = text(label, 12f, accent, true).apply {
        gravity = Gravity.CENTER
        background = shape(card, 13)
        setOnClickListener { action() }
    }

    private fun searchInput(hint: String, onQuery: (String) -> Unit): EditText =
        EditText(this).apply {
            setSingleLine(true)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(light)
            setHintTextColor(secondary)
            this.hint = hint
            background = shape(card, 15)
            setPadding(dp(17), 0, dp(15), 0)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) =
                    onQuery(s?.toString()?.trim().orEmpty())
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }

    private fun artwork(track: Track?, size: Int, radius: Int = 13): FrameLayout {
        val isRadio = track?.isLive == true
        val art = FrameLayout(this).apply {
            background = shape(if (isRadio) Color.rgb(41,41,41) else Color.rgb(32,32,32),radius)
            clipToOutline = true
            val symbol = icon(if (isRadio) R.drawable.ic_radio else R.drawable.ic_music,
                min(52,size / 2), Color.rgb(188,188,188))
            addView(symbol,FrameLayout.LayoutParams(dp(min(52,size / 2)),dp(min(52,size / 2)),Gravity.CENTER))
        }
        if (!track?.artworkUrl.isNullOrBlank()) {
            val cover = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = "Capa da música"
            }
            art.addView(cover,FrameLayout.LayoutParams(-1,-1))
            covers.into(track!!.artworkUrl,cover)
        }
        return art
    }

    /**
     * Render only a bounded page instead of creating hundreds of views for a large library.
     * The queue is preserved as a distinct source list.
     */
    private fun addRows(target: LinearLayout, shown: List<Track>, queue: List<Track>) {
        val saved = favorites.all().mapTo(mutableSetOf()) { it.id }
        fun append(from: Int) {
            val end = min(from+35,shown.size)
            for (index in from until end) {
                val track = shown[index]
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(66)
                    setOnClickListener { startTrack(queue,index) }
                }
                row.addView(artwork(track,48),lp(48,48,0,0,12))
                val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                details.addView(text(track.title,13.5f,light,true).apply { maxLines=1 })
                details.addView(text(track.artist + " · " + track.providerName,11f,secondary).apply { maxLines=1 },
                    lp(-1,-2,0,5))
                row.addView(details,LinearLayout.LayoutParams(0,-2,1f))
                val active = saved.contains(track.id)
                val heart = text(if (active) "♥" else "♡",21f,
                    if (active) light else secondary).apply {
                    gravity = Gravity.CENTER
                    contentDescription = "Favoritar"
                    setOnClickListener {
                        val added = favorites.toggle(track)
                        if (added) saved.add(track.id) else saved.remove(track.id)
                        text = if (added) "♥" else "♡"
                        setTextColor(if (added) light else secondary)
                        if (tab == 7) renderPage()
                        syncPlayback()
                    }
                }
                row.addView(heart,lp(32,44,0,0,1))
                row.addView(text("⋮",23f,secondary).apply {
                    gravity = Gravity.CENTER
                    contentDescription = "Mais opções"
                    setOnClickListener { showTrackMenu(track) }
                },lp(27,44))
                target.addView(row,lp(-1,67))
            }
            if (end < shown.size) {
                lateinit var more: TextView
                more = actionLabel("CARREGAR MAIS  (${shown.size-end})  ↓") {
                    target.removeView(more)
                    append(end)
                }
                target.addView(more,lp(-1,46,0,9,0,15))
            }
        }
        append(0)
    }

    private fun showTrackMenu(track: Track) {
        val actions = mutableListOf("Reproduzir agora", "Adicionar à fila", "Adicionar à playlist",
            if (favorites.contains(track.id)) "Remover dos favoritos" else "Adicionar aos favoritos")
        val options = mutableListOf(0,1,2,3)
        if (!track.isLive) {
            actions.add("Ver artista")
            options.add(4)
            if (track.album.isNotBlank()) {
                actions.add("Ver álbum")
                options.add(5)
            }
        }
        AlertDialog.Builder(this).setTitle(track.title)
            .setItems(actions.toTypedArray()) { _, index ->
                when (options[index]) {
                    0 -> startTrack(listOf(track),0)
                    1 -> addToQueue(track)
                    2 -> addToPlaylistDialog(track)
                    3 -> { favorites.toggle(track); if (tab == 7) renderPage(); syncPlayback() }
                    4 -> openDetail(track,false)
                    5 -> openDetail(track,true)
                }
            }
            .setNegativeButton("Cancelar",null)
            .show()
    }

    private fun hasAudioPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33)
            Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestAudioPermission() {
        val permission = if (Build.VERSION.SDK_INT >= 33)
            Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (!hasAudioPermission()) requestPermissions(arrayOf(permission), 101)
        else loadLocal()
    }

    private fun loadLocal() {
        if (!hasAudioPermission()) return
        io.execute {
            val tracks = runCatching { LocalMusicRepository(applicationContext).load() }
                .getOrDefault(emptyList())
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                localTracks = tracks
                if (tab == 0 || tab == 1) renderPage()
            }
        }
    }

    private fun loadRadios(
        query: String = radioSearch, tag: String = radioTag, brazilOnly: Boolean = radioBrazil
    ) {
        val requestId = ++radioRequest
        radioLoading = true
        radioError = null
        if (tab == 4) renderRadioRows()
        io.execute {
            val result = runCatching { RadioRepository().search(query, tag, brazilOnly) }
            runOnUiThread {
                if (isFinishing || isDestroyed || requestId != radioRequest) return@runOnUiThread
                radioLoading = false
                result.onSuccess { radios = it; radioError = null }
                result.onFailure { radios = emptyList(); radioError = it.message ?: "Erro de rede" }
                if (tab == 4) renderRadioRows()
                else if (tab == 0) renderPage()
            }
        }
    }

    private fun connectPlayback() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            try {
                if (isFinishing || isDestroyed) return@addListener
                val connected = future.get()
                player = connected
                connected.addListener(listener)
                val (saved, index) = libraryDb.restoreQueue()
                activeQueue = saved
                if (connected.mediaItemCount == 0 && saved.isNotEmpty()) {
                    connected.setMediaItems(saved.map(Track::mediaItem),index,C.TIME_UNSET)
                    connected.prepare()
                    // Never autoplay after reopening the app.
                }
                syncPlayback()
            } catch (_: Exception) {
                Toast.makeText(this, "Não foi possível iniciar o player.", Toast.LENGTH_LONG).show()
            }
        }, Executor { command -> runOnUiThread(command) })
    }

    private fun startTrack(queue: List<Track>, index: Int) {
        val playback = player
        if (playback == null) {
            Toast.makeText(this, "Player inicializando. Tente novamente.", Toast.LENGTH_SHORT).show()
            return
        }
        if (queue.isEmpty() || index !in queue.indices) return
        // Cap Media3's in-memory queue to 200 items while retaining the selected song.
        val start = (index - 60).coerceAtLeast(0)
        val end = (start + 200).coerceAtMost(queue.size)
        activeQueue = queue.subList(start,end).toList()
        playback.setMediaItems(activeQueue.map(Track::mediaItem), index-start, C.TIME_UNSET)
        playback.prepare()
        playback.play()
        persistQueue()
        syncPlayback()
    }

    private fun togglePlayback() {
        player?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    private fun currentTrack(): Track? {
        val current = player?.currentMediaItem ?: return null
        return activeQueue.firstOrNull { it.id == current.mediaId }
            ?: localTracks.firstOrNull { it.id == current.mediaId }
            ?: radios.firstOrNull { it.id == current.mediaId }
            ?: favorites.all().firstOrNull { it.id == current.mediaId }
            ?: Track(
                id = current.mediaId,
                title = current.mediaMetadata.title?.toString() ?: "Música",
                artist = current.mediaMetadata.artist?.toString() ?: "",
                uri = current.localConfiguration?.uri?.toString().orEmpty(),
                source = if (current.mediaId.startsWith("radio:")) Source.RADIO else Source.LOCAL
            )
    }

    private fun syncPlayback() {
        if (!::mini.isInitialized) return
        val playback = player
        val track = currentTrack()
        miniContainer?.visibility = if (track == null || tab == 10) View.GONE else View.VISIBLE
        if (track == null || playback == null) {
            miniProgress?.visibility = View.INVISIBLE
            return
        }
        if (playback.isPlaying && lastHistoryId != track.id) {
            lastHistoryId = track.id
            stateIo.execute { libraryDb.recordPlayed(track) }
        }
        miniTitle.text = track.title
        miniArtist.text = track.artist + " · " + track.providerName
        miniPlay.setImageResource(if (playback.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        if (miniArtworkId != track.id) {
            miniArtworkId = track.id
            miniArt?.let { mini.removeView(it) }
            miniArt = artwork(track,46,11)
            mini.addView(miniArt,0,lp(46,46,0,0,11))
        }
        playerTitle?.text = track.title
        playerArtist?.text = track.artist + "  ·  " + track.providerName
        playerPlay?.setImageResource(if (playback.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        playerFavorite?.apply {
            val saved = favorites.contains(track.id)
            text = if (saved) "♥" else "♡"
            setTextColor(if (saved) accent else light)
        }
        playerShuffle?.setTextColor(if (playback.shuffleModeEnabled) accent else secondary)
        playerRepeat?.setTextColor(if (playback.repeatMode != Player.REPEAT_MODE_OFF) accent else secondary)
        val duration = playback.duration
        val seekable = !track.isLive && duration > 0 && duration != C.TIME_UNSET
        miniProgress?.apply {
            visibility = if(seekable) View.VISIBLE else View.INVISIBLE
            scaleX = if(seekable) (playback.currentPosition.toFloat() / duration.toFloat()).coerceIn(0f,1f) else 0f
        }
        playerProgress?.apply {
            isEnabled = seekable
            if (!seeking) {
                max = if (seekable) min(duration,Int.MAX_VALUE.toLong()).toInt() else 1000
                progress = if (seekable) playback.currentPosition.coerceIn(0,duration)
                    .coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 0
            }
        }
        playerElapsed?.text = if (seekable) formatTime(playback.currentPosition) else "AO VIVO"
        playerTotal?.text = if (seekable) formatTime(duration) else "∞"
    }

    private fun formatTime(ms: Long): String {
        val total = (ms.coerceAtLeast(0) / 1000)
        return "%d:%02d".format(java.util.Locale.getDefault(), total / 60, total % 60)
    }

    private fun showPlayer() {
        if (overlay != null || currentTrack() == null) return
        val sheet = FrameLayout(this).apply { setBackgroundColor(bgColor) }
        overlay = sheet
        root.addView(sheet, FrameLayout.LayoutParams(-1, -1))
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = true
        }
        sheet.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(25), dp(18), dp(25), dp(30))
            gravity = Gravity.CENTER_HORIZONTAL
        }
        scroll.addView(column, FrameLayout.LayoutParams(-1, -1))
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val down = icon(R.drawable.ic_down, 25, light).apply {
            contentDescription = "Fechar player"
            setOnClickListener { closePlayer() }
        }
        top.addView(down, lp(30, 30))
        val title = text("OUVINDO AGORA", 11f, accent, true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.16f
        }
        top.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(View(this), lp(30, 30))
        column.addView(top, lp(-1, 36, 0, 0, 0, 28))

        val widthDp = (resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt()
        val artSize = min(302, (widthDp - 66).coerceAtLeast(155))
        val bigArt = artwork(currentTrack(), artSize, 28)
        column.addView(bigArt, lp(artSize, artSize, 0, 0, 0, 31))

        val details = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        playerTitle = text("", 25f, light, true).apply { maxLines = 2 }
        playerArtist = text("", 14f, secondary).apply { maxLines = 1 }
        words.addView(playerTitle)
        words.addView(playerArtist, lp(-1, -2, 0, 8))
        details.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        playerFavorite = text("♡", 30f, light).apply {
            gravity = Gravity.CENTER
            contentDescription = "Alternar favorito"
            setOnClickListener {
                currentTrack()?.let { favorites.toggle(it) }
                syncPlayback()
                if (tab == 7) renderPage()
            }
        }
        details.addView(playerFavorite, lp(42, 48))
        column.addView(details, lp(-1, -2, 0, 0, 0, 25))

        playerProgress = SeekBar(this).apply {
            progressTintList = ColorStateList.valueOf(accent)
            thumbTintList = ColorStateList.valueOf(accent)
            progressBackgroundTintList = ColorStateList.valueOf(surface)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) playerElapsed?.text = formatTime(progress.toLong())
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) { seeking = true }
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    seekBar?.let { player?.seekTo(it.progress.toLong()) }
                    seeking = false
                    syncPlayback()
                }
            })
        }
        column.addView(playerProgress, lp(-1, 35))
        val times = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        playerElapsed = text("0:00", 11f, secondary)
        playerTotal = text("0:00", 11f, secondary)
        times.addView(playerElapsed, LinearLayout.LayoutParams(0, -2, 1f))
        times.addView(playerTotal)
        column.addView(times, lp(-1, -2, 2, 0, 2, 20))

        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER }
        playerShuffle = text("⇄", 26f, secondary, true).apply {
            gravity = Gravity.CENTER
            contentDescription = "Aleatório"
            setOnClickListener { player?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }; syncPlayback() }
        }
        controls.addView(playerShuffle, LinearLayout.LayoutParams(0, dp(55), 1f))
        val previous = icon(R.drawable.ic_previous, 27, light).apply {
            contentDescription = "Anterior"
            setOnClickListener { player?.seekToPreviousMediaItem() }
        }
        val prevBox = FrameLayout(this).apply {
            addView(previous, FrameLayout.LayoutParams(dp(27), dp(27), Gravity.CENTER))
            setOnClickListener { player?.seekToPreviousMediaItem() }
        }
        controls.addView(prevBox, LinearLayout.LayoutParams(0, dp(62), 1f))

        val playBox = FrameLayout(this).apply {
            background = shape(accent, 100)
            setOnClickListener { togglePlayback() }
        }
        playerPlay = icon(R.drawable.ic_play, 31, bgColor)
        playBox.addView(playerPlay, FrameLayout.LayoutParams(dp(31), dp(31), Gravity.CENTER))
        controls.addView(playBox, lp(68, 68, 5, 0, 5))

        val next = icon(R.drawable.ic_next, 27, light).apply {
            contentDescription = "Próxima"
            setOnClickListener { player?.seekToNextMediaItem() }
        }
        val nextBox = FrameLayout(this).apply {
            addView(next, FrameLayout.LayoutParams(dp(27), dp(27), Gravity.CENTER))
            setOnClickListener { player?.seekToNextMediaItem() }
        }
        controls.addView(nextBox, LinearLayout.LayoutParams(0, dp(62), 1f))
        playerRepeat = text("↻", 27f, secondary, true).apply {
            gravity = Gravity.CENTER
            contentDescription = "Repetição"
            setOnClickListener {
                player?.let {
                    it.repeatMode = when (it.repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                }
                syncPlayback()
            }
        }
        controls.addView(playerRepeat, LinearLayout.LayoutParams(0, dp(55), 1f))
        column.addView(controls, lp(-1, 73, 0, 0, 0, 20))
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        actions.addView(actionLabel("☷  FILA") {
            closePlayer(immediate = true)
            openTab(8)
        }, LinearLayout.LayoutParams(0,dp(46),1f))
        actions.addView(actionLabel("+  PLAYLIST") {
            currentTrack()?.let { addToPlaylistDialog(it) }
        }, LinearLayout.LayoutParams(0,dp(46),1f).apply { marginStart = dp(10) })
        column.addView(actions,lp(-1,46,0,0,0,18))
        val info = currentTrack()
        if (info != null && !info.isLive) {
            val detailBar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            detailBar.addView(actionLabel("VER ARTISTA") {
                closePlayer(immediate = true)
                openDetail(info,false)
            },LinearLayout.LayoutParams(0,dp(42),1f))
            if (info.album.isNotBlank()) {
                detailBar.addView(actionLabel("VER ÁLBUM") {
                    closePlayer(immediate = true)
                    openDetail(info,true)
                },LinearLayout.LayoutParams(0,dp(42),1f).apply { marginStart = dp(10) })
            }
            column.addView(detailBar,lp(-1,42,0,0,0,15))
        }
        column.addView(text("N O X   M U S I C   •   0.4.0", 10f, secondary, true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
        }, lp(-1, -2, 0, 2))
        syncPlayback()
        val animations = getSharedPreferences("nox_settings", MODE_PRIVATE).getBoolean("animations", true)
        if (animations) {
            sheet.alpha = 0f
            sheet.translationY = dp(40).toFloat()
            sheet.animate().alpha(1f).translationY(0f).setDuration(210)
                .setInterpolator(DecelerateInterpolator()).start()
        }
    }

    private fun closePlayer(immediate: Boolean = false) {
        val sheet = overlay ?: return
        overlay = null
        playerTitle = null
        playerArtist = null
        playerPlay = null
        playerProgress = null
        playerElapsed = null
        playerTotal = null
        playerFavorite = null
        playerRepeat = null
        playerShuffle = null
        if (immediate || !getSharedPreferences("nox_settings", MODE_PRIVATE).getBoolean("animations", true)) {
            root.removeView(sheet)
        } else {
            sheet.animate().alpha(0f).translationY(dp(35).toFloat()).setDuration(180)
                .withEndAction { root.removeView(sheet) }.start()
        }
    }
}
