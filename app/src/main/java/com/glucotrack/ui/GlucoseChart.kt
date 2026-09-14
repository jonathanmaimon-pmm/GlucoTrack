package com.glucotrack.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glucotrack.analysis.GlucoseTargets
import com.glucotrack.analysis.GlucoseUnit
import com.glucotrack.data.GlucoseReading

/**
 * Line chart of glucose over a time window, with the target range shaded behind it.
 *
 * Two deliberate choices:
 *  - The y-axis is fixed to a clinically meaningful span rather than fitted to the data, so the
 *    same excursion looks the same size on every chart. An auto-fitted axis makes a flat day and
 *    a volatile one look identical.
 *  - The line breaks across gaps longer than [GAP_MILLIS] instead of interpolating, so a stretch
 *    with no scans reads as missing rather than as a smooth glide between two distant points.
 */
@Composable
fun GlucoseChart(
    readings: List<GlucoseReading>,
    targets: GlucoseTargets,
    unit: GlucoseUnit,
    windowStart: Long,
    windowEnd: Long,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val outline = MaterialTheme.colorScheme.outlineVariant
    val onSurface = MaterialTheme.colorScheme.onSurfaceVariant
    val line = MaterialTheme.colorScheme.primary

    if (readings.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                "No readings in this period",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    Canvas(modifier) {
        drawChart(
            readings = readings,
            targets = targets,
            unit = unit,
            windowStart = windowStart,
            windowEnd = windowEnd,
            measurer = measurer,
            gridColor = outline,
            labelColor = onSurface,
            lineColor = line,
        )
    }
}

private const val GAP_MILLIS = 20 * 60 * 1000L
private const val AXIS_MIN = 40.0
private const val AXIS_MAX = 260.0

private fun DrawScope.drawChart(
    readings: List<GlucoseReading>,
    targets: GlucoseTargets,
    unit: GlucoseUnit,
    windowStart: Long,
    windowEnd: Long,
    measurer: TextMeasurer,
    gridColor: Color,
    labelColor: Color,
    lineColor: Color,
) {
    val leftGutter = 38.dp.toPx()
    val bottomGutter = 18.dp.toPx()
    val plotWidth = (size.width - leftGutter).coerceAtLeast(1f)
    val plotHeight = (size.height - bottomGutter).coerceAtLeast(1f)
    val span = (windowEnd - windowStart).coerceAtLeast(1L)

    fun xOf(t: Long) = leftGutter + ((t - windowStart).toFloat() / span) * plotWidth
    fun yOf(mgdl: Double): Float {
        val clamped = mgdl.coerceIn(AXIS_MIN, AXIS_MAX)
        val fraction = (clamped - AXIS_MIN) / (AXIS_MAX - AXIS_MIN)
        return plotHeight - (fraction * plotHeight).toFloat()
    }

    // Target range, shaded.
    val top = yOf(targets.highMgdl)
    drawRect(
        color = BandColors.inRange.copy(alpha = 0.10f),
        topLeft = Offset(leftGutter, top),
        size = Size(plotWidth, yOf(targets.lowMgdl) - top),
    )

    // Range boundaries and axis labels.
    val labelStyle = TextStyle(fontSize = 10.sp, color = labelColor)
    listOf(targets.lowMgdl, targets.highMgdl).forEach { value ->
        val y = yOf(value)
        drawLine(gridColor, Offset(leftGutter, y), Offset(size.width, y), strokeWidth = 1f)
        val text = unit.format(value)
        val layout = measurer.measure(text, labelStyle)
        drawText(layout, topLeft = Offset(0f, y - layout.size.height / 2f))
    }

    // Glucose trace, broken wherever coverage is missing.
    val sorted = readings.sortedBy { it.timestamp }
    val path = Path()
    var penDown = false
    sorted.forEachIndexed { i, reading ->
        val x = xOf(reading.timestamp)
        val y = yOf(reading.mgdl)
        val gapped = i > 0 && reading.timestamp - sorted[i - 1].timestamp > GAP_MILLIS
        if (!penDown || gapped) {
            path.moveTo(x, y)
            penDown = true
        } else {
            path.lineTo(x, y)
        }
    }
    drawPath(
        path = path,
        color = lineColor,
        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
    )

    // Mark readings outside target so excursions are visible even where the line is dense.
    sorted.filter { it.mgdl > targets.highMgdl || it.mgdl < targets.lowMgdl }.forEach {
        drawCircle(
            color = BandColors.of(targets.classify(it.mgdl)),
            radius = 2.dp.toPx(),
            center = Offset(xOf(it.timestamp), yOf(it.mgdl)),
        )
    }

    // Time axis: start and end of the window.
    val startLabel = measurer.measure(formatTime(windowStart), labelStyle)
    drawText(startLabel, topLeft = Offset(leftGutter, plotHeight + 2.dp.toPx()))
    val endLabel = measurer.measure(formatTime(windowEnd), labelStyle)
    drawText(
        endLabel,
        topLeft = Offset(size.width - endLabel.size.width, plotHeight + 2.dp.toPx()),
    )
}
