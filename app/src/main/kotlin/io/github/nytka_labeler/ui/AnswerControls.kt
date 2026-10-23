package io.github.nytka_labeler.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.nytka_labeler.api.Ask
import io.github.nytka_labeler.api.Codes
import io.github.nytka_labeler.session.SessionUi
import io.github.nytka_labeler.session.SessionViewModel

private val SOURCE_LABELS =
    mapOf(
        "me" to "Me",
        "live" to "Live person",
        "device" to "Device",
        "call" to "Call",
        "none" to "No voice",
        "cant_tell" to "Can't tell",
        SessionViewModel.SEVERAL to "Several",
    )
private val TRANSCRIPT_LABELS =
    mapOf(
        "right" to "Right",
        "small_errors" to "Small errors",
        "wrong_words" to "Wrong words",
        "made_up" to "Made up",
        "cant_hear" to "Can't hear",
    )
private val OVERLAP_LABELS =
    mapOf("one" to "One", "two" to "Two", "three_plus" to "Three or more", "cant_tell" to "Can't tell")
private val DEVICE_LABELS =
    mapOf(
        "tv" to "TV",
        "phone" to "Phone",
        "laptop" to "Laptop",
        "car" to "Car",
        "assistant" to "Assistant",
        "other" to "Other",
    )
private val CONTENT_LABELS =
    mapOf(
        "speech" to "Speech",
        "singing" to "Singing",
        "vocal" to "Vocal sounds",
        "music" to "Music",
        "other_sound" to "Other sound",
        "silence" to "Silence",
    )
private val FLAG_LABELS =
    mapOf(
        "quiet" to "Quiet or far",
        "noisy" to "Noisy background",
        "overlap" to "Overlapping voices",
        "muffled" to "Muffled",
        "clipped" to "Clipped",
        "two_speakers" to "Two speakers in one line",
    )
private val LANGUAGE_LABELS =
    mapOf("uk" to "Ukrainian", "ru" to "Russian", "en" to "English", "mixed" to "Mixed", "other" to "Other")

/** The buttons of each asked axis, in the order of DESIGN.md. */
private fun buttonsFor(ask: String): Map<String, String> =
    when (ask) {
        Ask.SOURCE -> SOURCE_LABELS
        Ask.TRANSCRIPT -> TRANSCRIPT_LABELS
        else -> OVERLAP_LABELS
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Answers(
    card: SessionUi.Showing,
    vm: SessionViewModel,
) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        maxItemsInEachRow = 2,
    ) {
        buttonsFor(card.ask).forEach { (code, label) ->
            val open = card.draft.reveal == code
            val modifier = Modifier.weight(1f).heightIn(min = 64.dp).testTag("answer_$code")
            if (open) {
                FilledTonalButton(onClick = { vm.choose(code) }, modifier) { Text(label) }
            } else {
                Button(onClick = { vm.choose(code) }, modifier, colors = ButtonDefaults.buttonColors()) { Text(label) }
            }
        }
    }
    Panel(card, vm)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Panel(
    card: SessionUi.Showing,
    vm: SessionViewModel,
) {
    val draft = card.draft
    when (draft.reveal) {
        "device" -> {
            Text("Device type (optional)")
            ChipRow(DEVICE_LABELS, setOf(draft.deviceType).filterNotNull().toSet(), "device_type", vm::pickDeviceType)
            SaveButton(true, vm)
        }
        SessionViewModel.SEVERAL -> {
            Text("Pick up to two")
            val picks = Codes.SOURCE_PICKS.associateWith { SOURCE_LABELS.getValue(it) }
            ChipRow(picks, draft.pickedSources, "pick", vm::togglePick)
            SaveButton(draft.pickedSources.isNotEmpty(), vm)
        }
        "wrong_words" -> {
            if (card.lines.size == 1) {
                OutlinedTextField(
                    value = draft.correction,
                    onValueChange = vm::setCorrection,
                    label = { Text("What was said") },
                    modifier = Modifier.fillMaxWidth().testTag("correction"),
                )
            }
            SaveButton(true, vm)
        }
    }
}

@Composable
private fun SaveButton(
    enabled: Boolean,
    vm: SessionViewModel,
) {
    Button(onClick = vm::confirm, Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("save"), enabled = enabled) {
        Text("Save")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Optional(
    card: SessionUi.Showing,
    vm: SessionViewModel,
) {
    Text("Optional", style = MaterialTheme.typography.labelLarge)
    Text("Sound")
    ChipRow(CONTENT_LABELS, setOf(card.draft.content).filterNotNull().toSet(), "sound", vm::setContent)
    ChipRow(FLAG_LABELS, card.draft.flags, "flag", vm::toggleFlag)
    ChipRow(LANGUAGE_LABELS, setOf(card.draft.language).filterNotNull().toSet(), "lang", vm::setLanguage)
    if (card.lines.size > 1) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(card.draft.linesDiffer, { vm.toggleLinesDiffer() }, Modifier.testTag("lines_differ"))
            Text("  Lines differ")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(
    labels: Map<String, String>,
    selected: Set<String>,
    tagPrefix: String,
    onTap: (String) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEach { (code, label) ->
            FilterChip(
                selected = code in selected,
                onClick = { onTap(code) },
                label = { Text(label) },
                modifier = Modifier.testTag("${tagPrefix}_$code"),
            )
        }
    }
}
