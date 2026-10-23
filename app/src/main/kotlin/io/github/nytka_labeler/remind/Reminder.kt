package io.github.nytka_labeler.remind

import io.github.nytka_labeler.api.ApiResult
import io.github.nytka_labeler.api.LabelingClient
import io.github.nytka_labeler.settings.SettingsSource
import java.time.Clock
import java.time.LocalDate

fun interface ReminderNotifier {
    fun notifyOpen(open: Int)
}

/** Notifies "N uncertain clips wait" when something is open and no session was answered today. */
class Reminder(
    private val client: LabelingClient,
    private val settings: SettingsSource,
    private val notifier: ReminderNotifier,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    /** The open count when a notification was sent, else null. */
    suspend fun check(): Int? {
        val current = settings.current()
        if (!current.remindersEnabled || !current.configured || current.tokenRefused) return null
        if (current.lastSessionDay == LocalDate.now(clock).toString()) return null
        val open =
            when (val result = client.tasks()) {
                is ApiResult.Ok -> result.value.sumOf { it.open }
                is ApiResult.Failure -> return null
            }
        if (open <= 0) return null
        notifier.notifyOpen(open)
        return open
    }

    companion object {
        fun text(open: Int) = if (open == 1) "1 uncertain clip waits" else "$open uncertain clips wait"
    }
}
