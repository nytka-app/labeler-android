package io.github.nytka_labeler.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.nytka_labeler.session.WeekDay

/** Days with a session this week. Missing a day loses nothing, so there is no streak to break. */
@Composable
fun WeekStripView(week: List<WeekDay>) {
    Row(
        Modifier.fillMaxWidth().testTag("week_strip"),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        week.forEach { day ->
            val state = if (day.done) "session" else "no session"
            Column(
                Modifier.semantics { contentDescription = "${day.date}: $state" }.testTag("week_${day.date}"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(day.label, style = MaterialTheme.typography.labelSmall)
                val fill = if (day.done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                Row(Modifier.size(20.dp).clip(CircleShape).background(fill)) {}
            }
        }
    }
}
