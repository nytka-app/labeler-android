package io.github.nytka_labeler.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.nytka_labeler.session.SessionUi
import io.github.nytka_labeler.session.SessionViewModel

/** Clips kept composed around the current one, so a swipe never waits for a waveform. */
private const val PAGES_AHEAD = 3

@Composable
fun SessionScreen(
    viewModel: SessionViewModel,
    onExit: () -> Unit,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    // A clip must not keep playing behind another app.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.pausePlayback() }
    LaunchedEffect(viewModel) {
        viewModel.haptics.collect { yes ->
            haptic.performHapticFeedback(if (yes) HapticFeedbackType.Confirm else HapticFeedbackType.Reject)
        }
    }
    Box(Modifier.fillMaxSize()) {
        when (val state = ui) {
            is SessionUi.Loading -> Pad { Loading(state) }
            is SessionUi.Failed -> Pad { Failed(state.message, viewModel::load, onExit) }
            SessionUi.Empty -> Pad { Empty(onExit) }
            is SessionUi.Showing -> Feed(state, viewModel, onExit)
            is SessionUi.Ended ->
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        EndView(
                            state,
                            onAnother = viewModel::another,
                            back = viewModel::back,
                            done = {
                                viewModel.leave()
                                onExit()
                            },
                        )
                    }
                    if (state.undo) UndoBar(viewModel::undo)
                }
        }
    }
}

@Composable
private fun Pad(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) { content() }
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

/** The vertical feed: a top bar, one clip per page, the undo bar and the rail. */
@Composable
private fun Feed(
    state: SessionUi.Showing,
    vm: SessionViewModel,
    onExit: () -> Unit,
) {
    val pager = rememberPagerState(initialPage = state.index) { state.cards.size }
    // A swipe settles the pager and tells the view model; an answer, Undo or Back moves the pager the other way.
    LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.collect(vm::onPage) }
    LaunchedEffect(state.index) { if (pager.settledPage != state.index) pager.animateScrollToPage(state.index) }
    val position by vm.position.collectAsStateWithLifecycle()
    val actions = PageActions(yes = vm::yes, no = vm::no, replay = vm::replay, more = vm::openFull)
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar(state) {
                vm.leave()
                onExit()
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                VerticalPager(
                    pager,
                    Modifier.fillMaxSize().testTag("feed"),
                    beyondViewportPageCount = PAGES_AHEAD,
                    userScrollEnabled = state.sheet == null,
                    key = { state.cards[it].itemId },
                ) { page ->
                    val current = page == state.index
                    FeedPage(
                        PageState(state.cards[page], current, current && state.waitsForTap),
                        if (current) position else 0L,
                        enabled = current && state.sheet == null,
                        actions = actions,
                    )
                }
                state.flash?.let { Flash(it, Modifier.align(Alignment.TopCenter)) }
            }
            if (state.undo) UndoBar(vm::undo)
            Rail(vm)
        }
        if (state.sheet != null) AnswerSheet(state, vm)
    }
}

@Composable
private fun Flash(
    text: String,
    modifier: Modifier,
) {
    Surface(
        modifier.padding(top = 8.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.inverseSurface,
    ) {
        Text(
            text,
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("flash"),
            color = MaterialTheme.colorScheme.inverseOnSurface,
        )
    }
}

@Composable
private fun TopBar(
    state: SessionUi.Showing,
    onExit: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            taskName(state.current.task),
            Modifier.weight(1f).testTag("task_name"),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "${state.position} of ${state.total}",
            Modifier.testTag("progress"),
            style = MaterialTheme.typography.titleMedium,
        )
        CircularProgressIndicator(
            progress = { (state.answeredCount.toFloat() / state.goal).coerceIn(0f, 1f) },
            Modifier
                .padding(2.dp)
                .semantics { contentDescription = "${state.answeredCount} of ${state.goal} answered" }
                .testTag("goal_ring"),
        )
        TextButton(onClick = onExit, Modifier.testTag("leave")) { Text("Stop") }
    }
}
