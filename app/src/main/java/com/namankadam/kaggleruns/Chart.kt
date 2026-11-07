package com.namankadam.kaggleruns

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/** Title, subtitle and either the chart or its table view. A single series, so no legend: the title names it. */
@Composable
fun ChartSection(
    title: String,
    subtitle: String,
    points: List<Pair<Long, Double>>,
    invertY: Boolean,
    format: (Double) -> String,
) {
    var showTable by rememberSaveable(title) { mutableStateOf(false) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (points.size >= 2) {
                TextButton(onClick = { showTable = !showTable }) { Text(if (showTable) "Chart" else "Table") }
            }
        }
        when {
            points.size < 2 -> Text(
                "Not enough history yet. A reading is saved on each refresh, so this fills in over the next few hours.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            showTable -> HistoryTable(points, format)
            else -> HistoryChart(points, invertY, format, title)
        }
    }
}

/** Plot geometry shared by drawing and hit-testing, so a touch maps to the point under the finger. */
private class Geometry(width: Float, height: Float, density: Density, private val points: List<Pair<Long, Double>>, private val invertY: Boolean) {
    val left = with(density) { 52.dp.toPx() }
    val right = width - with(density) { 10.dp.toPx() }
    val top = with(density) { 30.dp.toPx() } // room for the tooltip
    val bottom = height - with(density) { 22.dp.toPx() } // x-axis band, inside the container
    private val tMin = points.first().first
    private val tMax = points.last().first
    val vMinRaw = points.minOf { it.second }
    val vMaxRaw = points.maxOf { it.second }
    private val pad = (vMaxRaw - vMinRaw).let { span -> if (span == 0.0) maxOf(abs(vMaxRaw) * 0.01, 1e-3) else span * 0.12 }
    private val vMin = vMinRaw - pad
    private val vMax = vMaxRaw + pad

    fun x(t: Long): Float = if (tMax == tMin) (left + right) / 2 else left + (t - tMin).toFloat() / (tMax - tMin) * (right - left)
    fun y(v: Double): Float {
        val f = ((v - vMin) / (vMax - vMin)).toFloat()
        return if (invertY) top + f * (bottom - top) else bottom - f * (bottom - top)
    }
    fun nearest(px: Float): Int = points.indices.minBy { abs(x(points[it].first) - px) }
}

@Composable
private fun HistoryChart(points: List<Pair<Long, Double>>, invertY: Boolean, format: (Double) -> String, title: String) {
    val context = LocalContext.current
    val colors = LocalAppColors.current
    val surface = MaterialTheme.colorScheme.surface
    val ink = MaterialTheme.colorScheme.onSurface
    val tooltipBg = MaterialTheme.colorScheme.surfaceContainerHigh
    val measurer = rememberTextMeasurer()
    var selected by remember(points) { mutableStateOf<Int?>(null) }

    val axisStyle = TextStyle(fontSize = 11.sp, color = colors.muted, fontFeatureSettings = "tnum")
    val labelStyle = TextStyle(fontSize = 12.sp, color = ink, fontWeight = FontWeight.SemiBold)
    val tooltipStyle = TextStyle(fontSize = 12.sp, color = ink)
    val sameDay = points.last().first - points.first().first < 24 * 3_600_000L
    fun axisDate(t: Long) = if (sameDay) formatClock(context, t) else formatDay(context, t)
    val first = points.first()
    val last = points.last()
    val summary = "$title: ${format(first.second)} on ${formatClock(context, first.first)}, " +
        "now ${format(last.second)} on ${formatClock(context, last.first)}. Switch to Table for every value."

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(190.dp)
            .semantics { contentDescription = summary }
            .pointerInput(points) {
                detectTapGestures { pos ->
                    val i = Geometry(size.width.toFloat(), size.height.toFloat(), this, points, invertY).nearest(pos.x)
                    selected = if (selected == i) null else i
                }
            }
            .pointerInput(points) {
                detectHorizontalDragGestures { change, _ ->
                    selected = Geometry(size.width.toFloat(), size.height.toFloat(), this, points, invertY).nearest(change.position.x)
                }
            },
    ) {
        val g = Geometry(size.width, size.height, this, points, invertY)
        val hairline = 1.dp.toPx()

        // Recessive solid hairline grid at the extremes and midpoint, labelled on the left.
        val ticks = listOf(g.vMinRaw, (g.vMinRaw + g.vMaxRaw) / 2, g.vMaxRaw).distinct()
        for (v in ticks) {
            val y = g.y(v)
            drawLine(colors.grid, Offset(g.left, y), Offset(g.right, y), strokeWidth = hairline)
            val text = measurer.measure(format(v), axisStyle)
            drawText(text, topLeft = Offset(g.left - text.size.width - 6.dp.toPx(), y - text.size.height / 2))
        }

        // X axis: first and last date, inside the container's axis band.
        val startLabel = measurer.measure(axisDate(first.first), axisStyle)
        val endLabel = measurer.measure(axisDate(last.first), axisStyle)
        val axisY = g.bottom + 6.dp.toPx()
        drawText(startLabel, topLeft = Offset(g.left, axisY))
        drawText(endLabel, topLeft = Offset(g.right - endLabel.size.width, axisY))

        // The series: 2px line, round joins and caps.
        val path = Path()
        points.forEachIndexed { i, (t, v) -> if (i == 0) path.moveTo(g.x(t), g.y(v)) else path.lineTo(g.x(t), g.y(v)) }
        drawPath(path, colors.series, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

        fun marker(t: Long, v: Double) {
            val c = Offset(g.x(t), g.y(v))
            drawCircle(surface, radius = 6.dp.toPx(), center = c) // 2dp surface ring
            drawCircle(colors.series, radius = 4.dp.toPx(), center = c)
        }

        val sel = selected
        if (sel == null) {
            // Direct label on the endpoint only.
            marker(last.first, last.second)
            val text = measurer.measure(format(last.second), labelStyle)
            val ex = (g.x(last.first) - text.size.width).coerceAtLeast(g.left)
            val ey = (g.y(last.second) - text.size.height - 8.dp.toPx()).coerceAtLeast(0f)
            drawText(text, topLeft = Offset(ex, ey))
        } else {
            // Crosshair and tooltip for the touched point.
            val (t, v) = points[sel]
            val x = g.x(t)
            drawLine(ink.copy(alpha = 0.35f), Offset(x, g.top), Offset(x, g.bottom), strokeWidth = hairline)
            marker(t, v)
            val text = measurer.measure("${format(v)}  ·  ${formatClock(context, t)}", tooltipStyle)
            val padX = 8.dp.toPx()
            val padY = 4.dp.toPx()
            val w = text.size.width + padX * 2
            val h = text.size.height + padY * 2
            val bx = (x - w / 2).coerceIn(0f, size.width - w)
            drawRoundRect(tooltipBg, topLeft = Offset(bx, 0f), size = Size(w, h), cornerRadius = CornerRadius(6.dp.toPx()))
            drawText(text, topLeft = Offset(bx + padX, padY))
        }
    }
}

/** The table view: every reading, newest first. */
@Composable
private fun HistoryTable(points: List<Pair<Long, Double>>, format: (Double) -> String) {
    val context = LocalContext.current
    Column(Modifier.padding(vertical = 4.dp)) {
        points.asReversed().take(30).forEach { (t, v) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text(
                    formatClock(context, t),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(format(v), style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"))
            }
        }
        if (points.size > 30) {
            Text(
                "Showing the latest 30 of ${points.size} readings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
