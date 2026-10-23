package io.github.nytka_labeler.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun VoiceScreen(
    viewModel: VoiceViewModel,
    onExit: () -> Unit,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Confirm my voice", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Confirmed lines teach the voiceprint, and that cannot be undone.",
            Modifier.testTag("voice_warning"),
        )
        if (ui.loading) Text("Loading")
        ui.error?.let { Text(it, Modifier.testTag("voice_error"), color = MaterialTheme.colorScheme.error) }
        if (!ui.loading && ui.items.isEmpty()) Text("Nothing waits for confirmation.", Modifier.testTag("voice_empty"))
        ui.items.forEach { item ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    item.labelId in ui.checked,
                    { viewModel.toggle(item.labelId) },
                    Modifier.testTag("voice_${item.labelId}"),
                )
                Text("%.1f s".format(item.durationMs / 1000.0))
            }
        }
        ui.learned?.let { Text("Learned: $it", Modifier.testTag("voice_learned")) }
        if (ui.items.isNotEmpty()) {
            Button(
                onClick = viewModel::confirm,
                Modifier.fillMaxWidth().testTag("voice_confirm"),
                enabled = ui.checked.isNotEmpty() && !ui.busy,
            ) { Text("Confirm ${ui.checked.size}") }
        }
        TextButton(onClick = onExit) { Text("Back to tasks") }
    }
}
