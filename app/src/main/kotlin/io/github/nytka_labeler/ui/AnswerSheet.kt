package io.github.nytka_labeler.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.nytka_labeler.session.SessionUi
import io.github.nytka_labeler.session.SessionViewModel
import io.github.nytka_labeler.session.Sheet

/**
 * The sheet over the feed. Quick holds the values a swipe or a rail button left open (the remaining answers of a
 * "No", or the count of voices for a "Yes"); Full is the long-press sheet with every value and the optional details.
 * It lives in the composition, not in a window, so it is part of the screen for the tests and for screen readers.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AnswerSheet(
    state: SessionUi.Showing,
    vm: SessionViewModel,
) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(onClickLabel = "Close", onClick = vm::dismissSheet)
                .testTag("sheet_scrim"),
        )
        Surface(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .testTag("sheet"),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(state.current.question, style = MaterialTheme.typography.titleLarge)
                if (state.sheet == Sheet.Quick) {
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        state.quickOptions.forEach { code ->
                            Button(
                                onClick = { vm.choose(code) },
                                Modifier.heightIn(min = 56.dp).testTag("answer_$code"),
                            ) { Text(optionLabel(code)) }
                        }
                    }
                    Panel(state, vm)
                } else {
                    Answers(state, vm)
                    Optional(state, vm)
                }
                TextButton(onClick = vm::dismissSheet, Modifier.testTag("sheet_close")) { Text("Close") }
            }
        }
    }
}
