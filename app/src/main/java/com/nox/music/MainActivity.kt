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

    private lateinit var favorites: FavoritesStore
    private lateinit var libraryDb: MusicDatabase
    private val audius = AudiusRepository()
    private val covers = CoverLoader()
    private var trendingTracks: List<Track> = emptyList()
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
    }
    private val ticker = object : Runnable {
        override fun run() {
            syncPlayback()
            handler.postDelayed(this, 750)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player?.removeListener(listener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        io.shutdownNow()
        covers.shutdown()
        libraryDb.close()
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
        val mark = TextView(this).apply {
            text = "N"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(bgColor)
            gravity = Gravity.CENTER
            background = shape(accent, 13)
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
        column.addView(mini, lp(-1, 64, 12, 1, 12, 2))

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

    private fun renderNav() {
        nav.removeAllViews()
        val entries = listOf(
            Triple("Início", R.drawable.ic_home, 0),
            Triple("Buscar", R.drawable.ic_search, 1),
            Triple("Biblioteca", R.drawable.ic_library, 2),
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
        heading(column, "Bem-vindo ao NOX", "Sua música. Seu espaço.", "Ouça faixas reais, sem depender de rádio.")
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(Color.rgb(20, 20, 20), 19)
            setPadding(dp(16), dp(16), dp(13), dp(16))
            setOnClickListener { openTab(1) }
        }
        hero.addView(artwork(trendingTracks.firstOrNull(), 76, 15), lp(76, 76, 0, 0, 14))
        val heroWords = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        heroWords.addView(text("DESCUBRA", 10f, secondary, true))
        heroWords.addView(text("Uma música para agora.", 18f, light, true), lp(-1,-2,0,5))
        heroWords.addView(text("Buscar músicas  →", 12f, light), lp(-1,-2,0,8))
        hero.addView(heroWords, LinearLayout.LayoutParams(0,-2,1f))
        column.addView(hero, lp(-1,-2,0,0,0,26))
        section(column, "Em alta no Audius", "Ver mais") { openTab(1) }
        when {
            onlineLoading && trendingTracks.isEmpty() ->
                column.addView(text("Carregando novidades...", 13f, secondary), lp(-1,-2,0,8))
            trendingTracks.isEmpty() ->
                infoCard(column, "Catálogo não disponível", "Confira a conexão e tente novamente.", "ATUALIZAR") { loadTrending() }
            else -> addRows(column, trendingTracks.take(6), trendingTracks)
        }
        column.addView(View(this), lp(-1,20))
        section(column, "Ouvidas recentemente", "Histórico") { openTab(3) }
        val recent = libraryDb.history(4)
        if (recent.isEmpty()) column.addView(text("As músicas reproduzidas aparecerão aqui.", 12f, secondary))
        else addRows(column, recent, recent)
        column.addView(View(this), lp(-1,24))
        section(column, "No dispositivo", "Biblioteca") { openTab(2) }
        if (localTracks.isEmpty()) {
            infoCard(column, "Suas músicas locais", "Áudio do celular, sem conexão.", "ABRIR BIBLIOTECA") { openTab(2) }
        } else addRows(column, localTracks.take(4), localTracks)
        column.addView(View(this), lp(-1,23))
        section(column, "Rádio ao vivo", "Explorar") { openTab(4) }
        column.addView(text("Estações gratuitas continuam disponíveis como opção secundária.", 12f, secondary))
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
        column.addView(input, lp(-1, 52, 0, 0, 0, 16))
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
        val id = getSharedPreferences("nox_settings", MODE_PRIVATE).getString("jamendo_id", "").orEmpty()
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
        if (query.isEmpty()) {
            section(target, "Em alta no Audius", "Atualizar") { loadTrending() }
            if (onlineLoading && trendingTracks.isEmpty()) {
                target.addView(text("Carregando faixas...", 13f, secondary))
            } else if (trendingTracks.isEmpty()) {
                infoCard(target, "Sem novidades no momento", "A conexão pode estar indisponível.", "TENTAR NOVAMENTE") { loadTrending() }
            } else addRows(target, trendingTracks, trendingTracks)
            return
        }
        val local = localTracks.filter { it.title.contains(query, true) || it.artist.contains(query, true) }
        if (local.isNotEmpty()) {
            section(target, "No seu dispositivo")
            addRows(target, local, local)
            target.addView(View(this), lp(-1, 16))
        }
        section(target, "Músicas online")
        if (onlineLoading) {
            target.addView(text("Pesquisando nos catálogos...", 13f, secondary), lp(-1, -2, 0, 9))
        } else if (onlineTracks.isEmpty()) {
            target.addView(text(
                if (onlineError != null) "Não foi possível consultar o catálogo. Tente novamente."
                else "Nenhuma faixa encontrada para essa pesquisa.",
                13f, secondary
            ), lp(-1, -2, 0, 11))
        } else addRows(target, onlineTracks, onlineTracks)
    }

    private fun performSearch(query: String) {
        val id = ++searchRequest
        val jamendoId = getSharedPreferences("nox_settings", MODE_PRIVATE).getString("jamendo_id", "").orEmpty()
        val highQuality = getSharedPreferences("nox_settings", MODE_PRIVATE).getBoolean("hq_audio", true)
        io.execute {
            val a = runCatching { audius.search(query) }
            val j = if (jamendoId.isBlank()) null
                else runCatching { JamendoRepository(jamendoId, highQuality).search(query) }
            val tracks = (a.getOrDefault(emptyList()) + (j?.getOrDefault(emptyList()) ?: emptyList()))
                .distinctBy { it.id }
            val unavailable = a.isFailure && (j == null || j.isFailure)
            runOnUiThread {
                if (isFinishing || isDestroyed || id != searchRequest || searchText != query) return@runOnUiThread
                onlineLoading = false
                onlineTracks = tracks
                onlineError = if (unavailable) (a.exceptionOrNull()?.message ?: "Falha de rede") else null
                if (tab == 1) renderSearchResults()
            }
        }
    }

    private fun loadTrending() {
        onlineLoading = true
        if (tab == 1) renderSearchResults()
        io.execute {
            val result = runCatching { audius.trending(36) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                trendingTracks = result.getOrDefault(emptyList())
                if (searchText.isEmpty()) onlineLoading = false
                if (tab == 0) renderPage()
                if (tab == 1 && searchText.isEmpty()) renderSearchResults()
            }
        }
    }

    private fun libraryPage(column: LinearLayout) {
        heading(column, "Offline", "Sua biblioteca", "${localTracks.size} músicas disponíveis no dispositivo")
        if (!hasAudioPermission()) {
            infoCard(column, "Acesso às músicas", "O Android precisa autorizar a leitura dos seus arquivos de áudio.", "PERMITIR ACESSO") { requestAudioPermission() }
            return
        }
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val search = searchInput("Buscar em suas músicas...") { query ->
            val filtered = localTracks.filter { it.title.contains(query, true) || it.artist.contains(query, true) }
            rows.removeAllViews()
            if (filtered.isEmpty()) rows.addView(text("Nenhuma música encontrada.", 13f, secondary), lp(-1, -2, 0, 12))
            else addRows(rows, filtered, filtered)
        }
        column.addView(search, lp(-1, 50, 0, 0, 0, 16))
        val sub = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        sub.addView(text("TODAS AS FAIXAS", 11f, accent, true), LinearLayout.LayoutParams(0, -2, 1f))
        sub.addView(text("↻  Atualizar", 12f, secondary).apply {
            setOnClickListener { loadLocal() }
        })
        column.addView(sub, lp(-1, -2, 0, 0, 0, 13))
        column.addView(rows)
        if (localTracks.isEmpty()) rows.addView(text("Nenhum arquivo de áudio encontrado.", 13f, secondary), lp(-1, -2, 0, 10))
        else addRows(rows, localTracks, localTracks)
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
                    if (tab == 7) renderPage()
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
        column.addView(buttons, lp(-1,48,0,0,0,18))
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
        io.execute { libraryDb.saveQueue(items,index) }
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
                        val clientId = prefs.getString("jamendo_id", "").orEmpty()
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
        heading(column, "Personalização", "Configurações", "NOX Music • versão 0.2.0 Alpha")
        val prefs = getSharedPreferences("nox_settings", MODE_PRIVATE)
        section(column, "Aparência")
        val motion = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = shape(card, 15)
            setPadding(dp(17),dp(16),dp(17),dp(16))
        }
        motion.addView(text("Animações de entrada", 14f, light, true), LinearLayout.LayoutParams(0,-2,1f))
        val state = text(if (prefs.getBoolean("animations", true)) "ATIVAS" else "DESLIGADAS",11f,secondary,true)
        motion.addView(state)
        motion.setOnClickListener {
            val enabled = !prefs.getBoolean("animations",true)
            prefs.edit().putBoolean("animations",enabled).apply()
            state.text = if (enabled) "ATIVAS" else "DESLIGADAS"
        }
        column.addView(motion,lp(-1,-2,0,0,0,19))
        section(column, "Streaming")
        val hasJamendo = !prefs.getString("jamendo_id","").isNullOrBlank()
        infoCard(column, "Jamendo • acesso opcional",
            if (hasJamendo) "Fonte Jamendo configurada. Músicas completas e oficiais." else "Para ativar, obtenha gratuitamente um client_id no portal Jamendo.",
            if (hasJamendo) "ALTERAR CLIENT ID" else "CONFIGURAR CLIENT ID") {
            val field = EditText(this).apply {
                setSingleLine(true)
                setTextColor(light)
                setHintTextColor(secondary)
                hint = "client_id do Jamendo"
                setText(prefs.getString("jamendo_id", ""))
                setPadding(dp(16),dp(12),dp(16),dp(12))
            }
            AlertDialog.Builder(this)
                .setTitle("Jamendo client_id")
                .setView(field)
                .setMessage("Use apenas a credencial gratuita da sua aplicação. Deixe vazio para desativar.")
                .setNegativeButton("Cancelar",null)
                .setPositiveButton("Salvar") { _,_ ->
                    val id = field.text.toString().trim()
                    if (id.isEmpty() || id.matches(Regex("[a-zA-Z0-9]{4,64}"))) {
                        prefs.edit().putString("jamendo_id",id).apply()
                        if (searchText.isNotEmpty()) performSearch(searchText)
                        renderPage()
                    } else Toast.makeText(this,"Client ID inválido.",Toast.LENGTH_SHORT).show()
                }.show()
        }
        column.addView(text("Portal oficial: developer.jamendo.com",11f,secondary),lp(-1,-2,0,5,0,17))
        val quality = actionLabel(
            if (prefs.getBoolean("hq_audio",true)) "QUALIDADE JAMENDO: ALTA" else "QUALIDADE JAMENDO: ECONÔMICA"
        ) {
            val enabled = !prefs.getBoolean("hq_audio",true)
            prefs.edit().putBoolean("hq_audio",enabled).apply()
            renderPage()
        }
        column.addView(quality,lp(-1,51,0,0,0,18))
        section(column, "Biblioteca")
        column.addView(actionLabel("RECARREGAR ARQUIVOS LOCAIS") {
            if (hasAudioPermission()) {
                loadLocal()
                Toast.makeText(this, "Atualizando biblioteca...", Toast.LENGTH_SHORT).show()
            } else requestAudioPermission()
        },lp(-1,49,0,0,0,10))
        column.addView(actionLabel("ABRIR RÁDIO AO VIVO") { openTab(4) },lp(-1,49,0,0,0,19))
        section(column, "Sobre")
        column.addView(text("NOX Music • player Android independente. Sem anúncios, sem conta obrigatória e sem coleta de dados.",12f,secondary))
        column.addView(text("Músicas online são fornecidas por seus respectivos provedores e podem não estar disponíveis em todas as regiões.",11f,secondary),lp(-1,-2,0,11))
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
                player = future.get().apply { addListener(listener) }
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
        activeQueue = queue.toList()
        playback.setMediaItems(activeQueue.map(Track::mediaItem), index, C.TIME_UNSET)
        playback.prepare()
        playback.play()
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
        mini.visibility = if (track == null) View.GONE else View.VISIBLE
        if (track == null || playback == null) return
        miniTitle.text = track.title
        miniArtist.text = track.artist
        miniPlay.setImageResource(if (playback.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        playerTitle?.text = track.title
        playerArtist?.text = track.artist
        playerPlay?.setImageResource(if (playback.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        playerFavorite?.apply {
            val saved = favorites.contains(track.id)
            text = if (saved) "♥" else "♡"
            setTextColor(if (saved) accent else light)
        }
        playerShuffle?.setTextColor(if (playback.shuffleModeEnabled) accent else secondary)
        playerRepeat?.setTextColor(if (playback.repeatMode != Player.REPEAT_MODE_OFF) accent else secondary)
        val duration = playback.duration
        val isSeekable = !track.isLive && duration > 0 && duration != C.TIME_UNSET
        playerProgress?.apply {
            isEnabled = isSeekable
            if (!seeking) {
                max = if (isSeekable) min(duration, Int.MAX_VALUE.toLong()).toInt() else 1000
                progress = if (isSeekable) playback.currentPosition.coerceIn(0, duration).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 0
            }
        }
        playerElapsed?.text = if (isSeekable) formatTime(playback.currentPosition) else "AO VIVO"
        playerTotal?.text = if (isSeekable) formatTime(duration) else "∞"
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
        val title = text("REPRODUZINDO AGORA", 11f, accent, true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.16f
        }
        top.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(View(this), lp(30, 30))
        column.addView(top, lp(-1, 36, 0, 0, 0, 28))

        val widthDp = (resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt()
        val artSize = min(270, (widthDp - 84).coerceAtLeast(160))
        val bigArt = artwork(currentTrack(), artSize, 28)
        column.addView(bigArt, lp(artSize, artSize, 0, 0, 0, 27))

        val details = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        playerTitle = text("", 24f, light, true).apply { maxLines = 2 }
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
        details.addView(playerFavorite, lp(48, 48))
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
        column.addView(controls, lp(-1, 73, 0, 0, 0, 24))
        column.addView(text("NOX MUSIC  •  0.2.0 ALPHA", 10f, secondary, true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
        }, lp(-1, -2, 0, 2))
        syncPlayback()
        val animations = getSharedPreferences("nox_settings", MODE_PRIVATE).getBoolean("animations", true)
        if (animations) {
            sheet.alpha = 0f
            sheet.translationY = dp(40).toFloat()
            sheet.animate().alpha(1f).translationY(0f).setDuration(260)
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
