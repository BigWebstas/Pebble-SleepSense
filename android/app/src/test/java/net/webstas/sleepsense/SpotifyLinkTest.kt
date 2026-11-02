package net.webstas.sleepsense

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpotifyLinkTest {
    @Test
    fun aSharedLinkBecomesTheAddress() {
        assertEquals(
            "spotify:playlist:37i9dQZF1DXcBWIGoYBM5M",
            SpotifyLink.parse("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=abc123"),
        )
    }

    @Test
    fun theAddressItselfAndTheInternationalLinkWork() {
        assertEquals("spotify:playlist:abc", SpotifyLink.parse("spotify:playlist:abc"))
        assertEquals("spotify:album:xyz9", SpotifyLink.parse("https://open.spotify.com/intl-de/album/xyz9"))
    }

    @Test
    fun otherTextIsRefused() {
        assertNull(SpotifyLink.parse("https://example.com/playlist/abc"))
        assertNull(SpotifyLink.parse(""))
    }
}
