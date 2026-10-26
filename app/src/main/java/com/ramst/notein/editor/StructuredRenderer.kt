package com.ramst.notein.editor

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.ceil

/** Shared by the editor, previews and PDF export, in the object's local coordinates. */
internal fun drawStructuredContent(canvas: Canvas, content: ContentObject) {
    content.shape?.let { drawShape(canvas, it) }
    content.graph?.let { drawGraph(canvas, it) }
    content.table?.let { drawTable(canvas, it) }
}

private fun drawShape(canvas: Canvas, shape: ShapeSpec) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = shape.color; strokeWidth = shape.strokeWidth
        style = if (shape.filled && shape.kind !in setOf(ShapeKind.LINE, ShapeKind.ARROW)) Paint.Style.FILL else Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    val inset = shape.strokeWidth / 2f + 1f
    val left = inset; val top = inset; val right = shape.width - inset; val bottom = shape.height - inset
    val cx = shape.width / 2f; val cy = shape.height / 2f
    val path = Path()
    when (shape.kind) {
        ShapeKind.SQUARE, ShapeKind.RECTANGLE -> canvas.drawRect(left, top, right, bottom, paint)
        ShapeKind.CIRCLE, ShapeKind.ELLIPSE -> canvas.drawOval(left, top, right, bottom, paint)
        ShapeKind.TRIANGLE -> { path.moveTo(cx, top); path.lineTo(right, bottom); path.lineTo(left, bottom); path.close(); canvas.drawPath(path, paint) }
        ShapeKind.RIGHT_TRIANGLE -> { path.moveTo(left, top); path.lineTo(right, bottom); path.lineTo(left, bottom); path.close(); canvas.drawPath(path, paint) }
        ShapeKind.DIAMOND -> { path.moveTo(cx, top); path.lineTo(right, cy); path.lineTo(cx, bottom); path.lineTo(left, cy); path.close(); canvas.drawPath(path, paint) }
        ShapeKind.LINE, ShapeKind.ARROW -> {
            canvas.drawLine(left, cy, right, cy, paint)
            if (shape.kind == ShapeKind.ARROW) {
                val tip = minOf(20f, shape.width / 4f, shape.height / 3f)
                path.moveTo(right - tip, cy - tip); path.lineTo(right, cy); path.lineTo(right - tip, cy + tip)
                canvas.drawPath(path, paint)
            }
        }
    }
}

