package io.github.nytka_labeler.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nytka_labeler.api.TaskInfo

private val TASK_NAMES =
    mapOf(
        "device" to "Device or live voice",
        "voice" to "My voice",
        "quiet" to "Quiet lines",
        "transcript" to "Transcript",
        "overlap" to "Overlap",
    )

const val HEADPHONE_HINT = "Device audio is easier to hear through headphones"

@Composable
fun TasksScreen(
    viewModel: TasksViewModel,
    onStart: (String) -> Unit,
    onSetup: () -> Unit,
    onVoice: () -> Unit,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    AskForNotifications(ui.reminders)
    TasksContent(
        ui,
        onStart,
        onSetup,
        onVoice,
        viewModel::refresh,
        viewModel::setReminders,
        viewModel::dismissRefused,
    )
}

@Composable
private fun AskForNotifications(remindersOn: Boolean) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(remindersOn) {
        if (remindersOn && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
fun TasksContent(
    ui: TasksUi,
    onStart: (String) -> Unit,
    onSetup: () -> Unit,
    onVoice: () -> Unit,
    onRefresh: () -> Unit,
    onReminders: (Boolean) -> Unit,
    onDismissRefused: (String) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Nytka Labeler", style = MaterialTheme.typography.headlineSmall)
        if (!ui.headphones) {
            Text(HEADPHONE_HINT, Modifier.testTag("headphone_hint"), style = MaterialTheme.typography.bodyMedium)
        }
        if (ui.needsToken) {
            Text("The server refused the token. Save a token of the labeling scope.", Modifier.testTag("token_refused"))
            Button(onClick = onSetup, Modifier.testTag("open_setup")) { Text("Enter a token") }
        }
        ui.error?.takeIf { !ui.needsToken }?.let {
            Text(it, Modifier.testTag("tasks_error"), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onRefresh) { Text("Try again") }
        }
        if (ui.waiting > 0) Text("${ui.waiting} answers wait for the network", Modifier.testTag("waiting"))
        ui.refused.forEach { row ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Refused: ${row.error.orEmpty()}", Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { onDismissRefused(row.clientId) }) { Text("Dismiss") }
            }
        }
        if (ui.loading) Text("Loading tasks")
        ui.tasks.forEach { TaskCard(it, onStart) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Remind me when clips wait", Modifier.weight(1f))
            Switch(ui.reminders, onReminders, Modifier.testTag("reminders"))
        }
        TextButton(onClick = onVoice, Modifier.testTag("open_voice")) { Text("Confirm my voice") }
        TextButton(onClick = onSetup, Modifier.testTag("settings")) { Text("Server and token") }
    }
}

@Composable
private fun TaskCard(
    task: TaskInfo,
    onStart: (String) -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().testTag("task_${task.task}").clickable(enabled = task.open > 0) { onStart(task.task) },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(TASK_NAMES[task.task] ?: task.task, style = MaterialTheme.typography.titleMedium)
            Text("${task.open} open, ${task.labelled} labelled")
            task.goal?.takeIf { it > 0 }?.let { goal ->
                LinearProgressIndicator(
                    progress = { (task.labelled.toFloat() / goal).coerceIn(0f, 1f) },
                    Modifier.fillMaxWidth(),
                )
                Text(
                    "${task.labelled} of $goal ${task.goalLabel.orEmpty()}".trim(),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
