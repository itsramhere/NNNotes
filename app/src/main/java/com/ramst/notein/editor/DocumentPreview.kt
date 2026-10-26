package com.ramst.notein.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import org.json.JSONObject
import com.ramst.notein.storage.EditableObjectEntity

/** Built off the UI thread. Immutable preview geometry is reused during navigation. */
internal data class PreviewStroke(val points: List<Offset>, val color: Int, val width: Float, val bounds: InkBounds,
    val stroke: androidx.ink.strokes.Stroke? = null, val content: ContentObject? = null)

internal fun InkStrokeRecord.toPreview(): PreviewStroke? = decode()?.let { decoded ->
    val inputs = decoded.stroke.inputs
    val json = JSONObject(payload)
    PreviewStroke(List(inputs.size) { Offset(inputs[it].x, inputs[it].y) },
        json.getInt("color"), decoded.stroke.brush.size, decoded.bounds, decoded.stroke)
}

internal fun EditableObjectEntity.toContentPreview(): PreviewStroke? {
    if (type == INK_STROKE_TYPE) return toInkStrokeRecord()?.toPreview()
    val content = toContentObject() ?: return null
    val bounds = content.bounds
    return PreviewStroke(listOf(Offset(bounds.left, bounds.top), Offset(bounds.right, bounds.top),
        Offset(bounds.right, bounds.bottom), Offset(bounds.left, bounds.bottom), Offset(bounds.left, bounds.top)),
        if (type == TEXT_OBJECT_TYPE || type == HEADING_OBJECT_TYPE) content.color else 0xFF697A8C.toInt(), 1f, bounds,
        content = content.takeIf { type in STRUCTURED_CONTENT_TYPES })
}

internal data class OverviewGeometry(val world: InkBounds, val viewport: InkBounds, val factor: Float) {
    fun toWorld(point: Offset) = Offset(world.left + point.x / factor, world.top + point.y / factor)
    fun toPreview(point: Offset) = Offset((point.x - world.left) * factor, (point.y - world.top) * factor)
}

internal fun overviewGeometry(previews: List<PreviewStroke>, x: Float, y: Float, scale: Float,
    surface: Size, preview: Size): OverviewGeometry {
    val visible = InkBounds(-x / scale, -y / scale, (surface.width - x) / scale, (surface.height - y) / scale)
    val left = minOf(previews.minOfOrNull { it.bounds.left - it.width } ?: visible.left, visible.left) - 24f
    val top = minOf(previews.minOfOrNull { it.bounds.top - it.width } ?: visible.top, visible.top) - 24f
    val right = maxOf(previews.maxOfOrNull { it.bounds.right + it.width } ?: visible.right, visible.right) + 24f
    val bottom = maxOf(previews.maxOfOrNull { it.bounds.bottom + it.width } ?: visible.bottom, visible.bottom) + 24f
    return OverviewGeometry(InkBounds(left, top, right, bottom), visible,
        minOf(preview.width / (right - left), preview.height / (bottom - top)))
}

internal fun DrawScope.drawPreview(previews: List<PreviewStroke>, factor: Float, origin: Offset = Offset.Zero) {
    val renderer = androidx.ink.rendering.android.canvas.CanvasStrokeRenderer.create()
    previews.forEach { ink ->
        ink.content?.let { content ->
            val native = drawContext.canvas.nativeCanvas
            native.save()
            native.translate((content.transform.x.toFloat() - origin.x) * factor, (content.transform.y.toFloat() - origin.y) * factor)
            native.scale(content.transform.scale.toFloat() * factor, content.transform.scale.toFloat() * factor)
            if (content.entity.type == HEADING_OBJECT_TYPE) {
                native.translate(8f, 8f); textLayout(content).draw(native)
            } else drawStructuredContent(native, content)
            native.restore()
            return@forEach
        }
        if (ink.stroke != null) {
            val native = drawContext.canvas.nativeCanvas
            native.save()
            native.translate(-origin.x * factor, -origin.y * factor)
            native.scale(factor, factor)
            val matrix = android.graphics.Matrix().apply { setScale(factor, factor); postTranslate(-origin.x * factor, -origin.y * factor) }
            renderer.draw(native, ink.stroke, matrix)
            native.restore()
            return@forEach
        }
        val path = Path()
        ink.points.forEachIndexed { index, point ->
            val x = (point.x - origin.x) * factor
            val y = (point.y - origin.y) * factor
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        if (ink.points.size == 1) drawCircle(Color(ink.color), maxOf(0.5f, ink.width * factor / 2f),
            (ink.points.first() - origin) * factor)
        else drawPath(path, Color(ink.color), style = Stroke(maxOf(0.5f, ink.width * factor)))
    }
}
