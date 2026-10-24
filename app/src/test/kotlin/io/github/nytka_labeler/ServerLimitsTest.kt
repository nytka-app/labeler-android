package io.github.nytka_labeler

import io.github.nytka_labeler.api.Answer
import io.github.nytka_labeler.api.TaskInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the server PR (nytka-app/server#195) changed: goal progress and the answer limits. */
class ServerLimitsTest {
    @Test fun `goal progress comes from progress when the server sends it`() {
        // device: 8 labelled, of which 5 count toward the not-device goal
        assertEquals(5, TaskInfo(task = "device", ask = "source", labelled = 8, goal = 100, progress = 5).towardGoal)
    }

    @Test fun `an older server without progress falls back to labelled`() {
        assertEquals(8, TaskInfo(task = "device", ask = "source", labelled = 8, goal = 100).towardGoal)
    }

    @Test fun `limits match the server`() {
        assertEquals(600_000L, Answer.MAX_LISTENED_MS)
        assertEquals(2000, Answer.MAX_CORRECTION)
    }
}
