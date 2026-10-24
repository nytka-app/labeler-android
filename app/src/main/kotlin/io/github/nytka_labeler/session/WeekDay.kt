package io.github.nytka_labeler.session

import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** One day of the week strip: it is lit when a session happened that day. Nothing is lost by missing one. */
data class WeekDay(
    val date: String,
    val label: String,
    val done: Boolean,
    val today: Boolean,
)

/** The seven days ending [today], oldest first, lit by the ISO dates in [days]. No streak is counted. */
fun weekStrip(
    today: LocalDate,
    days: Set<String>,
): List<WeekDay> =
    (6L downTo 0L).map { back ->
        val day = today.minusDays(back)
        WeekDay(
            date = day.toString(),
            label = day.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.ENGLISH),
            done = day.toString() in days,
            today = back == 0L,
        )
    }
