package io.github.nytka_labeler.remind

import android.content.Context

/** Turns the periodic check on or off; a fake in tests. */
fun interface ReminderControl {
    fun apply(enabled: Boolean)
}

class WorkReminderControl(
    private val context: Context,
) : ReminderControl {
    override fun apply(enabled: Boolean) = ReminderWorker.apply(context, enabled)
}
