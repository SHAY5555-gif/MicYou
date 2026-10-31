package com.lanrhyme.micyou.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** A vertical channel fader: high values at the top, low values at the bottom. */
@Composable
fun MixerFader(
    label: String,
    display: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    minimumLabel: String,
    maximumLabel: String,
    accent: Color,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onChange: (Float) -> Unit
) {
    val latestOnChange by rememberUpdatedState(onChange)
    var trackHeight by remember { mutableFloatStateOf(0f) }
    val thumbHeight = if (compact) 24.dp else 32.dp
    val thumbPx = with(LocalDensity.current) { thumbHeight.toPx() }
    val fraction = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
    val travel = (trackHeight - thumbPx).coerceAtLeast(0f)
    val thumbTop = travel * (1f - fraction)

    Column(
        modifier.background(Color(0xFF171D19), RoundedCornerShape(14.dp))
            .border(1.dp, Color(0xFF3B453D), RoundedCornerShape(14.dp))
            .padding(horizontal = 8.dp, vertical = if (compact) 6.dp else 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp)
    ) {
        Box(Modifier.width(24.dp).height(3.dp).background(accent, RoundedCornerShape(2.dp)))
        Text(
            label, color = Color(0xFFD9E2D9),
            style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center, minLines = if (compact) 1 else 2,
            maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            display, color = accent, fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold, fontSize = if (compact) 14.sp else 18.sp,
            lineHeight = if (compact) 18.sp else 22.sp, maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
                .background(Color(0xFF0C110E), RoundedCornerShape(6.dp))
                .padding(vertical = if (compact) 4.dp else 6.dp)
        )
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f)
                .onSizeChanged { trackHeight = it.height.toFloat() }
                .semantics {
                    contentDescription = label
                    stateDescription = display
                    progressBarRangeInfo = ProgressBarRangeInfo(value, range)
                    setProgress { requested ->
                        latestOnChange(requested.coerceIn(range))
                        true
                    }
                }
                .pointerInput(range, thumbPx) {
                    fun updateAt(y: Float) {
                        val usableHeight = (trackHeight - thumbPx).coerceAtLeast(1f)
                        val position = ((y - thumbPx / 2f) / usableHeight).coerceIn(0f, 1f)
                        latestOnChange(range.start + (1f - position) * (range.endInclusive - range.start))
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        updateAt(down.position.y)
                        down.consume()
                        do {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            updateAt(change.position.y)
                            change.consume()
                        } while (true)
                    }
                },
            contentAlignment = Alignment.TopCenter
        ) {
            Canvas(Modifier.matchParentSize()) {
                val center = size.width / 2f
                val start = thumbPx / 2f
                val length = (size.height - thumbPx).coerceAtLeast(0f)
                for (index in 0..10) {
                    val y = start + length * index / 10f
                    val tick = if (index % 5 == 0) 15.dp.toPx() else 9.dp.toPx()
                    val gap = 15.dp.toPx()
                    drawLine(Color(0xFF5A665D), Offset(center - gap - tick, y), Offset(center - gap, y), 1.dp.toPx())
                    drawLine(Color(0xFF5A665D), Offset(center + gap, y), Offset(center + gap + tick, y), 1.dp.toPx())
                }
                drawRoundRect(
                    Color(0xFF080C09), Offset(center - 6.dp.toPx(), start),
                    Size(12.dp.toPx(), length), CornerRadius(6.dp.toPx())
                )
                val filled = length * fraction
                drawRoundRect(
                    accent.copy(alpha = 0.65f), Offset(center - 2.dp.toPx(), start + length - filled),
                    Size(4.dp.toPx(), filled), CornerRadius(2.dp.toPx())
                )
            }
            Box(
                Modifier.offset { IntOffset(0, thumbTop.roundToInt()) }
                    .width(64.dp).height(thumbHeight)
                    .shadow(5.dp, RoundedCornerShape(6.dp))
                    .background(
                        Brush.verticalGradient(listOf(Color(0xFFE2E7E1), Color(0xFFACB5AA), Color(0xFF758172))),
                        RoundedCornerShape(6.dp)
                    )
                    .border(1.dp, Color(0xFFC4CDC1), RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center
            ) {
                Box(Modifier.fillMaxWidth().height(3.dp).background(Color(0xFF374332)))
                Box(Modifier.width(18.dp).height(3.dp).background(accent))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(minimumLabel, color = Color(0xFF8C998E), fontSize = 10.sp,
                lineHeight = 12.sp, fontFamily = FontFamily.Monospace)
            Text(maximumLabel, color = Color(0xFF8C998E), fontSize = 10.sp,
                lineHeight = 12.sp, fontFamily = FontFamily.Monospace)
        }
    }
}
