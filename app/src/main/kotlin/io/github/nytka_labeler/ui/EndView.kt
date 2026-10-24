package io.github.nytka_labeler.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.nytka_labeler.session.GateProgress
import io.github.nytka_labeler.session.SessionUi
import io.github.nytka_labeler.session.WeekDay

@Composable
internal fun EndView(
    state: SessionUi.Ended,
    onAnother: () -> Unit,
    back: () -> Unit,
    done: () -> Unit,
) {
    val summary = state.summary
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Session done", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("end_title"))
        Text("${summary.answered} answered, ${summary.skipped} skipped", Modifier.testTag("end_counts"))
        Text("${summary.saved} saved on the server", Modifier.testTag("end_saved"))
        if (summary.waiting > 0) {
            Text(
                "${summary.waiting} answers still sending. They go out when there is a network.",
                Modifier.testTag("end_waiting"),
            )
        }
        if (summary.answered > 0) {
            Text(
                "${summary.disagreed} of ${summary.answered} answers disagreed with the model, the most useful ones.",
                Modifier.testTag("end_disagreed"),
            )
        }
        summary.gates.forEach { Gate(it) }
        summary.refused.forEach { Text("Refused: $it", color = MaterialTheme.colorScheme.error) }
        Week(state.week)
        Button(onClick = onAnother, Modifier.fillMaxWidth().testTag("another")) { Text("Another ${summary.goal}") }
        TextButton(onClick = done, Modifier.testTag("done")) { Text("Back to tasks") }
        TextButton(onClick = back, Modifier.testTag("end_back")) { Text("Change the last answer") }
    }
}

@Composable
private fun Gate(gate: GateProgress) {
    Text(
        "${taskName(gate.task)}: ${gate.before} → ${gate.after} of ${gate.goal} ${gate.goalLabel}".trim(),
        Modifier.testTag("gate_${gate.task}"),
    )
}

@Composable
private fun Week(week: List<WeekDay>) {
    Text("Days with a session this week", style = MaterialTheme.typography.labelLarge)
    WeekStripView(week)
}
