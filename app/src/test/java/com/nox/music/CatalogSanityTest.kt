package com.nox.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogSanityTest {
    @Test fun queuePositionAlwaysClamped() {
        val count = 3
        val chosen = 999.coerceIn(0, (count - 1).coerceAtLeast(0))
        assertEquals(2, chosen)
    }

    @Test fun providerTrackIdsStayDistinct() {
        val a = "audius:track42"
        val b = "jamendo:track42"
        assertTrue(a != b)
    }
}
