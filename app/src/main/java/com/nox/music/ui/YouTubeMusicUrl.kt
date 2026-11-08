package com.nox.music.ui

import java.net.URI
import java.net.URLEncoder

/**
 * Small pure-JVM route policy; YouTube Music searches stay on Google's HTTPS origin.
 */
object YouTubeMusicUrl {
    const val HOME = "https://music.youtube.com/"

    fun destination(query: String?): String =
        if (query.isNullOrBlank()) HOME
        else "https://music.youtube.com/search?q=" + URLEncoder.encode(query.trim(), "UTF-8")

    fun isTrustedSite(raw: String): Boolean = runCatching {
        val uri = URI(raw)
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals("music.youtube.com", ignoreCase = true)
    }.getOrDefault(false)
}
