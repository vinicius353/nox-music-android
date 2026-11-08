package com.nox.music.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

/**
 * NOX's separate, first-party YouTube Music website surface.
 *
 * This is NOT a replacement for an official streaming SDK: playback is performed
 * by Google's own site, not through Media3, downloaded streams, or an extractor.
 * WebView instances are destroyed when leaving the surface so the YouTube player
 * cannot keep playing invisibly.
 */
class YouTubeMusicPanel(private val activity: Activity) {
    companion object {
        const val HOME = YouTubeMusicUrl.HOME

        fun destination(query: String?): String = YouTubeMusicUrl.destination(query)

        fun isYouTubeMusic(url: Uri): Boolean = YouTubeMusicUrl.isTrustedSite(url.toString())
    }

    private val bg = Color.rgb(6, 6, 6)
    private val panelBg = Color.rgb(20, 20, 20)
    private val primary = Color.rgb(246, 246, 246)
    private val muted = Color.rgb(153, 153, 153)
    private var browser: WebView? = null
    private var holder: FrameLayout? = null
    private var loading: ProgressBar? = null
    private var errorOverlay: LinearLayout? = null
    private var isClosed = false
    private var currentTarget = HOME

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density + .5f).toInt()

    private fun text(value: String, sp: Float, color: Int, bold: Boolean = false) =
        TextView(activity).apply {
            text = value
            textSize = sp
            setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
        }

    private fun barButton(label: String, click: () -> Unit) =
        text(label, 13f, primary, true).apply {
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(panelBg)
                cornerRadius = dp(11).toFloat()
            }
            setPadding(dp(11), dp(10), dp(11), dp(10))
            setOnClickListener { click() }
        }

    /** Creates a native toolbar and the actual official music.youtube.com site. */
    fun create(query: String? = null): View {
        isClosed = false
        currentTarget = destination(query)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        val top = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(17), dp(11), dp(17), dp(11))
        }
        top.addView(text("YT MUSIC", 16f, primary, true), LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(barButton("↻") { reload() },
            LinearLayout.LayoutParams(-2, dp(39)).apply { rightMargin = dp(7) })
        top.addView(barButton("ABRIR APP") { openInOfficialApp() }, LinearLayout.LayoutParams(-2, dp(39)))
        root.addView(top, LinearLayout.LayoutParams(-1, dp(65)))
        root.addView(text("Catálogo e reprodução pelo site oficial · pode exigir login ou Premium",
            11f, muted).apply {
            setPadding(dp(18), 0, dp(18), dp(11))
            maxLines = 2
        }, LinearLayout.LayoutParams(-1, -2))

        loading = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progress = 0
            progressTintList = ColorStateList.valueOf(primary)
            progressBackgroundTintList = ColorStateList.valueOf(panelBg)
        }
        root.addView(loading, LinearLayout.LayoutParams(-1, dp(2)))

        val frame = FrameLayout(activity).apply { setBackgroundColor(bg) }
        holder = frame
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        val web = WebView(activity).apply {
            setBackgroundColor(bg)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = true
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setSupportZoom(false)
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            // Keep the authentic browser user agent; do not spoof or bypass site restrictions.
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (isClosed) return
                    loading?.progress = newProgress
                    loading?.visibility = if (newProgress >= 100) View.INVISIBLE else View.VISIBLE
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
                    val url = request.url
                    if (isYouTubeMusic(url)) return false
                    // Never pass file://, javascript:, intent: or other unsafe schemes to an external app.
                    if (url.scheme == "https" || url.scheme == "http") {
                        openExternal(url)
                    } else if (url.scheme == "intent") {
                        // Google's app links and sign-in flows work more reliably in a real browser.
                        openInOfficialApp()
                    }
                    return true
                }

                override fun onRenderProcessGone(
                    view: WebView?, detail: RenderProcessGoneDetail?
                ): Boolean {
                    // Low-memory Android may reclaim the WebView renderer. Keep NOX alive.
                    if (!isClosed && view != null) {
                        (view.parent as? ViewGroup)?.removeView(view)
                        view.destroy()
                        browser = null
                        showError("O navegador foi encerrado pelo Android. Tente novamente ou abra o app oficial.")
                    }
                    return true
                }

                override fun onReceivedError(
                    view: WebView?, request: WebResourceRequest?, error: WebResourceError?
                ) {
                    if (isClosed || request?.isForMainFrame != true) return
                    showError("Não foi possível carregar o YouTube Music.\nVerifique a internet ou abra no app oficial.")
                }

                override fun onReceivedHttpError(
                    view: WebView?, request: WebResourceRequest?,
                    errorResponse: android.webkit.WebResourceResponse?
                ) {
                    if (isClosed || request?.isForMainFrame != true) return
                    if ((errorResponse?.statusCode ?: 200) >= 400) {
                        showError("O YouTube Music não aceitou esta página no navegador integrado.\nAbra no app oficial.")
                    }
                }
            }
            loadUrl(currentTarget)
        }
        browser = web
        frame.addView(web, FrameLayout.LayoutParams(-1, -1))

        errorOverlay = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(bg)
            addView(text("Não foi possível abrir o catálogo", 18f, primary, true).apply {
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(-1, -2))
            addView(text("Tente recarregar ou use o aplicativo oficial.", 13f, muted).apply {
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(-1, dp(70)))
            addView(barButton("TENTAR NOVAMENTE") {
                errorOverlay?.visibility = View.GONE
                if (browser == null) {
                    // Renderer crashes are recovered by opening the reliable external service.
                    openInOfficialApp()
                } else browser?.loadUrl(currentTarget)
            }, LinearLayout.LayoutParams(-1, dp(48)))
            addView(barButton("ABRIR YOUTUBE MUSIC") { openInOfficialApp() },
                LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(12) })
        }
        frame.addView(errorOverlay, FrameLayout.LayoutParams(-1, -1))

        val note = text("O NOX não extrai áudio do YouTube. A reprodução e os anúncios seguem as regras do serviço.",
            10.5f, muted).apply {
            gravity = Gravity.CENTER
            setPadding(dp(15), dp(7), dp(15), dp(8))
        }
        root.addView(note, LinearLayout.LayoutParams(-1, -2))
        return root
    }

    fun canGoBack(): Boolean = browser?.canGoBack() == true
    fun goBack() { browser?.goBack() }
    fun reload() {
        errorOverlay?.visibility = View.GONE
        browser?.reload()
    }

    /** The same destination can be opened in YouTube Music or the user's browser. */
    fun openInOfficialApp() {
        val target = browser?.url?.let { Uri.parse(it) }
            ?.takeIf { isYouTubeMusic(it) } ?: Uri.parse(currentTarget)
        openExternal(target)
    }

    private fun openExternal(uri: Uri) {
        if (uri.scheme != "https" && uri.scheme != "http") return
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, "Nenhum navegador disponível.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showError(message: String) {
        val error = errorOverlay ?: return
        val messageView = error.getChildAt(1) as? TextView
        messageView?.text = message
        error.visibility = View.VISIBLE
    }

    /** Destroy, do not merely hide, to avoid invisible background playback and reduce RAM. */
    fun close() {
        isClosed = true
        loading = null
        errorOverlay = null
        browser?.let { web ->
            web.stopLoading()
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
        browser = null
        holder = null
    }
}
