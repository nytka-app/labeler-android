package io.github.nytka_labeler.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.nytka_labeler.api.Via
import io.github.nytka_labeler.session.SessionViewModel

/** The buttons that do what the gestures do, for screen readers, switch control and anyone who does not swipe. */
@Composable
internal fun Rail(vm: SessionViewModel) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RailButton("Yes", "rail_yes", Modifier.weight(1f)) { vm.yes(Via.BUTTON) }
        RailButton("No", "rail_no", Modifier.weight(1f)) { vm.no(Via.BUTTON) }
        RailButton("Skip", "rail_skip", Modifier.weight(1f)) { vm.skip(Via.BUTTON) }
        RailButton("More", "rail_more", Modifier.weight(1f)) { vm.openFull() }
    }
}

@Composable
private fun RailButton(
    label: String,
    tag: String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Button(onClick = onClick, modifier.testTag(tag)) { Text(label) }
}

@Composable
internal fun UndoBar(onUndo: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("undo_bar"),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.inverseSurface,
    ) {
        Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Answer saved", Modifier.weight(1f), color = MaterialTheme.colorScheme.inverseOnSurface)
            TextButton(onClick = onUndo, Modifier.testTag("undo")) {
                Text("Undo", color = MaterialTheme.colorScheme.inversePrimary)
            }
        }
    }
}
