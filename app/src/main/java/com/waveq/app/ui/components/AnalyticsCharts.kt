package com.waveq.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.waveq.app.ui.theme.AppTypography
import com.waveq.app.ui.theme.Dimens
import com.waveq.app.ui.theme.StatNumberStyle
import com.waveq.app.ui.theme.appExtraColors

/**
 * Canvas-drawn charts. Merged in from the base branch and rewired onto the
 * theme-aware colour system - the originals read the raw light-mode vals
 * directly (TextPrimary, SurfaceMuted, AccentBlue), which would have stayed
 * light-on-light in dark mode.
 *
 * Colours for slices and bars are passed in by the caller, because the only
 * multi-hue system this UI keeps is the severity scale and the caller is the
 * one that knows which severity a datum belongs to.
 */

data class ChartSlice(val label: String, val value: Float, val color: Color)

data class BarItem(val label: String, val value: Float, val color: Color)

/** Donut with a centred total and a legend column. */
@Composable
fun DonutChart(
    data: List<ChartSlice>,
    modifier: Modifier = Modifier,
    strokeWidth: Float = 36f,
) {
    val total = data.sumOf { it.value.toDouble() }.toFloat().coerceAtLeast(1f)
    val animatedProgress = remember { Animatable(0f) }

    LaunchedEffect(data) {
        animatedProgress.snapTo(0f)
        animatedProgress.animateTo(1f, animationSpec = tween(durationMillis = 800))
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(Dimens.cardPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(modifier = Modifier.size(140.dp), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                var startAngle = -90f
                data.forEach { slice ->
                    val sweepAngle = (slice.value / total) * 360f * animatedProgress.value
                    drawArc(
                        color = slice.color,
                        startAngle = startAngle,
                        sweepAngle = sweepAngle,
                        useCenter = false,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                        topLeft = Offset(strokeWidth / 2, strokeWidth / 2),
                        size = Size(size.width - strokeWidth, size.height - strokeWidth),
                    )
                    startAngle += sweepAngle
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${data.sumOf { it.value.toInt() }}",
                    style = StatNumberStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                MicroLabel("Total")
            }
        }

        Column(
            modifier = Modifier.padding(start = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            data.forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(item.color),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${item.label} (${item.value.toInt()})",
                        style = AppTypography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/** Horizontal bars, one row per category. */
@Composable
fun HorizontalBarChart(
    items: List<BarItem>,
    modifier: Modifier = Modifier,
) {
    val maxValue = items.maxOfOrNull { it.value }?.coerceAtLeast(1f) ?: 1f
    val animatedProgress = remember { Animatable(0f) }
    val trackColor = MaterialTheme.colorScheme.surfaceVariant

    LaunchedEffect(items) {
        animatedProgress.snapTo(0f)
        animatedProgress.animateTo(1f, animationSpec = tween(durationMillis = 800))
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(Dimens.cardPadding),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items.forEach { bar ->
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = bar.label,
                        style = AppTypography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "${bar.value.toInt()}",
                        style = AppTypography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(trackColor),
                ) {
                    val fraction = (bar.value / maxValue) * animatedProgress.value
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(5.dp))
                            .background(bar.color),
                    )
                }
            }
        }
    }
}

/**
 * Smoothed line with a gradient fill under the curve.
 *
 * [xLabels] is caller-supplied rather than hardcoded to clock times, so the
 * same chart serves a 24h incident trend and an hourly rainfall forecast.
 * Pass an empty list to omit the axis row entirely.
 */
@Composable
fun SparklineTrendChart(
    points: List<Float>,
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.primary,
    xLabels: List<String> = emptyList(),
) {
    val animatedProgress = remember { Animatable(0f) }
    val dotCore = MaterialTheme.colorScheme.surface

    LaunchedEffect(points) {
        animatedProgress.snapTo(0f)
        animatedProgress.animateTo(1f, animationSpec = tween(durationMillis = 1000))
    }

    Column(modifier = modifier.fillMaxWidth().padding(Dimens.cardPadding)) {
        Canvas(modifier = Modifier.fillMaxWidth().height(120.dp)) {
            if (points.size < 2) return@Canvas

            val maxVal = (points.maxOrNull() ?: 1f).coerceAtLeast(1f)
            val range = maxVal.coerceAtLeast(0.0001f)
            val stepX = size.width / (points.size - 1)
            val path = Path()
            val fillPath = Path()

            points.forEachIndexed { i, value ->
                val x = i * stepX
                val y = size.height - (value * animatedProgress.value / range * (size.height - 20f))

                if (i == 0) {
                    path.moveTo(x, y)
                    fillPath.moveTo(x, size.height)
                    fillPath.lineTo(x, y)
                } else {
                    val prevX = (i - 1) * stepX
                    val prevVal = points[i - 1] * animatedProgress.value
                    val prevY = size.height - (prevVal / range * (size.height - 20f))
                    val cx = (prevX + x) / 2
                    path.cubicTo(cx, prevY, cx, y, x, y)
                    fillPath.cubicTo(cx, prevY, cx, y, x, y)
                }
            }

            fillPath.lineTo(size.width, size.height)
            fillPath.close()

            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(lineColor.copy(alpha = 0.35f), Color.Transparent),
                ),
            )
            drawPath(
                path = path,
                color = lineColor,
                style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round),
            )

            points.forEachIndexed { i, value ->
                val x = i * stepX
                val y = size.height - (value * animatedProgress.value / range * (size.height - 20f))
                drawCircle(color = dotCore, radius = 4.dp.toPx(), center = Offset(x, y))
                drawCircle(color = lineColor, radius = 2.5.dp.toPx(), center = Offset(x, y))
            }
        }

        if (xLabels.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                xLabels.forEach { label ->
                    Text(
                        text = label,
                        style = AppTypography.bodySmall,
                        color = MaterialTheme.appExtraColors.textTertiary,
                    )
                }
            }
        }
    }
}
