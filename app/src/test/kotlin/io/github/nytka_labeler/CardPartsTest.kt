package io.github.nytka_labeler

import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import io.github.nytka_labeler.session.CaptionLine
import io.github.nytka_labeler.session.CardUi
import io.github.nytka_labeler.session.weekStrip
import io.github.nytka_labeler.ui.Captions
import io.github.nytka_labeler.ui.Waveform
import io.github.nytka_labeler.ui.activeLine
import io.github.nytka_labeler.ui.playedFraction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/** The waveform, the captions and the week strip, apart from the screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CardPartsTest {
    @get:Rule val compose = createComposeRule()

    private fun card(peaks: List<Float>? = List(100) { it / 100f }) =
        CardUi(
            itemId = 7,
            task = "quiet",
            ask = "transcript",
            question = "Is the text right?",
            lines = listOf(CaptionLine("Перший", 0, 2_000), CaptionLine("Другий", 2_000, 4_000)),
            before = listOf(CaptionLine("Раніше", -2_000, -300)),
            after = listOf(CaptionLine("Потім", 4_300, 6_000)),
            peaks = peaks,
            durationMs = 6_400,
            taskChange = false,
        )

    private fun fraction(tag: String) =
        compose
            .onNodeWithTag(tag)
            .fetchSemanticsNode()
            .config[SemanticsProperties.ProgressBarRangeInfo]
            .current

    @Test
    fun `the waveform fills with the playback progress`() {
        val position = mutableLongStateOf(0)
        compose.setContent { Waveform(card(), position.longValue) }
        assertEquals(0f, fraction("waveform_7"), 0.001f)

        position.longValue = 3_200
        compose.waitForIdle()
        assertEquals(0.5f, fraction("waveform_7"), 0.001f)

        position.longValue = 99_000
        compose.waitForIdle()
        assertEquals("never past the end", 1f, fraction("waveform_7"), 0.001f)
    }

    @Test
    fun `a clip with no peaks still draws a flat bar that fills`() {
        compose.setContent { Waveform(card(peaks = null), 1_600) }
        compose.onNodeWithTag("waveform_7").assertIsDisplayed()
        assertEquals(0.25f, fraction("waveform_7"), 0.001f)
    }

    @Test
    fun `the line being spoken is highlighted, the others and the context stay quiet`() {
        val position = mutableLongStateOf(500)
        compose.setContent { Captions(card(), position.longValue, current = true) }
        compose.onNodeWithContentDescription("speaking: Перший").assertIsDisplayed()
        compose.onNodeWithText("Раніше").assertIsDisplayed()
        compose.onNodeWithText("Потім").assertIsDisplayed()

        position.longValue = 2_500
        compose.waitForIdle()
        compose.onNodeWithContentDescription("speaking: Другий").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithContentDescription("speaking: Перший").fetchSemanticsNodes().size)
    }

    @Test
    fun `playback maths`() {
        assertEquals(0, activeLine(card(), 0))
        assertEquals(0, activeLine(card(), 1_999))
        assertEquals(1, activeLine(card(), 2_000))
        assertNull("between and after the lines nothing is spoken", activeLine(card(), 4_000))
        assertNull(activeLine(card(), -500))
        assertEquals(0f, playedFraction(100, 0), 0f)
        assertEquals(0.5f, playedFraction(50, 100), 0f)
    }

    @Test
    fun `the week strip lights the days with a session and counts no streak`() {
        val week = weekStrip(LocalDate.of(2026, 10, 24), setOf("2026-10-24", "2026-10-22", "2026-10-01"))
        assertEquals(7, week.size)
        assertEquals("2026-10-18", week.first().date)
        assertEquals(listOf("2026-10-22", "2026-10-24"), week.filter { it.done }.map { it.date })
        assertEquals("2026-10-24", week.single { it.today }.date)
    }
}
