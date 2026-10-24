package io.github.nytka_labeler

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.nytka_labeler.api.TaskInfo
import io.github.nytka_labeler.session.weekStrip
import io.github.nytka_labeler.ui.TasksContent
import io.github.nytka_labeler.ui.TasksUi
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/** The session list offers Mixed, the speaker switch and the week strip. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TasksFeedTest {
    @get:Rule val compose = createComposeRule()

    private val tasks =
        listOf(
            TaskInfo("device", "source", open = 12),
            TaskInfo("quiet", "transcript", open = 5),
        )

    private fun show(
        ui: TasksUi,
        started: MutableList<String> = mutableListOf(),
        speaker: MutableList<Boolean> = mutableListOf(),
    ) = compose.setContent { TasksContent(ui, started::add, {}, {}, {}, {}, {}, speaker::add) }

    @Test
    fun `Mixed starts the mixed task and adds up what is open`() {
        val started = mutableListOf<String>()
        show(TasksUi(loading = false, tasks = tasks, headphones = true), started)

        compose
            .onNodeWithTag("task_mixed")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        assertEquals(listOf("mixed"), started)
    }

    @Test
    fun `Mixed does not start when nothing is open`() {
        val started = mutableListOf<String>()
        show(TasksUi(loading = false, tasks = listOf(TaskInfo("device", "source", open = 0))), started)
        compose.onNodeWithTag("task_mixed").performScrollTo().performClick()
        assertEquals(emptyList<String>(), started)
    }

    @Test
    fun `the Autoplay on speaker switch is off by default and reports a change`() {
        val speaker = mutableListOf<Boolean>()
        show(TasksUi(loading = false, tasks = tasks), speaker = speaker)

        compose.onNodeWithTag("autoplay_speaker").performScrollTo().performClick()
        assertEquals(listOf(true), speaker)
    }

    @Test
    fun `the week strip shows seven days`() {
        val week = weekStrip(LocalDate.of(2026, 10, 24), setOf("2026-10-23"))
        show(TasksUi(loading = false, tasks = tasks, week = week))

        compose.onNodeWithTag("week_strip").performScrollTo().assertIsDisplayed()
        assertEquals(7, week.count { compose.onAllNodesWithTag("week_${it.date}").fetchSemanticsNodes().size == 1 })
    }
}
