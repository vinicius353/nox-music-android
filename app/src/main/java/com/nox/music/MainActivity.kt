package com.nox.music

import android.Manifest
import android.app.Activity
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
import com.nox.music.data.LocalMusicRepository
import com.nox.music.data.RadioRepository
import com.nox.music.audio.PlaybackService
import com.nox.music.model.Source
import com.nox.music.model.Track
import java.util.concurrent.Executors
import java.util.concurrent.Executor
import kotlin.math.min

class MainActivity : Activity() {
    private val bgColor = Color.rgb(8, 9, 12)
    private val card = Color.rgb(23, 25, 31)
    private val surface = Color.rgb(32, 34, 42)
    private val light = Color.rgb(246, 244, 250)
    private val secondary = Color.rgb(151, 149, 160)
    private val accent = Color.rgb(214, 195, 255)
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(2)

    private lateinit var favorites: FavoritesStore
    private lateinit var root: FrameLayout
    private lateinit var content: FrameLayout
    private lateinit var nav: LinearLayout
    private lateinit var mini: LinearLayout
    private lateinit var miniTitle: TextView
    private lateinit var miniArtist: TextView
    private lateinit var miniPlay: ImageView

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
        @Suppress("DEPRECATION")
        window.statusBarColor = bgColor
        @Suppress("DEPRECATION")
        window.navigationBarColor = bgColor
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = 0
        buildShell()
        connectPlayback()
        if (hasAudioPermission()) loadLocal() else requestAudioPermission()
        loadRadios()
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
        val tagline = text("M U S I C", 8.5f, accent, true)
        brand.addView(tagline, lp(-1, -2, 0, 2))
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        val settings = icon(R.drawable.ic_settings, 23, secondary).apply {
            contentDescription = "Configurações"
            setPadding(dp(1), dp(1), dp(1), dp(1))
            setOnClickListener { openTab(4) }
        }
        header.addView(settings, lp(28, 28))
        column.addView(header, lp(-1, 68))

