package io.github.nytka_labeler

import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.nytka_labeler.session.SessionUi
import io.github.nytka_labeler.support.FeedRobot
import io.github.nytka_labeler.support.Harness
import io.github.nytka_labeler.support.askOf
import io.github.nytka_labeler.support.click
import io.github.nytka_labeler.support.item
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** `task=mixed`: runs of one task, each clip asked its own fixed question, the task name shown at each switch. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MixedFeedTest {
    @get:Rule val compose = createComposeRule()
    private var harness: Harness? = null

    @After
    fun tearDown() {
        harness?.close()
    }

    private val tasks = listOf("device", "device", "quiet", "quiet", "overlap")

    private fun start(task: String = "mixed"): FeedRobot {
        val items = tasks.mapIndexed { i, t -> item(i + 1L, t, askOf(t), "Привіт") }
        val h = Harness(task, items).also { harness = it }
        return FeedRobot(compose, h).also { it.show() }
    }

    @Test
    fun `the task name shows when the task changes and each clip asks its own question`() {
        val r = start()

        val cards = (r.vm.ui.value as SessionUi.Showing).cards
        assertEquals(listOf(true, false, true, false, true), cards.map { it.taskChange })
        r.waitFor { r.count("question_3") == 1 }
        assertEquals(1, r.count("task_change_1"))
        assertEquals(0, r.count("task_change_2"))
        assertEquals(1, r.count("task_change_3"))
        assertEquals("Device or live voice", r.text("task_name"))
        assertEquals("Is it coming from a device?", r.text("question_1"))
        assertEquals("Is the text right?", r.text("question_3"))

        r.perform(click("rail_yes").single(), 1)
        r.perform(click("rail_yes").single(), 2)
        r.waitFor { r.text("task_name") == "Quiet lines" }
    }

    @Test
    fun `a single task never announces a change`() {
        val r = start(task = "device")
        assertEquals(0, r.count("task_change_1"))
        assertEquals(0, r.count("task_change_3"))
    }
}
