package io.github.nytka_labeler

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.cash.turbine.test
import io.github.nytka_labeler.api.Via
import io.github.nytka_labeler.support.Dir
import io.github.nytka_labeler.support.FeedRobot
import io.github.nytka_labeler.support.Harness
import io.github.nytka_labeler.support.body
import io.github.nytka_labeler.support.click
import io.github.nytka_labeler.support.swipe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Moving through the feed: back, replay, the long-press sheet, the day record and the haptic ticks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FeedFlowTest {
    @get:Rule val compose = createComposeRule()
    private var harness: Harness? = null

    @After
    fun tearDown() {
        harness?.close()
    }

    private fun start(count: Int = 3): FeedRobot {
        val h = Harness("device", feedItems("device", count)).also { harness = it }
        return FeedRobot(compose, h).also { it.show() }
    }

    private fun sent(itemId: Long) =
        harness!!.server.posts.filter { it.itemId == itemId }.map {
            JsonObject(Json.parseToJsonElement(it.body).jsonObject - "clientId")
        }

    @Test
    fun `swipe down goes back and a new answer replaces the one not yet sent`() {
        val r = start()
        r.perform(swipe(Dir.Right), 1)
        r.waitFor { r.text("progress") == "2 of 3" }

        r.perform(swipe(Dir.Down), 2)
        r.waitFor { r.text("progress") == "1 of 3" }
        r.perform(swipe(Dir.Left), 1)
        r.perform(click("answer_live").single(), 1)
        r.waitFor { r.text("progress") == "2 of 3" }

        harness!!.passUndoWindow()
        runBlocking { harness!!.sender.drain() }
        val expected = Json.parseToJsonElement(body("\"source\":[\"live\"]", Via.SHEET)).jsonObject
        assertEquals("one POST for the clip, the second answer", listOf(expected), sent(1))
    }

    @Test
    fun `swipe down after the first answer was sent supersedes it with a second answer`() {
        val r = start()
        r.perform(swipe(Dir.Right), 1)
        r.waitFor { r.text("progress") == "2 of 3" }
        harness!!.passUndoWindow()
        runBlocking { harness!!.sender.drain() }

        r.perform(swipe(Dir.Down), 2)
        r.waitFor { r.text("progress") == "1 of 3" }
        r.perform(swipe(Dir.Left), 1)
        r.perform(click("answer_none").single(), 1)
        r.waitFor { r.text("progress") == "2 of 3" }
        harness!!.passUndoWindow()
        runBlocking { harness!!.sender.drain() }

        assertEquals(
            listOf(
                Json.parseToJsonElement(body("\"source\":[\"device\"]", Via.SWIPE)).jsonObject,
                Json.parseToJsonElement(body("\"source\":[\"none\"]", Via.SHEET)).jsonObject,
            ),
            sent(1),
        )
    }

    @Test
    fun `tapping the card plays it again and a long-press opens the full sheet`() {
        val r = start(2)
        compose.onNodeWithTag("card_1").performClick()
        assertEquals(1, harness!!.player.replays)

        compose.onNodeWithTag("card_1").performTouchInput { longClick() }
        r.waitFor { r.count("sheet") == 1 }
        // The full sheet holds every value of the axis and the optional details.
        listOf("answer_me", "answer_device", "answer_several", "flag_noisy", "lang_uk", "sound_music").forEach {
            assertEquals(it, 1, r.count(it))
        }
        r.perform(click("sheet_close").single(), 1)
        r.waitFor { r.count("sheet") == 0 }
        assertEquals("closing the sheet answers nothing", 0, runBlocking { harness!!.outbox.all() }.size)
    }

    @Test
    fun `an answer records the day for the week strip`() {
        val r = start(2)
        r.perform(click("rail_yes").single(), 1)
        r.waitFor { runBlocking { harness!!.settings.current() }.sessionDays == setOf("2026-10-24") }
        assertEquals("2026-10-24", runBlocking { harness!!.settings.current() }.lastSessionDay)
    }

    @Test
    fun `a yes ticks a confirm and the rest a reject`() {
        val h = Harness("device", feedItems("device", 3)).also { harness = it }
        val vm = h.viewModel()
        waitUntilShowing(vm)
        runBlocking {
            vm.haptics.test {
                vm.yes(Via.BUTTON)
                assertEquals(true, awaitItem())
                vm.no(Via.BUTTON)
                vm.choose("me")
                assertEquals(false, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }
        assertTrue(vm.ui.value is io.github.nytka_labeler.session.SessionUi.Showing)
    }

    private fun waitUntilShowing(vm: io.github.nytka_labeler.session.SessionViewModel) {
        val end = System.currentTimeMillis() + 10_000
        while (vm.ui.value !is io.github.nytka_labeler.session.SessionUi.Showing) {
            check(System.currentTimeMillis() < end) { "timed out" }
            org.robolectric.shadows.ShadowLooper
                .idleMainLooper()
            Thread.sleep(10)
        }
    }
}