private fun drawGraph(canvas: Canvas, graph: GraphSpec) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = Color.WHITE
    canvas.drawRect(0f, 0f, graph.width, graph.height, paint)
    val area = RectF(36f, 20f, graph.width - 24f, graph.height - 32f)
    fun x(value: Float) = area.left + (value - graph.xMin) / (graph.xMax - graph.xMin) * area.width()
    fun y(value: Float) = area.bottom - (value - graph.yMin) / (graph.yMax - graph.yMin) * area.height()
    val zeroX = x(0f); val zeroY = y(0f)
    fun ticks(min: Float, max: Float, step: Float): List<Float> {
        val first = ceil(min.toDouble() / step) * step
        return (0..100).map { (first + it * step.toDouble()).toFloat() }.takeWhile { it <= max + step * .001f }
    }
    val xTicks = ticks(graph.xMin, graph.xMax, graph.xStep)
    val yTicks = ticks(graph.yMin, graph.yMax, graph.yStep)
    if (graph.grid) {
        paint.color = 0xFFDDE5EA.toInt(); paint.strokeWidth = .6f
        xTicks.forEach { canvas.drawLine(x(it), area.top, x(it), area.bottom, paint) }
        yTicks.forEach { canvas.drawLine(area.left, y(it), area.right, y(it), paint) }
    }
    paint.color = 0xFF253B45.toInt(); paint.strokeWidth = 1.4f
    canvas.drawLine(area.left, zeroY, area.right + 8f, zeroY, paint)
    canvas.drawLine(zeroX, area.bottom, zeroX, area.top - 8f, paint)
    canvas.drawLine(area.right + 8f, zeroY, area.right + 3f, zeroY - 3f, paint)
    canvas.drawLine(area.right + 8f, zeroY, area.right + 3f, zeroY + 3f, paint)
    canvas.drawLine(zeroX, area.top - 8f, zeroX - 3f, area.top - 3f, paint)
    canvas.drawLine(zeroX, area.top - 8f, zeroX + 3f, area.top - 3f, paint)
    paint.textSize = 9f; paint.typeface = Typeface.DEFAULT
    fun label(value: Float) = if (value == value.toInt().toFloat()) value.toInt().toString() else "%.2f".format(java.util.Locale.US, value).trimEnd('0').trimEnd('.')
    // Keep labels readable even when the chosen grid has many intervals.
    xTicks.filterIndexed { index, _ -> index % ceil(24f / (area.width() * graph.xStep / (graph.xMax - graph.xMin))).toInt().coerceAtLeast(1) == 0 }
        .forEach { value ->
            canvas.drawLine(x(value), zeroY - 2f, x(value), zeroY + 2f, paint)
            if (graph.labels && kotlin.math.abs(value) > graph.xStep * .001f) {
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText(label(value), x(value), (zeroY + 13f).coerceAtMost(graph.height - 5f), paint)
            }
        }
    yTicks.filterIndexed { index, _ -> index % ceil(18f / (area.height() * graph.yStep / (graph.yMax - graph.yMin))).toInt().coerceAtLeast(1) == 0 }
        .forEach { value ->
            canvas.drawLine(zeroX - 2f, y(value), zeroX + 2f, y(value), paint)
            if (graph.labels && kotlin.math.abs(value) > graph.yStep * .001f) {
                paint.textAlign = Paint.Align.RIGHT
                canvas.drawText(label(value), (zeroX - 6f).coerceAtLeast(26f), y(value) + 3f, paint)
            }
        }
    paint.textAlign = Paint.Align.LEFT; paint.textSize = 11f
    canvas.drawText("x", area.right + 10f, zeroY + 4f, paint)
    canvas.drawText("y", zeroX + 5f, area.top - 7f, paint)
    if (graph.labels) { paint.textSize = 9f; canvas.drawText("0", zeroX + 4f, (zeroY + 12f).coerceAtMost(graph.height - 5f), paint) }
}

private fun drawTable(canvas: Canvas, table: TableSpec) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = Color.WHITE; canvas.drawRect(0f, 0f, table.width, table.height, paint)
    var top = 0f
    table.rows.forEachIndexed { row, height ->
        var left = 0f
        table.columns.forEachIndexed { column, width ->
            if (row == 0 && table.header) {
                paint.color = 0xFFE4ECE3.toInt(); paint.style = Paint.Style.FILL
                canvas.drawRect(left, top, left + width, top + height, paint)
            }
            val text = table.cells[row][column]
            if (text.isNotEmpty()) {
                val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFF253B34.toInt(); textSize = 14f
                    typeface = Typeface.create(Typeface.DEFAULT, if (row == 0 && table.header) Typeface.BOLD else Typeface.NORMAL)
                }
                val layout = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, (width - 12f).toInt().coerceAtLeast(1))
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build()
                canvas.save(); canvas.clipRect(left + 4f, top + 4f, left + width - 4f, top + height - 4f)
                canvas.translate(left + 6f, top + 6f); layout.draw(canvas); canvas.restore()
            }
            left += width
        }
        top += height
    }
    paint.color = 0xFF607166.toInt(); paint.strokeWidth = 1f; paint.style = Paint.Style.STROKE
    canvas.drawRect(.5f, .5f, table.width - .5f, table.height - .5f, paint)
    var x = 0f
    table.columns.dropLast(1).forEach { x += it; canvas.drawLine(x, 0f, x, table.height, paint) }
    var y = 0f
    table.rows.dropLast(1).forEach { y += it; canvas.drawLine(0f, y, table.width, y, paint) }
}
