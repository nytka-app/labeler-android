package io.github.nytka_labeler.support

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import io.github.nytka_labeler.api.LabelItem
import io.github.nytka_labeler.outbox.DrainResult
import io.github.nytka_labeler.session.SessionViewModel
import io.github.nytka_labeler.ui.SessionScreen
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals

enum class Dir { Right, Left, Up, Down }

/** One thing the owner does on the feed. */
sealed interface Step {
    data class Swipe(
        val dir: Dir,
    ) : Step

    data class Click(
        val tag: String,
    ) : Step

    data class Type(
        val tag: String,
        val text: String,
    ) : Step
}

/** Steps on one clip and the JSON that must reach the server for it (without the `clientId`). */
class FeedCase(
    val steps: List<Step>,
    val expected: String,
)

fun swipe(dir: Dir) = Step.Swipe(dir)

fun click(vararg tags: String) = tags.map { Step.Click(it) }

/** The body of a non-skipped answer, as the fake player reports 5200 ms heard. */
fun body(
    fields: String,
    via: String,
) = """{$fields,"linesDiffer":false,"skipped":false,"listenedMs":${FakeClipPlayer.LISTENED},"answeredWith":"$via"}"""

fun skippedBody(via: String) =
    """{"linesDiffer":false,"skipped":true,"listenedMs":${FakeClipPlayer.LISTENED},"answeredWith":"$via"}"""

/** Drives the feed screen the way a finger does, and reads what reaches the MockWebServer. */
class FeedRobot(
    private val compose: ComposeContentTestRule,
    val h: Harness,
) {
    lateinit var vm: SessionViewModel

    fun show() {
        vm = h.viewModel()
        compose.setContent { SessionScreen(vm, onExit = {}) }
        waitFor { count("feed") == 1 }
    }

    // The card is one clickable node that merges its texts; the tests read its parts from the unmerged tree.
    fun count(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

    /** The text of a node: a button's label lives in the merged tree, a text inside the card in the unmerged one. */
    fun text(tag: String): String =
        listOf(false, true)
            .firstNotNullOfOrNull { unmerged ->
                compose
                    .onAllNodesWithTag(tag, useUnmergedTree = unmerged)
                    .fetchSemanticsNodes()
                    .firstOrNull()
                    ?.config
                    ?.getOrNull(SemanticsProperties.Text)
                    ?.joinToString("") { it.text }
            }.orEmpty()

    fun perform(
        step: Step,
        itemId: Long,
    ) {
        when (step) {
            is Step.Swipe ->
                compose.onNodeWithTag("card_$itemId").performTouchInput {
                    when (step.dir) {
                        Dir.Right -> swipeRight()
                        Dir.Left -> swipeLeft()
                        Dir.Up -> swipeUp()
                        Dir.Down -> swipeDown()
                    }
                }
            is Step.Click ->
                compose.onNodeWithTag(step.tag).apply {
                    if (step.tag.startsWith("rail_") || step.tag in setOf("undo", "another")) {
                        performClick()
                    } else {
                        performScrollTo().performClick()
                    }
                }
            is Step.Type ->
                compose.onNodeWithTag(step.tag).performScrollTo().apply {
                    performTextClearance()
                    if (step.text.isNotEmpty()) performTextInput(step.text)
                }
        }
    }

    /** Runs every case on its own clip (ids 1, 2, ...), then sends the outbox and compares each POST body. */
    fun runCases(cases: List<FeedCase>) {
        show()
        cases.forEachIndexed { index, case ->
            val id = index + 1L
            waitFor { text("progress") == "${index + 1} of ${cases.size}" }
            case.steps.forEach { perform(it, id) }
            if (index + 1 < cases.size) {
                waitFor { text("progress") == "${index + 2} of ${cases.size}" }
            } else {
                waitFor { count("end_title") == 1 }
            }
        }
        h.passUndoWindow()
        assertEquals(DrainResult.Done, runBlocking { h.sender.drain() })
        assertEquals(cases.size, h.server.posts.size)
        cases.forEachIndexed { index, case ->
            val post = h.server.posts[index]
            assertEquals("item of case $index", index + 1L, post.itemId)
            val sent = Json.parseToJsonElement(post.body).jsonObject
            assertEquals(
                "clientId present in case $index",
                36,
                sent
                    .getValue("clientId")
                    .jsonPrimitive.content.length,
            )
            assertEquals(
                "body of case $index (${case.steps})",
                Json.parseToJsonElement(case.expected).jsonObject,
                JsonObject(sent - "clientId"),
            )
        }
    }

    fun waitFor(condition: () -> Boolean) = compose.waitUntil(timeoutMillis = 10_000, condition = condition)
}

fun items(
    task: String,
    count: Int,
    vararg text: String,
): List<LabelItem> =
    (1..count).map {
        item(it.toLong(), task, askOf(task), *text.ifEmpty { arrayOf("Привіт, як справа") })
    }
