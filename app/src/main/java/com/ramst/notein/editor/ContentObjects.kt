package com.ramst.notein.editor

import android.graphics.PointF
import com.ramst.notein.storage.EditableObjectEntity
import org.json.JSONObject

internal const val TEXT_OBJECT_TYPE = "text_box_v1"
internal const val IMAGE_OBJECT_TYPE = "image_v1"
internal const val PDF_OBJECT_TYPE = "pdf_page_v1"
internal val EDITABLE_CONTENT_TYPES = setOf(INK_STROKE_TYPE, TEXT_OBJECT_TYPE, IMAGE_OBJECT_TYPE, PDF_OBJECT_TYPE) + STRUCTURED_CONTENT_TYPES

/** Uniform positive scale and translation, applied to immutable source coordinates. */
internal data class ObjectTransform(val x: Double = 0.0, val y: Double = 0.0, val scale: Double = 1.0) {
    fun bounds(source: InkBounds) = InkBounds(
        (source.left * scale + x).toFloat(), (source.top * scale + y).toFloat(),
        (source.right * scale + x).toFloat(), (source.bottom * scale + y).toFloat(),
    )
    fun write(json: JSONObject): JSONObject = json.put("transformX", x).put("transformY", y).put("transformScale", scale)
    companion object {
        fun read(json: JSONObject): ObjectTransform {
            val result = ObjectTransform(json.optDouble("transformX", 0.0), json.optDouble("transformY", 0.0), json.optDouble("transformScale", 1.0))
            require(result.x.isFinite() && result.y.isFinite() && result.scale.isFinite() && result.scale > 0.0)
            return result
        }
    }
}

internal data class ContentObject(
    val entity: EditableObjectEntity,
    val sourceBounds: InkBounds,
    val transform: ObjectTransform,
    val text: String = "",
    val fontSize: Float = 24f,
    val color: Int = android.graphics.Color.BLACK,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val assetId: String? = null,
    val pdfSourceId: String? = null,
    val pdfPageIndex: Int? = null,
    val pdfPath: String? = null,
    val headingLevel: Int? = null,
    val shape: ShapeSpec? = null,
    val graph: GraphSpec? = null,
    val table: TableSpec? = null,
) {
    val bounds: InkBounds get() = transform.bounds(sourceBounds)
}

internal fun EditableObjectEntity.toContentObject(): ContentObject? = runCatching {
    if (type !in EDITABLE_CONTENT_TYPES) return null
    val json = JSONObject(payload)
    val bounds = if (type == INK_STROKE_TYPE) {
        val padding = json.getDouble("size").toFloat()
        InkBounds(json.getDouble("left").toFloat() - padding, json.getDouble("top").toFloat() - padding,
            json.getDouble("right").toFloat() + padding, json.getDouble("bottom").toFloat() + padding)
    } else InkBounds(0f, 0f, json.getDouble("width").toFloat(), json.getDouble("height").toFloat())
    require(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom).all { it.isFinite() })
    require(bounds.right > bounds.left && bounds.bottom > bounds.top)
    val transform = ObjectTransform.read(json)
    val world = transform.bounds(bounds)
    require(listOf(world.left,world.top,world.right,world.bottom).all { it.isFinite() } && world.right > world.left && world.bottom > world.top)
    if (type == IMAGE_OBJECT_TYPE) java.util.UUID.fromString(json.getString("assetId"))
    if (type == PDF_OBJECT_TYPE) {
        java.util.UUID.fromString(json.getString("pdfSourceId"))
        require(json.getInt("pdfPageIndex") >= 0 && json.getString("pdfPath").isNotBlank())
    }
    require(json.optDouble("fontSize",24.0).isFinite())
    val headingLevel = if (type == HEADING_OBJECT_TYPE) json.getInt("headingLevel").also {
        require(it in 1..6 && json.getString("text").isNotBlank())
    } else null
    ContentObject(this, bounds, transform, json.optString("text", ""),
        json.optDouble("fontSize", 24.0).toFloat().coerceIn(8f, 144f), json.optInt("color", android.graphics.Color.BLACK),
        json.optBoolean("bold"), json.optBoolean("italic"), json.optString("assetId").takeIf { it.isNotBlank() },
        json.optString("pdfSourceId").takeIf { it.isNotBlank() },
        if (type == PDF_OBJECT_TYPE) json.getInt("pdfPageIndex") else null,
        json.optString("pdfPath").takeIf { it.isNotBlank() }, headingLevel,
        if (type == SHAPE_OBJECT_TYPE) ShapeSpec.read(json) else null,
        if (type == GRAPH_OBJECT_TYPE) GraphSpec.read(json) else null,
        if (type == TABLE_OBJECT_TYPE) TableSpec.read(json) else null)
}.getOrNull()

