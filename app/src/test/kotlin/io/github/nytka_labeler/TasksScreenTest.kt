package io.github.nytka_labeler

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.nytka_labeler.api.TaskInfo
import io.github.nytka_labeler.ui.HEADPHONE_HINT
import io.github.nytka_labeler.ui.TasksContent
import io.github.nytka_labeler.ui.TasksUi
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TasksScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun show(
        ui: TasksUi,
        started: MutableList<String> = mutableListOf(),
    ) = compose.setContent { TasksContent(ui, started::add, {}, {}, {}, {}) }

    private val tasks =
        listOf(
            TaskInfo("device", "source", open = 12, labelled = 72, goal = 100, goalLabel = "not-device lines"),
            TaskInfo("voice", "source", open = 0),
            TaskInfo("quiet", "transcript", open = 5),
            TaskInfo("transcript", "transcript", open = 3),
            TaskInfo("overlap", "overlap", open = 9),
        )

    @Test
    fun `the headphone hint shows only without headphones`() {
        show(TasksUi(loading = false, tasks = tasks, headphones = false))
        compose.onNodeWithTag("headphone_hint").assertIsDisplayed()
    }

    @Test
    fun `no hint with headphones`() {
        show(TasksUi(loading = false, tasks = tasks, headphones = true))
        assertEquals(0, compose.onAllNodesWithTag("headphone_hint").fetchSemanticsNodes().size)
        assertEquals("Device audio is easier to hear through headphones", HEADPHONE_HINT)
    }

    @Test
    fun `every task is listed with its counts and a tap starts it`() {
        val started = mutableListOf<String>()
        show(TasksUi(loading = false, tasks = tasks, headphones = true), started)

        listOf("device", "voice", "quiet", "transcript", "overlap").forEach {
            compose.onNodeWithTag("task_$it").performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithTag("task_device").performScrollTo().performClick()
        compose.onNodeWithTag("task_overlap").performScrollTo().performClick()
        assertEquals(listOf("device", "overlap"), started)
    }

    @Test
    fun `a refused token is announced`() {
        show(TasksUi(loading = false, needsToken = true, headphones = true))
        compose.onNodeWithTag("token_refused").assertIsDisplayed()
    }
}
