package io.github.nytka_labeler.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.nytka_labeler.session.CardUi

/** How much of the clip has played, 0 to 1. */
internal fun playedFraction(
    positionMs: Long,
    durationMs: Long,
): Float = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

/** The clip's `peaks` as bars, filled up to the playhead; a flat bar when the server sent none. */
@Composable
internal fun Waveform(
    card: CardUi,
    positionMs: Long,
) {
    val played = playedFraction(positionMs, card.durationMs)
    val filled = MaterialTheme.colorScheme.primary
    val rest = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(120.dp)
            .semantics {
                contentDescription = "Waveform"
                progressBarRangeInfo = ProgressBarRangeInfo(played, 0f..1f)
            }.testTag("waveform_${card.itemId}"),
    ) {
        val peaks = card.peaks
        if (peaks == null) {
            val bar = 6.dp.toPx()
            val top = (size.height - bar) / 2
            drawRoundRect(rest, Offset(0f, top), Size(size.width, bar), CornerRadius(bar / 2))
            drawRoundRect(filled, Offset(0f, top), Size(size.width * played, bar), CornerRadius(bar / 2))
        } else {
            val slot = size.width / peaks.size
            val width = slot * 0.6f
            peaks.forEachIndexed { i, peak ->
                val h = maxOf(peak * size.height, 4.dp.toPx())
                val x = i * slot + (slot - width) / 2
                val color = if ((i + 0.5f) / peaks.size <= played) filled else rest
                drawRoundRect(color, Offset(x, (size.height - h) / 2), Size(width, h), CornerRadius(width / 2))
            }
        }
    }
}
