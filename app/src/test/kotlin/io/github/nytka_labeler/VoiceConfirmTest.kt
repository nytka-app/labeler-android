package io.github.nytka_labeler

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.nytka_labeler.support.Harness
import io.github.nytka_labeler.ui.VoiceScreen
import io.github.nytka_labeler.ui.VoiceViewModel
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoiceConfirmTest {
    @get:Rule val compose = createComposeRule()
    private val h = Harness("device", emptyList())

    @After
    fun tearDown() = h.close()

    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(10_000, condition)

    @Test
    fun `the confirm body holds exactly the checked ids and the answer shows learned`() {
        h.server.pendingVoice =
            """{"items":[{"labelId":11,"segmentId":1,"durationMs":2400},{"labelId":12,"segmentId":2,"durationMs":3100},
            {"labelId":13,"segmentId":3,"durationMs":900}]}"""
        val vm = VoiceViewModel(h.api)
        compose.setContent { VoiceScreen(vm, onExit = {}) }
        waitFor { compose.onAllNodesWithTag("voice_11").fetchSemanticsNodes().isNotEmpty() }

        // All are ticked at first; untick the middle one.
        compose.onNodeWithTag("voice_12").performScrollTo().performClick()
        compose
            .onNodeWithTag("voice_confirm")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        waitFor { h.server.confirmBodies.isNotEmpty() }

        assertEquals(1, h.server.confirmBodies.size)
        assertEquals(
            Json.parseToJsonElement("""{"labelIds":[11,13]}"""),
            Json.parseToJsonElement(h.server.confirmBodies.single()),
        )
        waitFor { compose.onAllNodesWithTag("voice_learned").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("voice_learned").assertTextEquals("Learned: 2")
        compose
            .onNodeWithTag(
                "voice_warning",
            ).assertTextEquals("Confirmed lines teach the voiceprint, and that cannot be undone.")
    }
}
