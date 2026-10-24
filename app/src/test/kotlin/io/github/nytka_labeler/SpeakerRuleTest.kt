package io.github.nytka_labeler

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.github.nytka_labeler.support.FeedRobot
import io.github.nytka_labeler.support.Harness
import io.github.nytka_labeler.support.click
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Other people's voices do not play out loud by surprise: a clip starts on its own only through headphones or a
 * Bluetooth headset, or when the owner switched speaker autoplay on. The monitor is a fake.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SpeakerRuleTest {
    @get:Rule val compose = createComposeRule()
    private var harness: Harness? = null

    @After
    fun tearDown() {
        harness?.close()
    }

    private fun start(
        headphones: Boolean,
        speakerAutoplay: Boolean = false,
    ): FeedRobot {
        val h = Harness("device", feedItems("device", 3), headphones = headphones).also { harness = it }
        runBlocking { h.settings.update { it.copy(autoplaySpeaker = speakerAutoplay) } }
        return FeedRobot(compose, h).also { it.show() }
    }

    @Test
    fun `without headphones the clip waits for a tap`() {
        val r = start(headphones = false)
        r.waitFor { harness!!.player.loaded.size == 1 }

        assertFalse("no autoplay on the loudspeaker", harness!!.player.playing.value)
        r.waitFor { r.count("tap_to_play") == 1 }
        assertEquals("Tap to play (speaker)", r.text("tap_to_play"))

        compose.onNodeWithTag("card_1").performClick()
        assertEquals(1, harness!!.player.replays)
        assertTrue(harness!!.player.playing.value)
        r.waitFor { r.count("tap_to_play") == 0 }

        // The next clip waits again.
        r.perform(click("rail_yes").single(), 1)
        r.waitFor { r.text("progress") == "2 of 3" }
        assertFalse(harness!!.player.playing.value)
        r.waitFor { r.count("tap_to_play") == 1 }
    }

    @Test
    fun `with headphones the clip plays on arrival and shows no hint`() {
        val r = start(headphones = true)
        r.waitFor { harness!!.player.loaded.size == 1 }

        assertTrue(harness!!.player.playing.value)
        assertEquals(0, r.count("tap_to_play"))

        r.perform(click("rail_yes").single(), 1)
        r.waitFor { harness!!.player.loaded.size == 2 }
        assertTrue("the next clip plays by itself too", harness!!.player.playing.value)
    }

    @Test
    fun `the autoplay on speaker switch lets the loudspeaker play on arrival`() {
        val r = start(headphones = false, speakerAutoplay = true)
        r.waitFor { harness!!.player.loaded.size == 1 }

        assertTrue(harness!!.player.playing.value)
        assertEquals(0, r.count("tap_to_play"))
    }
}
