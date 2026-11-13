package io.drivegrowth.app.ui.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrototypeAudioFocusTest {
    @Test fun transientLossKeepsRequestAndDiscardsNewSoundsAcrossSceneChanges() {
        val focus = PrototypeAudioFocus()
        var requests = 0
        var abandons = 0
        fun request() = focus.request { requests++; true }
        fun sceneHasNoPlayback() = focus.releaseIfIdle(true) { abandons++; focus.reset() }

        assertTrue(request())
        focus.pause()
        sceneHasNoPlayback()
        assertTrue(focus.paused)
        assertFalse(request())
        sceneHasNoPlayback()
        assertFalse(request())
        assertEquals(0, abandons)
        assertEquals(1, requests)

        focus.reset()
        assertTrue(request())
        sceneHasNoPlayback()
        assertEquals(2, requests)
        assertEquals(1, abandons)
    }
}
