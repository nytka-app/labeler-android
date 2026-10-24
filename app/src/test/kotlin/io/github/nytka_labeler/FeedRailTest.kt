package io.github.nytka_labeler

import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.nytka_labeler.api.Via
import io.github.nytka_labeler.support.FeedRobot
import io.github.nytka_labeler.support.Harness
import io.github.nytka_labeler.support.click
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The rail (Yes, No, Skip, More) does what the gestures do, for screen readers and anyone who does not swipe. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FeedRailTest {
    @get:Rule val compose = createComposeRule()
    private var harness: Harness? = null

    @After
    fun tearDown() {
        harness?.close()
    }

    private fun run(task: String) {
        val cases =
            taskCases(
                task,
                yes = click("rail_yes"),
                no = click("rail_no"),
                skip = click("rail_skip"),
                direct = Via.BUTTON,
            )
        val h = Harness(task, feedItems(task, cases.size)).also { harness = it }
        FeedRobot(compose, h).runCases(cases)
    }

    @Test fun `device - Yes says device, No opens the sheet, Skip skips`() = run("device")

    @Test fun `voice - Yes is me, No is not_me, Skip skips`() = run("voice")

    @Test fun `quiet - Yes is right, No opens the sheet of mistakes, Skip skips`() = run("quiet")

    @Test fun `transcript - Yes is right, No opens the sheet with a correction`() = run("transcript")

    @Test fun `overlap - No is one, Yes opens two or three plus, Skip skips`() = run("overlap")
}
