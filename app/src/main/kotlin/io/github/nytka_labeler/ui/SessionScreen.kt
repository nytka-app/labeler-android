package io.github.nytka_labeler.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nytka_labeler.session.AnsweredCard
import io.github.nytka_labeler.session.EndSummary
import io.github.nytka_labeler.session.SaveState
import io.github.nytka_labeler.session.SessionUi
import io.github.nytka_labeler.session.SessionViewModel

@Composable
fun SessionScreen(
    viewModel: SessionViewModel,
    onExit: () -> Unit,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    // A clip must not keep playing behind another app.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.pausePlayback() }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        when (val state = ui) {
            is SessionUi.Loading -> Loading(state)
            is SessionUi.Failed -> Failed(state.message, viewModel::load, onExit)
            SessionUi.Empty -> Empty(onExit)
            is SessionUi.Showing -> CardView(state, viewModel, onExit)
            is SessionUi.Ended ->
                EndView(state.summary, viewModel::back) {
                    viewModel.leave()
                    onExit()
                }
        }
    }
}

@Composable
private fun Loading(state: SessionUi.Loading) {
    Text(
        if (state.total == 0) "Getting clips" else "Downloading clips: ${state.done} of ${state.total}",
        Modifier.testTag("loading"),
        style = MaterialTheme.typography.titleMedium,
    )
}

@Composable
private fun Failed(
    message: String,
    retry: () -> Unit,
    onExit: () -> Unit,
) {
    Text(message, Modifier.testTag("failed"), color = MaterialTheme.colorScheme.error)
    Button(onClick = retry, Modifier.testTag("retry")) { Text("Try again") }
    TextButton(onClick = onExit) { Text("Back to tasks") }
}

@Composable
private fun Empty(onExit: () -> Unit) {
    Text("Nothing is waiting in this task.", Modifier.testTag("empty"))
    Button(onClick = onExit) { Text("Back to tasks") }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardView(
    card: SessionUi.Showing,
    vm: SessionViewModel,
    onExit: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${card.position} of ${card.total}",
                Modifier.testTag("progress"),
                style = MaterialTheme.typography.titleMedium,
            )
            card.previous?.let {
                Text(
                    "  previous: ${statusText(it)}",
                    Modifier.testTag("previous_status"),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        card.answered?.let {
            Text(statusText(it), Modifier.testTag("card_status"), color = MaterialTheme.colorScheme.primary)
        }
        card.lines.forEach { Text(it, Modifier.testTag("line_text"), style = MaterialTheme.typography.titleLarge) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = vm::playPause,
                Modifier.testTag("play"),
            ) { Text(if (card.playing) "Pause" else "Replay") }
            if (card.hasContext) {
                OutlinedButton(onClick = vm::toggleContext, Modifier.testTag("context")) {
                    Text(if (card.showingContext) "Clip only" else "Context")
                }
            }
        }
        Answers(card, vm)
        Optional(card, vm)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = vm::back, Modifier.testTag("back"), enabled = card.position > 1) { Text("Back") }
            TextButton(onClick = vm::skip, Modifier.testTag("skip")) { Text("Skip") }
            TextButton(onClick = {
                vm.leave()
                onExit()
            }, Modifier.testTag("leave")) { Text("Stop") }
        }
    }
}

private fun statusText(card: AnsweredCard) =
    when (card.state) {
        SaveState.Saved -> if (card.skipped) "Skipped (saved)" else "Saved ✓"
        SaveState.Waiting -> "Waiting to send"
        SaveState.Refused -> "Refused: ${card.refusal.orEmpty()}"
    }

@Composable
private fun EndView(
    summary: EndSummary,
    back: () -> Unit,
    done: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Session done", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("end_title"))
        Text(
            "${summary.answered} answered, ${summary.skipped} skipped",
            Modifier.testTag("end_counts"),
        )
        Text("${summary.saved} saved on the server", Modifier.testTag("end_saved"))
        if (summary.waiting > 0) {
            Text(
                "${summary.waiting} waiting to send. They go out when there is a network.",
                Modifier.testTag("end_waiting"),
            )
        }
        summary.refused.forEach { Text("Refused: $it", color = MaterialTheme.colorScheme.error) }
        Button(onClick = done, Modifier.testTag("done")) { Text("Back to tasks") }
        TextButton(onClick = back, Modifier.testTag("end_back")) { Text("Change the last answer") }
    }
}
