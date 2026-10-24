package io.github.nytka_labeler

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import io.github.nytka_labeler.api.Codes
import io.github.nytka_labeler.api.LabelItem
import io.github.nytka_labeler.outbox.DrainResult
import io.github.nytka_labeler.support.FakeClipPlayer
import io.github.nytka_labeler.support.Harness
import io.github.nytka_labeler.support.TOKEN
import io.github.nytka_labeler.support.askOf
import io.github.nytka_labeler.support.item
import io.github.nytka_labeler.ui.SessionScreen
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The test this app exists for. An earlier labelling page lost every label to a button handler bug, so each button of
 * each asked axis, and each optional row, is tapped through the real Compose screen and the real view model; the
 * answers go through the real Room outbox and the real API client to a MockWebServer, and the exact JSON of every
 * POST is compared.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EveryAnswerSavesTest {
    @get:Rule val compose = createComposeRule()

    private var harness: Harness? = null

    @After
    fun tearDown() {
        harness?.close()
    }

    private sealed interface Tap {
        data class Click(
            val tag: String,
        ) : Tap

        data class Type(
            val tag: String,
            val text: String,
        ) : Tap
    }

    private class Case(
        val item: LabelItem,
        val taps: List<Tap>,
        val expected: String,
    )

    private fun click(vararg tags: String) = tags.map { Tap.Click(it) }

    /** The fixed tail of every non-skip answer, as the fake player reports 5200 ms heard. */
    private fun answer(
        fields: String,
        linesDiffer: Boolean = false,
    ) = """{$fields,"linesDiffer":$linesDiffer,"skipped":false,"listenedMs":${FakeClipPlayer.LISTENED},""" +
        """"answeredWith":"sheet"}"""

    private val skipped =
        """{"linesDiffer":false,"skipped":true,"listenedMs":${FakeClipPlayer.LISTENED},"answeredWith":"button"}"""

    private fun cases(
        task: String,
        build: CaseBuilder.() -> Unit,
    ): List<Case> = CaseBuilder(task).apply(build).cases

    private inner class CaseBuilder(
        val task: String,
    ) {
        val cases = mutableListOf<Case>()

        fun case(
            taps: List<Tap>,
            expected: String,
            vararg text: String,
            language: String? = null,
        ) {
            val id = cases.size + 1L
            val lines = text.ifEmpty { arrayOf("Привіт, як справа") }
            cases += Case(item(id, task, askOf(task), *lines, language = language), taps, expected)
        }
    }

    // ---- the cases per asked axis

    private fun CaseBuilder.optionalRows(
        firstButton: String,
        fields: String,
    ) {
        Codes.FLAGS.forEach { flag ->
            case(click("flag_$flag", firstButton), answer("$fields,\"flags\":[\"$flag\"]"))
        }
        // Tapped out of order; the body lists them in the codes' order.
        case(
            click(
                *Codes.FLAGS
                    .reversed()
                    .map { "flag_$it" }
                    .toTypedArray(),
                firstButton,
            ),
            answer("$fields,\"flags\":${Codes.FLAGS.joinToString(",", "[", "]") { "\"$it\"" }}"),
        )
        Codes.CONTENT.forEach { code ->
            case(click("sound_$code", firstButton), answer("$fields,\"content\":\"$code\""))
        }
        // A second tap on the same sound clears it.
        case(click("sound_music", "sound_music", firstButton), answer(fields))
        case(click("sound_music", "sound_singing", firstButton), answer("$fields,\"content\":\"singing\""))
        Codes.LANGUAGE.forEach { code ->
            case(click("lang_$code", firstButton), answer("$fields,\"language\":\"$code\""))
        }
        case(click(firstButton), answer("$fields,\"language\":\"uk\""), language = "uk")
        case(click("lang_uk", firstButton), answer(fields), language = "uk")
        case(click("lang_ru", firstButton), answer("$fields,\"language\":\"ru\""), language = "uk")
        case(
            click("lines_differ", firstButton),
            answer(fields, linesDiffer = true),
            "Перший рядок",
            "Другий рядок",
        )
        case(click("flag_noisy", "lang_en", firstButton), answer("$fields,\"flags\":[\"noisy\"],\"language\":\"en\""))
    }

    private fun sourceCases(task: String) =
        cases(task) {
            // Device opens its panel and is covered below; the rest answer on the tap.
            (Codes.SOURCE - "device").forEach { code ->
                case(click("answer_$code"), answer("\"source\":[\"$code\"]"))
            }
            if (task == "voice") case(click("answer_not_me"), answer("\"source\":[\"not_me\"]"))
            case(click("answer_device", "save"), answer("\"source\":[\"device\"]"))
            Codes.DEVICE_TYPE.forEach { type ->
                case(
                    click("answer_device", "device_type_$type", "save"),
                    answer("\"source\":[\"device\"],\"deviceType\":\"$type\""),
                )
            }
            // A second tap on the same type clears it again.
            case(click("answer_device", "device_type_tv", "device_type_tv", "save"), answer("\"source\":[\"device\"]"))
            case(click("answer_several", "pick_me", "pick_live", "save"), answer("\"source\":[\"me\",\"live\"]"))
            case(click("answer_several", "pick_call", "pick_live", "save"), answer("\"source\":[\"live\",\"call\"]"))
            // Only two can be picked: the third tap is ignored.
            case(
                click("answer_several", "pick_me", "pick_live", "pick_call", "save"),
                answer("\"source\":[\"me\",\"live\"]"),
            )
            // A pick can be taken back.
            case(click("answer_several", "pick_me", "pick_live", "pick_me", "save"), answer("\"source\":[\"live\"]"))
            case(click("answer_several", "pick_device", "save"), answer("\"source\":[\"device\"]"))
            optionalRows("answer_me", "\"source\":[\"me\"]")
            case(
                click("flag_muffled", "answer_device", "device_type_car", "save"),
                answer("\"source\":[\"device\"],\"deviceType\":\"car\",\"flags\":[\"muffled\"]"),
            )
            case(click("rail_skip"), skipped)
        }

    private fun transcriptCases(task: String) =
        cases(task) {
            listOf("right", "small_errors", "made_up", "cant_hear").forEach { code ->
                case(click("answer_$code"), answer("\"transcript\":\"$code\""))
            }
            // Wrong words opens the correction field with the line's text in it.
            case(
                click("answer_wrong_words", "save"),
                answer("\"transcript\":\"wrong_words\",\"correction\":\"Привіт, як справа\""),
            )
            case(
                listOf(Tap.Click("answer_wrong_words"), Tap.Type("correction", "Привіт, як справи"), Tap.Click("save")),
                answer("\"transcript\":\"wrong_words\",\"correction\":\"Привіт, як справи\""),
            )
            case(
                listOf(Tap.Click("answer_wrong_words"), Tap.Type("correction", ""), Tap.Click("save")),
                answer("\"transcript\":\"wrong_words\""),
            )
            // An item of several lines takes no correction.
            case(
                click("answer_wrong_words", "save"),
                answer("\"transcript\":\"wrong_words\""),
                "Перший рядок",
                "Другий рядок",
            )
            optionalRows("answer_right", "\"transcript\":\"right\"")
            case(
                listOf(
                    Tap.Click("flag_quiet"),
                    Tap.Click("answer_wrong_words"),
                    Tap.Type("correction", "Добрий день"),
                    Tap.Click("save"),
                ),
                answer("\"transcript\":\"wrong_words\",\"flags\":[\"quiet\"],\"correction\":\"Добрий день\""),
            )
            case(click("rail_skip"), skipped)
        }

    private fun overlapCases(task: String) =
        cases(task) {
            Codes.OVERLAP.forEach { code ->
                case(click("answer_$code"), answer("\"overlap\":\"$code\""))
            }
            optionalRows("answer_two", "\"overlap\":\"two\"")
            case(click("rail_skip"), skipped)
        }

    // ---- one test per task

    @Test fun `device task - every button and optional row saves`() = run("device", sourceCases("device"))

    @Test fun `voice task - every button and optional row saves`() = run("voice", sourceCases("voice"))

    @Test fun `quiet task - every button and optional row saves`() = run("quiet", transcriptCases("quiet"))

    @Test fun `transcript task - every button and optional row saves`() =
        run(
            "transcript",
            transcriptCases("transcript"),
        )

    @Test fun `overlap task - every button and optional row saves`() = run("overlap", overlapCases("overlap"))

    // ---- the run

    private fun run(
        task: String,
        cases: List<Case>,
    ) {
        val h = Harness(task, cases.map { it.item }).also { harness = it }
        val vm = h.viewModel()
        compose.setContent { SessionScreen(vm, onExit = {}) }

        cases.forEachIndexed { index, case ->
            compose.onNodeWithTag("progress").assertTextEquals("${index + 1} of ${cases.size}")
            // The big grid is gone: every value now sits in the long-press sheet, which More opens.
            if (case.taps.first() != Tap.Click("rail_skip")) perform(Tap.Click("rail_more"))
            case.taps.forEach(::perform)
            if (index + 1 < cases.size) {
                waitFor { text("progress") == "${index + 2} of ${cases.size}" }
            } else {
                waitFor { compose.onAllNodesWithTag("end_title").fetchSemanticsNodes().isNotEmpty() }
            }
        }

        // Every answer is in the outbox, and none shows as saved before the server said so.
        waitFor {
            runBlocking {
                h.db
                    .outbox()
                    .all()
                    .size
            } == cases.size
        }
        waitFor { text("end_waiting").isNotEmpty() }
        compose.onNodeWithTag("end_saved").assertTextEquals("0 saved on the server")

        // The undo window holds every answer back; once it is over the outbox sends them all.
        assertEquals(DrainResult.Done, runBlocking { h.sender.drain() })
        assertEquals("nothing is sent inside the undo window", 0, h.server.posts.size)
        h.passUndoWindow()
        assertEquals(DrainResult.Done, runBlocking { h.sender.drain() })
        assertEquals(
            "the outbox keeps nothing after 200s",
            0,
            runBlocking {
                h.db
                    .outbox()
                    .all()
                    .size
            },
        )

        assertEquals(cases.size, h.server.posts.size)
        cases.forEachIndexed { index, case ->
            val post = h.server.posts[index]
            assertEquals("item of case $index", case.item.id, post.itemId)
            assertEquals("Bearer $TOKEN", post.authorization)
            val sent = Json.parseToJsonElement(post.body).jsonObject
            assertTrue(
                "clientId present in case $index",
                sent
                    .getValue("clientId")
                    .jsonPrimitive.content.length == 36,
            )
            assertEquals(
                "body of case $index (${case.taps})",
                Json.parseToJsonElement(case.expected).jsonObject,
                JsonObject(sent - "clientId"),
            )
        }
        assertEquals("one label per answer on the server", cases.size, h.server.labelCount)
        waitFor { text("end_saved") == "${cases.size} saved on the server" }
    }

    private fun perform(tap: Tap) {
        when (tap) {
            is Tap.Click ->
                compose.onNodeWithTag(tap.tag).apply {
                    if (tap.tag.startsWith("rail_")) performClick() else performScrollTo().performClick()
                }
            is Tap.Type ->
                compose.onNodeWithTag(tap.tag).performScrollTo().apply {
                    performTextClearance()
                    if (tap.text.isNotEmpty()) performTextInput(tap.text)
                }
        }
    }

    private fun text(tag: String): String =
        compose
            .onAllNodesWithTag(tag)
            .fetchSemanticsNodes()
            .firstOrNull()
            ?.config
            ?.getOrNull(SemanticsProperties.Text)
            ?.joinToString("") { it.text }
            .orEmpty()

    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(timeoutMillis = 10_000, condition = condition)
}
