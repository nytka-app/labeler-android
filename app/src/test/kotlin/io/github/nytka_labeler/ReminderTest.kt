package io.github.nytka_labeler

import io.github.nytka_labeler.api.TaskInfo
import io.github.nytka_labeler.remind.Reminder
import io.github.nytka_labeler.remind.ReminderNotifier
import io.github.nytka_labeler.settings.Settings
import io.github.nytka_labeler.support.FakeSettings
import io.github.nytka_labeler.support.TOKEN
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ReminderTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-24T10:00:00Z"), ZoneOffset.UTC)
    private val notified = mutableListOf<Int>()
    private val notifier = ReminderNotifier { notified += it }

    private fun reminder(
        settings: Settings,
        tasks: List<TaskInfo>,
    ) = Reminder(
        client =
            object : io.github.nytka_labeler.api.LabelingClient {
                override suspend fun tasks() =
                    io.github.nytka_labeler.api.ApiResult
                        .Ok(tasks)

                override suspend fun next(
                    task: String,
                    limit: Int,
                ) = error("not used")

                override suspend fun clip(
                    id: Long,
                    context: Int,
                    target: java.io.File,
                ) = error("not used")

                override suspend fun voicePending() = error("not used")

                override suspend fun voiceConfirm(labelIds: List<Long>) = error("not used")

                override suspend fun answer(
                    itemId: Long,
                    body: String,
                ) = error("not used")
            },
        settings = FakeSettings(settings),
        notifier = notifier,
        clock = clock,
    )

    private val configured = Settings(serverUrl = "https://a.example/", token = TOKEN)
    private val open = listOf(TaskInfo("device", "source", open = 4), TaskInfo("quiet", "transcript", open = 3))

    @Test
    fun `notifies the open total when nothing was done today`() {
        assertEquals(7, runBlocking { reminder(configured.copy(lastSessionDay = "2026-10-23"), open).check() })
        assertEquals(listOf(7), notified)
    }

    @Test
    fun `stays quiet after a session today`() {
        assertNull(runBlocking { reminder(configured.copy(lastSessionDay = "2026-10-24"), open).check() })
        assertEquals(emptyList<Int>(), notified)
    }

    @Test
    fun `stays quiet when nothing is open, when switched off, or without a token`() {
        assertNull(runBlocking { reminder(configured, listOf(TaskInfo("device", "source", open = 0))).check() })
        assertNull(runBlocking { reminder(configured.copy(remindersEnabled = false), open).check() })
        assertNull(runBlocking { reminder(configured.copy(token = ""), open).check() })
        assertNull(runBlocking { reminder(configured.copy(tokenRefused = true), open).check() })
        assertEquals(emptyList<Int>(), notified)
    }

    @Test
    fun `the text counts clips`() {
        assertEquals("7 uncertain clips wait", Reminder.text(7))
        assertEquals("1 uncertain clip waits", Reminder.text(1))
    }
}
