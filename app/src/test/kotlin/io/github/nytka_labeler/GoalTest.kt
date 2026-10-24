package io.github.nytka_labeler

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import io.github.nytka_labeler.api.Guess
import io.github.nytka_labeler.api.TaskInfo
import io.github.nytka_labeler.session.SessionConfig
import io.github.nytka_labeler.support.FeedRobot
import io.github.nytka_labeler.support.Harness
import io.github.nytka_labeler.support.askOf
import io.github.nytka_labeler.support.click
import io.github.nytka_labeler.support.item
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The daily goal ends the feed with an end card that says only what the server can honestly say. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GoalTest {
    @get:Rule val compose = createComposeRule()
    private var harness: Harness? = null

    @After
    fun tearDown() {
        harness?.close()
    }

    private fun start(
        items: List<io.github.nytka_labeler.api.LabelItem>,
        config: SessionConfig = SessionConfig(),
        prepare: (Harness) -> Unit = {},
    ): FeedRobot {
        val h = Harness("device", items, config).also { harness = it }
        prepare(h)
        return FeedRobot(compose, h).also { it.show() }
    }

    @Test
    fun `after 30 answers the feed stops, no further page and the end card shows`() {
        val r = start(feedItems("device", 33))
        repeat(30) { i ->
            r.waitFor { r.text("progress") == "${i + 1} of 30" }
            r.perform(click("rail_yes").single(), i + 1L)
        }
        r.waitFor { r.count("end_title") == 1 }

        assertEquals("the feed is gone, not paused on a 31st clip", 0, r.count("feed"))
        assertEquals(0, r.count("card_31"))
        assertEquals("30 answered, 0 skipped", r.text("end_counts"))
        assertEquals("Another 30", r.text("another"))
        assertEquals(
            7,
            (0..6).count {
                r.count("week_${java.time.LocalDate.of(2026, 10, 24).minusDays(it.toLong())}") ==
                    1
            },
        )

        harness!!.passUndoWindow()
        runBlocking { harness!!.sender.drain() }
        assertEquals(30, harness!!.server.posts.size)
        assertEquals(
            "the three clips never reached are not answered",
            30,
            harness!!
                .server.posts
                .map {
                    it.itemId
                }.toSet()
                .size,
        )
    }

    @Test
    fun `the end card reports the gate before and after, the disagreements and answers still sending`() {
        val guess = Guess("clap-head-1", "device", 0.71)
        val items = (1L..4L).map { item(it, "device", askOf("device"), "Привіт", guess = guess) }
        val gate = { labelled: Int ->
            listOf(
                TaskInfo("device", "source", open = 4, labelled = labelled, goal = 100, goalLabel = "not-device lines"),
            )
        }
        val r = start(items, SessionConfig(goal = 3)) { it.server.tasks = gate(72) }
        r.waitFor {
            r.h.server.requestPaths
                .any { it.endsWith("api/v1/labeling/tasks") }
        }
        r.h.server.tasks = gate(75)

        r.perform(click("rail_yes").single(), 1)
        r.perform(click("rail_no", "answer_me").first(), 2)
        r.perform(click("answer_me").single(), 2)
        r.waitFor { r.text("progress") == "3 of 3" }
        r.perform(click("rail_no").single(), 3)
        r.perform(click("answer_live").single(), 3)
        r.waitFor { r.count("end_title") == 1 }

        r.waitFor { r.count("gate_device") == 1 }
        assertEquals("Device or live voice: 72 → 75 of 100 not-device lines", r.text("gate_device"))
        assertEquals(
            "2 of 3 answers disagreed with the model, the most useful ones.",
            r.text("end_disagreed"),
        )
        assertEquals("3 answers still sending. They go out when there is a network.", r.text("end_waiting"))
        assertEquals("0 saved on the server", r.text("end_saved"))
    }

    @Test
    fun `the model's guess is hidden until the answer, then flashed`() {
        val guess = Guess("clap-head-1", "device", 0.71)
        val items = (1L..2L).map { item(it, "device", askOf("device"), "Привіт", guess = guess) }
        val r = start(items, SessionConfig(goal = 1_000, flashMs = 60_000))

        assertEquals(
            "no guess on the card before the answer",
            0,
            compose.onAllNodesWithText("0.71", substring = true).fetchSemanticsNodes().size,
        )
        assertEquals(0, r.count("flash"))

        r.perform(click("rail_yes").single(), 1)
        r.waitFor { r.count("flash") == 1 }
        assertEquals("model said: device, 0.71", r.text("flash"))
    }

    @Test
    fun `another 30 starts a new feed`() {
        val r = start(feedItems("device", 3), SessionConfig(goal = 2))
        r.perform(click("rail_yes").single(), 1)
        r.waitFor { r.text("progress") == "2 of 2" }
        r.perform(click("rail_yes").single(), 2)
        r.waitFor { r.count("end_title") == 1 }
        assertTrue(r.text("another").startsWith("Another"))

        r.perform(click("another").single(), 0)
        r.waitFor { r.count("feed") == 1 }
        assertEquals("1 of 2", r.text("progress"))
    }
}
