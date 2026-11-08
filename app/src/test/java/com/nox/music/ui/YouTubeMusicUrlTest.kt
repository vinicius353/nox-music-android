package com.nox.music.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeMusicUrlTest {
    @Test fun homePageWhenNoSearch() {
        assertEquals("https://music.youtube.com/", YouTubeMusicUrl.destination(null))
        assertEquals("https://music.youtube.com/", YouTubeMusicUrl.destination("  "))
    }

    @Test fun properlyEncodeRealMusicQueries() {
        assertEquals(
            "https://music.youtube.com/search?q=Linkin+Park+%26+Jay-Z",
            YouTubeMusicUrl.destination("Linkin Park & Jay-Z")
        )
    }

    @Test fun acceptsOnlyTrustedSecureYouTubeMusicOrigin() {
        assertTrue(YouTubeMusicUrl.isTrustedSite("https://music.youtube.com/search?q=test"))
        assertFalse(YouTubeMusicUrl.isTrustedSite("http://music.youtube.com/"))
        assertFalse(YouTubeMusicUrl.isTrustedSite("https://music.youtube.com.attacker.test"))
        assertFalse(YouTubeMusicUrl.isTrustedSite("javascript:alert(1)"))
        assertFalse(YouTubeMusicUrl.isTrustedSite("https://accounts.google.com/"))
    }
}