        content = FrameLayout(this)
        column.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        mini = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = gradient(Color.rgb(37, 37, 47), Color.rgb(25, 26, 34), radius = 16)
            setPadding(dp(10), dp(7), dp(10), dp(7))
            visibility = View.GONE
            setOnClickListener { showPlayer() }
        }
        mini.addView(artwork(null, 46, 12), lp(46, 46, 0, 0, 11))
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
            setPadding(dp(12), dp(7), dp(12), dp(8))
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
            Triple("Biblioteca", R.drawable.ic_library, 1),
            Triple("Rádio", R.drawable.ic_radio, 2),
            Triple("Favoritos", R.drawable.ic_heart, 3)
        )
        for ((label, drawable, index) in entries) {
            val selected = tab == index
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = if (selected) shape(card, 14) else shape(bgColor, 14)
                contentDescription = label
                setOnClickListener { openTab(index) }
            }
            cell.addView(icon(drawable, 21, if (selected) accent else secondary), lp(21, 21))
            val name = text(label, 10f, if (selected) accent else secondary, selected)
            name.gravity = Gravity.CENTER
            cell.addView(name, lp(-1, -2, 0, 5))
            nav.addView(cell, LinearLayout.LayoutParams(0, -1, 1f).apply {
                marginStart = dp(2); marginEnd = dp(2)
            })
        }
    }

    private fun openTab(index: Int) {
        if (overlay != null) closePlayer(immediate = true)
        tab = index
        radioContainer = null
        renderPage()
        renderNav()
    }

    private fun renderPage() {
        if (!::content.isInitialized) return
        content.removeAllViews()
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
            1 -> libraryPage(column)
            2 -> radioPage(column)
            3 -> favoritesPage(column)
            else -> settingsPage(column)
        }
        if (getSharedPreferences("nox_settings", MODE_PRIVATE).getBoolean("animations", true)) {
            scroll.alpha = 0f
            scroll.translationY = dp(13).toFloat()
            scroll.animate().alpha(1f).translationY(0f).setDuration(260)
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
        heading(column, "Seu espaço", "Escute do seu jeito.", "Tudo o que você gosta, sem distrações.")
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = gradient(Color.rgb(64, 48, 96), Color.rgb(24, 26, 39))
            setPadding(dp(20), dp(21), dp(20), dp(20))
            setOnClickListener { openTab(2) }
        }
        hero.addView(text("NOX RADIO  /  AO VIVO", 10f, accent, true), lp(-1, -2, 0, 0, 0, 10))
        hero.addView(text("Descubra novos sons.", 24f, light, true))
        hero.addView(text("Estações gratuitas de vários estilos e países.", 12f, Color.rgb(208, 198, 225)), lp(-1, -2, 0, 8, 0, 18))
        val radioCta = text("EXPLORAR RÁDIOS  →", 12f, light, true).apply { letterSpacing = 0.07f }
        hero.addView(radioCta)
        column.addView(hero, lp(-1, -2, 0, 0, 0, 28))

        section(column, "No seu dispositivo", "Ver todas") { openTab(1) }
        if (hasAudioPermission() && localTracks.isNotEmpty()) {
            addRows(column, localTracks.take(4), localTracks)
        } else {
            infoCard(column,
                if (!hasAudioPermission()) "Permita o acesso à sua música" else "Sua biblioteca está vazia",
                if (!hasAudioPermission()) "Encontre faixas MP3, FLAC e M4A do celular."
                else "Adicione arquivos de áudio ao seu dispositivo para vê-los aqui.",
                if (!hasAudioPermission()) "PERMITIR" else "ABRIR BIBLIOTECA"
            ) {
                if (!hasAudioPermission()) requestAudioPermission() else openTab(1)
            }
        }
        column.addView(View(this), lp(-1, 22))
        section(column, "Rádios populares", "Ver todas") { openTab(2) }
        when {
            radioLoading && radios.isEmpty() -> column.addView(text("Buscando estações ao vivo...", 13f, secondary))
            radios.isEmpty() -> infoCard(column, "Sem rádios por enquanto", "Confira sua conexão ou tente atualizar.", "TENTAR NOVAMENTE") { loadRadios() }
            else -> addRows(column, radios.take(5), radios)
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
            val chip = text(name, 12f, if (selected) background else secondary, selected).apply {
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

    private fun settingsPage(column: LinearLayout) {
        heading(column, "Preferências", "Configurações", "NOX Music • versão 0.1.0 Alpha")
        section(column, "Reprodução")
        infoCard(column, "Áudio em segundo plano", "Notificação de mídia, controle por fones e reprodução com a tela desligada.", null, null)
        val prefs = getSharedPreferences("nox_settings", MODE_PRIVATE)
        val motion = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = shape(card, 16)
        }
        motion.addView(text("Animações de entrada", 14f, light, true), LinearLayout.LayoutParams(0, -2, 1f))
        val status = text(if (prefs.getBoolean("animations", true)) "ATIVAS" else "DESLIGADAS", 11f, accent, true)
        motion.addView(status)
        motion.setOnClickListener {
            val enabled = !prefs.getBoolean("animations", true)
            prefs.edit().putBoolean("animations", enabled).apply()
            status.text = if (enabled) "ATIVAS" else "DESLIGADAS"
        }
        column.addView(motion, lp(-1, -2, 0, 0, 0, 18))
        section(column, "Biblioteca")
        column.addView(actionLabel("RECARREGAR MÚSICAS LOCAIS") {
            if (hasAudioPermission()) {
                loadLocal()
                Toast.makeText(this, "Atualizando biblioteca...", Toast.LENGTH_SHORT).show()
            } else requestAudioPermission()
        }, lp(-1, 54, 0, 0, 0, 14))
        column.addView(text("Este aplicativo não inclui publicidade, coleta de dados nem áudio protegido. As rádios pertencem aos seus respectivos operadores.", 12f, secondary), lp(-1, -2, 0, 15))
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
        return FrameLayout(this).apply {
            background = if (isRadio)
                gradient(Color.rgb(102, 75, 132), Color.rgb(40, 35, 69), radius = radius)
            else gradient(Color.rgb(62, 63, 81), Color.rgb(29, 34, 43), radius = radius)
            val graphic = icon(if (isRadio) R.drawable.ic_radio else R.drawable.ic_music,
                min(64, size / 2), if (isRadio) accent else light)
            addView(graphic, FrameLayout.LayoutParams(dp(min(64, size / 2)),
                dp(min(64, size / 2)), Gravity.CENTER))
        }
    }

    /**
     * Render a small, bounded page of rows. A large music library must not create
     * thousands of Android Views at once on a low-memory phone.
     */
    private fun addRows(target: LinearLayout, shown: List<Track>, queue: List<Track>) {
        val savedIds = favorites.all().mapTo(mutableSetOf()) { it.id }

        fun append(from: Int) {
            val end = min(from + 45, shown.size)
            for (index in from until end) {
                val track = shown[index]
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(67)
                    setOnClickListener { startTrack(queue, index) }
                }
                row.addView(artwork(track, 50), lp(50, 50, 0, 0, 12))
                val metadata = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                metadata.addView(text(track.title, 13.5f, light, true).apply { maxLines = 1 })
                metadata.addView(text(track.artist, 11.5f, secondary).apply { maxLines = 1 },
                    lp(-1, -2, 0, 5))
                row.addView(metadata, LinearLayout.LayoutParams(0, -2, 1f))
                val isSaved = savedIds.contains(track.id)
                val heart = text(if (isSaved) "♥" else "♡",
                    22f, if (isSaved) accent else secondary)
                heart.gravity = Gravity.CENTER
                heart.contentDescription = "Adicionar ou remover favorito"
                heart.setOnClickListener {
                    val added = favorites.toggle(track)
                    if (added) savedIds.add(track.id) else savedIds.remove(track.id)
                    heart.text = if (added) "♥" else "♡"
                    heart.setTextColor(if (added) accent else secondary)
                    syncPlayback()
                    if (tab == 3) renderPage()
                }
                row.addView(heart, lp(39, 48, 7))
                target.addView(row, lp(-1, 70))
            }
            if (end < shown.size) {
                lateinit var more: TextView
                more = actionLabel("CARREGAR MAIS  (${shown.size - end})  ↓") {
                    target.removeView(more)
                    append(end)
                }
                target.addView(more, lp(-1, 50, 0, 8, 0, 15))
            }
        }
        append(0)
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
        if (tab == 2) renderRadioRows()
        io.execute {
            val result = runCatching { RadioRepository().search(query, tag, brazilOnly) }
            runOnUiThread {
                if (isFinishing || isDestroyed || requestId != radioRequest) return@runOnUiThread
                radioLoading = false
                result.onSuccess { radios = it; radioError = null }
                result.onFailure { radios = emptyList(); radioError = it.message ?: "Erro de rede" }
                if (tab == 2) renderRadioRows()
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
                if (tab == 3) renderPage()
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
        column.addView(text("NOX MUSIC  •  0.1.0 ALPHA", 10f, secondary, true).apply {
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
