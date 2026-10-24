package io.github.nytka_labeler.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.nytka_labeler.api.Via
import io.github.nytka_labeler.session.CardUi
import kotlin.math.roundToInt

/** What a page of the feed is showing. */
internal data class PageState(
    val card: CardUi,
    val current: Boolean,
    /** On the loudspeaker the clip waits for a tap. */
    val waitsForTap: Boolean,
)

/** What a gesture on the page does; the rail calls the same view-model methods. */
internal data class PageActions(
    val yes: (String) -> Unit,
    val no: (String) -> Unit,
    val replay: () -> Unit,
    val more: () -> Unit,
)

private enum class Rest { Left, Center, Right }

/** How far the card follows the finger, as a share of its width. */
private const val TRAVEL = 0.5f

/**
 * One clip on a full page: the waveform, the captions and the fixed question. A tap replays, a long-press opens the
 * full answer sheet, a horizontal swipe answers (right is yes, left is no) and the pager owns the vertical swipes.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FeedPage(
    page: PageState,
    positionMs: Long,
    enabled: Boolean,
    actions: PageActions,
) {
    val card = page.card
    val drag = remember { AnchoredDraggableState(Rest.Center) }
    LaunchedEffect(drag, enabled) {
        snapshotFlow { drag.settledValue }.collect { rest ->
            if (rest != Rest.Center && enabled) {
                if (rest == Rest.Right) actions.yes(Via.SWIPE) else actions.no(Via.SWIPE)
            }
            if (rest != Rest.Center) drag.animateTo(Rest.Center)
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                val reach = size.width * TRAVEL
                drag.updateAnchors(
                    DraggableAnchors {
                        Rest.Left at -reach
                        Rest.Center at 0f
                        Rest.Right at reach
                    },
                )
            }.offset { IntOffset(drag.offset.takeUnless { it.isNaN() }?.roundToInt() ?: 0, 0) }
            .anchoredDraggable(drag, Orientation.Horizontal, enabled = enabled)
            .combinedClickable(
                enabled = enabled,
                onClickLabel = "Play again",
                onClick = actions.replay,
                onLongClickLabel = "More answers",
                onLongClick = actions.more,
            ).testTag("card_${card.itemId}"),
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (card.taskChange) {
                Text(
                    taskName(card.task),
                    Modifier.testTag("task_change_${card.itemId}"),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Captions(card, positionMs, page.current)
            Waveform(card, positionMs)
            if (page.waitsForTap) {
                Text(
                    "Tap to play (speaker)",
                    Modifier.testTag("tap_to_play"),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        Text(
            card.question,
            Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)
                .testTag("question_${card.itemId}"),
            style = MaterialTheme.typography.headlineSmall,
        )
    }
}

/** The line being spoken is bright, the other asked lines fade, and the context lines around them are dim. */
@Composable
internal fun Captions(
    card: CardUi,
    positionMs: Long,
    current: Boolean,
) {
    val active = if (current) activeLine(card, positionMs) else null
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        card.before.forEach { Caption(it.text, CONTEXT_ALPHA, false, null) }
        card.lines.forEachIndexed { i, line ->
            val alpha = if (active == null || active == i) 1f else FADED_ALPHA
            Caption(line.text, alpha, active == i, "caption_${card.itemId}_$i")
        }
        card.after.forEach { Caption(it.text, CONTEXT_ALPHA, false, null) }
    }
}

private const val CONTEXT_ALPHA = 0.35f
private const val FADED_ALPHA = 0.6f

@Composable
private fun Caption(
    text: String,
    alpha: Float,
    speaking: Boolean,
    tag: String?,
) {
    val base = if (tag != null) Modifier.testTag(tag) else Modifier
    Text(
        text,
        base.semantics { if (speaking) contentDescription = "speaking: $text" },
        style = MaterialTheme.typography.titleLarge,
        color =
            if (speaking) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
            },
    )
}

/** Index of the asked line that [positionMs] falls in, or null between lines and before the clip plays. */
internal fun activeLine(
    card: CardUi,
    positionMs: Long,
): Int? = card.lines.indexOfFirst { positionMs >= it.startMs && positionMs < it.endMs }.takeIf { it >= 0 }
