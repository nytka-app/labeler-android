package io.github.nytka_labeler

import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.nytka_labeler.api.Via
import io.github.nytka_labeler.support.FeedRobot
import io.github.nytka_labeler.support.Harness
import io.github.nytka_labeler.support.body
import io.github.nytka_labeler.support.click
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

/**
 * An answer goes into the outbox at once with a send time 4 s ahead. Undo inside the window takes it out; after the
 * window the outbox sends it. The clock is the test's, so the 4 s pass without waiting.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UndoTest {
    @get:Rule val compose = createComposeRule()
    private var harness: Harness? = null

    @After
    fun tearDown() {
        harness?.close()
    }

    private fun start(
        count: Int = 2,
        goal: Int = 1_000,
    ): FeedRobot {
        val h =
            Harness(
                "device",
                feedItems("device", count),
                config =
                    io.github.nytka_labeler.session
                        .SessionConfig(goal = goal),
            ).also { harness = it }
        return FeedRobot(compose, h).also { it.show() }
    }

    private fun drain() = runBlocking { harness!!.sender.drain() }

    @Test
    fun `an answer is queued at once and not sent inside the window`() {
        val r = start()
        r.perform(click("rail_yes").single(), 1)
        r.waitFor { r.text("progress") == "2 of 2" }

        val row = runBlocking { harness!!.outbox.pending() }.single()
        assertEquals("not before 4 s after it was given", row.createdAt + 4_000, row.notBefore)
        drain()
        harness!!.clock.advance(3_999)
        drain()
        assertEquals("nothing is sent while the owner can still take it back", 0, harness!!.server.posts.size)
        assertEquals(1, r.count("undo_bar"))
    }

    @Test
    fun `undo within the window sends nothing and shows the clip again`() {
        val r = start()
        r.perform(click("rail_yes").single(), 1)
        r.waitFor { r.text("progress") == "2 of 2" }

        r.perform(click("undo").single(), 1)
        r.waitFor { r.text("progress") == "1 of 2" }
        assertEquals(0, r.count("undo_bar"))

        harness!!.passUndoWindow()
        drain()
        assertEquals(0, harness!!.server.posts.size)
        assertTrue(runBlocking { harness!!.outbox.all() }.isEmpty())
    }

    @Test
    fun `without undo the answer is sent after the window`() {
        val r = start()
        r.perform(click("rail_yes").single(), 1)
        r.waitFor { r.text("progress") == "2 of 2" }

        harness!!.passUndoWindow()
        drain()

        val post = harness!!.server.posts.single()
        assertEquals(1L, post.itemId)
        assertEquals(
            Json.parseToJsonElement(body("\"source\":[\"device\"]", Via.BUTTON)).jsonObject,
            JsonObject(Json.parseToJsonElement(post.body).jsonObject - "clientId"),
        )
        assertTrue(runBlocking { harness!!.outbox.all() }.isEmpty())
    }

    @Test
    fun `undoing the answer that ended the feed brings the last clip back`() {
        val r = start(count = 3, goal = 2)
        r.perform(click("rail_yes").single(), 1)
        r.waitFor { r.text("progress") == "2 of 2" }
        r.perform(click("rail_yes").single(), 2)
        r.waitFor { r.count("end_title") == 1 }
        assertEquals(1, r.count("undo_bar"))

        r.perform(click("undo").single(), 2)
        r.waitFor { r.count("feed") == 1 }

        assertEquals("2 of 2", r.text("progress"))
        harness!!.passUndoWindow()
        drain()
        assertEquals("only the first answer is sent", listOf(1L), harness!!.server.posts.map { it.itemId })
    }
}
