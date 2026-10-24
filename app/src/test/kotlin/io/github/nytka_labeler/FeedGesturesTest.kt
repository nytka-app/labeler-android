package io.github.nytka_labeler

import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.nytka_labeler.api.Via
import io.github.nytka_labeler.support.Dir
import io.github.nytka_labeler.support.FeedCase
import io.github.nytka_labeler.support.FeedRobot
import io.github.nytka_labeler.support.Harness
import io.github.nytka_labeler.support.Step
import io.github.nytka_labeler.support.askOf
import io.github.nytka_labeler.support.body
import io.github.nytka_labeler.support.click
import io.github.nytka_labeler.support.item
import io.github.nytka_labeler.support.skippedBody
import io.github.nytka_labeler.support.swipe
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The cases of section 7's task table, shared by the swipe test and the rail test: only the first gesture differs. */
internal fun taskCases(
    task: String,
    yes: List<Step>,
    no: List<Step>,
    skip: List<Step>,
    direct: String,
): List<FeedCase> {
    val sheet = Via.SHEET

    fun pick(
        open: List<Step>,
        code: String,
        field: String,
    ) = FeedCase(open + click("answer_$code"), body("\"$field\":${wrap(field, code)}", sheet))

    val cases =
        when (task) {
            "voice" ->
                listOf(
                    FeedCase(yes, body("\"source\":[\"me\"]", direct)),
                    FeedCase(no, body("\"source\":[\"not_me\"]", direct)),
                )
            "device" ->
                listOf(FeedCase(yes, body("\"source\":[\"device\"]", direct))) +
                    listOf("me", "live", "call", "none", "cant_tell").map { pick(no, it, "source") }
            "overlap" ->
                listOf(FeedCase(no, body("\"overlap\":\"one\"", direct))) +
                    listOf("two", "three_plus").map { pick(yes, it, "overlap") }
            else ->
                listOf(FeedCase(yes, body("\"transcript\":\"right\"", direct))) +
                    listOf("small_errors", "made_up", "cant_hear").map { pick(no, it, "transcript") } +
                    listOf(
                        // Wrong words opens the correction with the line's text in it.
                        FeedCase(
                            no + click("answer_wrong_words", "save"),
                            body("\"transcript\":\"wrong_words\",\"correction\":\"Привіт, як справа\"", sheet),
                        ),
                        FeedCase(
                            no + click("answer_wrong_words") + Step.Type("correction", "Привіт, як справи") +
                                click("save"),
                            body("\"transcript\":\"wrong_words\",\"correction\":\"Привіт, як справи\"", sheet),
                        ),
                    )
        }
    val skipCase = FeedCase(skip, skippedBody(direct))
    // The last clip cannot be swiped up (the pager ends there), so the skip sits before it.
    return cases.dropLast(1) + skipCase + cases.last()
}

private fun wrap(
    field: String,
    code: String,
) = if (field == "source") "[\"$code\"]" else "\"$code\""

internal fun feedItems(
    task: String,
    count: Int,
) = (1..count).map { item(it.toLong(), task, askOf(task), "Привіт, як справа") }

/**
 * Every task answered by its fixed question with swipes through the real Compose screen: right and left on the
 * card, the sheets they open, swipe up for a skip. The answers go through the real Room outbox and API client to a
 * MockWebServer and the exact bodies, `answeredWith` included, are compared.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FeedGesturesTest {
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
                yes = listOf(swipe(Dir.Right)),
                no = listOf(swipe(Dir.Left)),
                skip = listOf(swipe(Dir.Up)),
                direct = Via.SWIPE,
            )
        val h = Harness(task, feedItems(task, cases.size)).also { harness = it }
        FeedRobot(compose, h).runCases(cases)
    }

    @Test fun `device - swipe right says device, swipe left opens the sheet, swipe up skips`() = run("device")

    @Test fun `voice - swipe right is me, swipe left is not_me, swipe up skips`() = run("voice")

    @Test fun `quiet - swipe right is right, swipe left opens the sheet of mistakes, swipe up skips`() = run("quiet")

    @Test fun `transcript - swipe right is right, swipe left opens the sheet with a correction`() = run("transcript")

    @Test fun `overlap - swipe left is one, swipe right opens two or three plus, swipe up skips`() = run("overlap")
}