internal fun transformContent(entity: EditableObjectEntity, pivotX: Float, pivotY: Float, dx: Float, dy: Float, factor: Float): EditableObjectEntity {
    require(dx.isFinite() && dy.isFinite() && factor.isFinite() && factor > 0f)
    val json = JSONObject(entity.payload)
    val before = ObjectTransform.read(json)
    val after = ObjectTransform(
        pivotX + (before.x - pivotX) * factor + dx,
        pivotY + (before.y - pivotY) * factor + dy,
        before.scale * factor,
    )
    require(after.scale in 0.000001..1000000.0 && after.x.isFinite() && after.y.isFinite())
    val changed = entity.copy(payload = after.write(json).toString(), updatedAt = System.currentTimeMillis())
    require(changed.toContentObject() != null) { "This transform exceeds document-coordinate precision." }
    return changed
}

internal fun selectionBounds(objects: List<EditableObjectEntity>, ids: Set<String>): InkBounds? {
    val bounds = objects.filter { it.id in ids }.mapNotNull { it.toContentObject()?.bounds }
    if (bounds.isEmpty()) return null
    return InkBounds(bounds.minOf { it.left }, bounds.minOf { it.top }, bounds.maxOf { it.right }, bounds.maxOf { it.bottom })
}

/** Closed polygon/rectangle intersection, including edges and fully enclosed objects. */
internal fun lassoIntersects(polygon: List<PointF>, bounds: InkBounds): Boolean {
    if (polygon.size < 3) return false
    val corners = listOf(PointF(bounds.left, bounds.top), PointF(bounds.right, bounds.top), PointF(bounds.right, bounds.bottom), PointF(bounds.left, bounds.bottom))
    if (polygon.any { bounds.contains(it.x, it.y, 0f) } || corners.any { pointInPolygon(it, polygon) }) return true
    return polygon.indices.any { p -> corners.indices.any { c ->
        segmentsIntersect(polygon[p], polygon[(p + 1) % polygon.size], corners[c], corners[(c + 1) % corners.size])
    } }
}

internal fun contentIntersectsLasso(entity: EditableObjectEntity, polygon: List<PointF>): Boolean {
    val content = entity.toContentObject() ?: return false
    if (!lassoIntersects(polygon, content.bounds)) return false
    if (entity.type != INK_STROKE_TYPE) return true
    val decoded = entity.toInkStrokeRecord()?.decode() ?: return false
    val inputs = decoded.stroke.inputs
    val radius = decoded.stroke.brush.size / 2f
    fun distance(point: PointF, first: PointF, second: PointF): Float {
        val dx = second.x - first.x; val dy = second.y - first.y
        val length = dx * dx + dy * dy
        val t = if (length > 0f) (((point.x - first.x) * dx + (point.y - first.y) * dy) / length).coerceIn(0f, 1f) else 0f
        return kotlin.math.hypot(point.x - first.x - t * dx, point.y - first.y - t * dy)
    }
    for (index in 0 until inputs.size) {
        val sample = inputs[index]
        val point = PointF(sample.x, sample.y)
        if (pointInPolygon(point, polygon)) return true
        val previous = if (index > 0) inputs[index - 1].let { PointF(it.x, it.y) } else point
        if (polygon.indices.any { p ->
            val a = polygon[p]; val b = polygon[(p + 1) % polygon.size]
            segmentsIntersect(previous, point, a, b) || distance(point, a, b) <= radius ||
                distance(a, previous, point) <= radius || distance(b, previous, point) <= radius
        }) return true
    }
    return false
}

private fun pointInPolygon(point: PointF, polygon: List<PointF>): Boolean {
    var inside = false
    var previous = polygon.last()
    for (current in polygon) {
        if ((current.y > point.y) != (previous.y > point.y) &&
            point.x < (previous.x - current.x) * (point.y - current.y) / (previous.y - current.y) + current.x) inside = !inside
        previous = current
    }
    return inside
}

internal fun segmentsIntersect(a: PointF, b: PointF, c: PointF, d: PointF): Boolean {
    fun cross(p: PointF, q: PointF, r: PointF): Double = (q.x - p.x).toDouble() * (r.y - p.y) - (q.y - p.y).toDouble() * (r.x - p.x)
    fun onSegment(p: PointF, q: PointF, r: PointF) = q.x in minOf(p.x, r.x)..maxOf(p.x, r.x) && q.y in minOf(p.y, r.y)..maxOf(p.y, r.y)
    val first = cross(a, b, c); val second = cross(a, b, d); val third = cross(c, d, a); val fourth = cross(c, d, b)
    if (((first > 0 && second < 0) || (first < 0 && second > 0)) && ((third > 0 && fourth < 0) || (third < 0 && fourth > 0))) return true
    return (first == 0.0 && onSegment(a, c, b)) || (second == 0.0 && onSegment(a, d, b)) ||
        (third == 0.0 && onSegment(c, a, d)) || (fourth == 0.0 && onSegment(c, b, d))
}
