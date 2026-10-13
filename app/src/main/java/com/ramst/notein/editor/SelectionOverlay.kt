package com.ramst.notein.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.view.MotionEvent
import android.view.View
import com.ramst.notein.storage.EditableObjectEntity
import kotlin.math.hypot

/** Selection gestures are recorded in document space and committed only on pointer-up. */
internal class SelectionOverlay(context: Context) : View(context) {
    var selectionEnabled = false
    var objects: List<EditableObjectEntity> = emptyList()
    var selectedIds: Set<String> = emptySet()
    var viewportX = 0f
    var viewportY = 0f
    var viewportScale = 1f
    var onLasso: ((List<PointF>) -> Unit)? = null
    var onTransform: ((Float, Float, Float) -> Unit)? = null
    var onSelect: ((String?) -> Unit)? = null
    var onEditText: ((String) -> Unit)? = null
    var onPending: ((Int) -> Unit)? = null
    private val polygon = mutableListOf<PointF>()
    private var start: PointF? = null
    private var last: PointF? = null
    private var gesture: Gesture? = null
    private var snapshotBounds: InkBounds? = null
    private var pointerId = MotionEvent.INVALID_POINTER_ID
    private var pointerTool = MotionEvent.TOOL_TYPE_UNKNOWN
    private var handleX = 1f
    private var handleY = 1f
    private var lastTapTime = 0L
    private var lastTapId: String? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(36, 76, 154) }
    private enum class Gesture { LASSO, MOVE, RESIZE }

    fun cancelGesture() {
        val hadGesture = gesture != null
        gesture = null; start = null; last = null; polygon.clear(); pointerId = MotionEvent.INVALID_POINTER_ID
        if (hadGesture) onPending?.invoke(0)
        invalidate()
    }

    fun handle(event: MotionEvent): Boolean {
        if (!selectionEnabled) return false
        val actionIndex = event.actionIndex
        val actionPointer = event.getPointerId(actionIndex)
        if (event.actionMasked == MotionEvent.ACTION_CANCEL ||
            (android.os.Build.VERSION.SDK_INT >= 33 && event.flags and MotionEvent.FLAG_CANCELED != 0 && actionPointer == pointerId)) {
            cancelGesture(); return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val tool = event.getToolType(actionIndex)
                val pen = tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER
                if (gesture != null) {
                    if (!pen || pointerTool != MotionEvent.TOOL_TYPE_FINGER) return true
                    cancelGesture()
                } else if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && !pen) return true
                pointerId = actionPointer
                pointerTool = tool
                val point = world(event.getX(actionIndex), event.getY(actionIndex))
                start = point; last = point
                snapshotBounds = selectionBounds(objects, selectedIds)
                val bounds = snapshotBounds
                val radius = minOf(24f * resources.displayMetrics.density / viewportScale,
                    bounds?.let { minOf(it.right - it.left, it.bottom - it.top) / 3f } ?: Float.MAX_VALUE)
                val corner = bounds?.let { box -> listOf(
                    Triple(box.left, box.top, Pair(-1f, -1f)), Triple(box.right, box.top, Pair(1f, -1f)),
                    Triple(box.left, box.bottom, Pair(-1f, 1f)), Triple(box.right, box.bottom, Pair(1f, 1f)),
                ).minByOrNull { hypot(point.x - it.first, point.y - it.second) }
                    ?.takeIf { hypot(point.x - it.first, point.y - it.second) <= radius } }
                gesture = when {
                    corner != null -> { handleX = corner.third.first; handleY = corner.third.second; Gesture.RESIZE }
                    bounds?.contains(point.x, point.y, 0f) == true -> Gesture.MOVE
                    else -> Gesture.LASSO
                }
                polygon.clear(); polygon += point
                onPending?.invoke(1)
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointerId)
                if (index < 0) { cancelGesture(); return true }
                last = world(event.getX(index), event.getY(index))
                if (gesture == Gesture.LASSO) {
                    repeat(event.historySize) { h -> polygon += world(event.getHistoricalX(index, h), event.getHistoricalY(index, h)) }
                    polygon += requireNotNull(last)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (actionPointer != pointerId) return true
                val first = start ?: return true
                val end = world(event.getX(actionIndex), event.getY(actionIndex))
                val currentGesture = gesture
                val bounds = snapshotBounds
                val lasso = polygon.toList() + end
                cancelGesture()
                // A closed lasso finishes near its start but still travelled around content.
                val distance = maxOf(hypot(end.x - first.x, end.y - first.y),
                    if (currentGesture == Gesture.LASSO) lasso.maxOfOrNull { hypot(it.x - first.x, it.y - first.y) } ?: 0f else 0f) * viewportScale
                when {
                    distance < 8f * resources.displayMetrics.density -> {
                        val pointPolygon = listOf(PointF(end.x - 0.01f, end.y - 0.01f), PointF(end.x + 0.01f, end.y - 0.01f),
                            PointF(end.x + 0.01f, end.y + 0.01f), PointF(end.x - 0.01f, end.y + 0.01f))
                        val hit = objects.asReversed().firstOrNull { contentIntersectsLasso(it, pointPolygon) }
                        onSelect?.invoke(hit?.id)
                        if (hit?.type == TEXT_OBJECT_TYPE && hit.id == lastTapId && event.eventTime - lastTapTime < 400L) onEditText?.invoke(hit.id)
                        lastTapId = hit?.id; lastTapTime = event.eventTime
                    }
                    currentGesture == Gesture.MOVE -> onTransform?.invoke(end.x - first.x, end.y - first.y, 1f)
                    currentGesture == Gesture.RESIZE && bounds != null -> {
                        val factor = resizeFactor(first, end, bounds)
                        val width = bounds.right - bounds.left; val height = bounds.bottom - bounds.top
                        onTransform?.invoke(if (handleX < 0) width * (1f - factor) else 0f,
                            if (handleY < 0) height * (1f - factor) else 0f, factor)
                    }
                    currentGesture == Gesture.LASSO -> onLasso?.invoke(lasso)
                }
                return true
            }
        }
        invalidate(); return true
    }

    private fun resizeFactor(first: PointF, end: PointF, bounds: InkBounds): Float {
        val width = (bounds.right - bounds.left).coerceAtLeast(1f)
        val height = (bounds.bottom - bounds.top).coerceAtLeast(1f)
        return (1f + ((end.x - first.x) * handleX * width + (end.y - first.y) * handleY * height) /
            (width * width + height * height)).coerceIn(0.05f, 20f)
    }
    private fun world(x: Float, y: Float) = PointF((x - viewportX) / viewportScale, (y - viewportY) / viewportScale)
    override fun onDraw(canvas: Canvas) {
        if (!selectionEnabled) return
        canvas.save(); canvas.translate(viewportX, viewportY); canvas.scale(viewportScale, viewportScale)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f * resources.displayMetrics.density / viewportScale
        if (polygon.size > 1 && gesture == Gesture.LASSO) {
            val path = Path().apply { moveTo(polygon.first().x, polygon.first().y); polygon.drop(1).forEach { lineTo(it.x, it.y) } }
            canvas.drawPath(path, paint)
        }
        var bounds = selectionBounds(objects, selectedIds)
        val first = start; val end = last
        if (bounds != null && first != null && end != null) {
            if (gesture == Gesture.MOVE) bounds = ObjectTransform((end.x - first.x).toDouble(), (end.y - first.y).toDouble()).bounds(bounds)
            if (gesture == Gesture.RESIZE) {
                val factor = resizeFactor(first, end, bounds)
                val dx = if (handleX < 0) (bounds.right - bounds.left) * (1f - factor) else 0f
                val dy = if (handleY < 0) (bounds.bottom - bounds.top) * (1f - factor) else 0f
                bounds = InkBounds(bounds.left + dx, bounds.top + dy,
                    bounds.left + dx + (bounds.right - bounds.left) * factor, bounds.top + dy + (bounds.bottom - bounds.top) * factor)
            }
        }
        bounds?.let { box ->
            canvas.drawRect(box.left, box.top, box.right, box.bottom, paint)
            val radius = 6f * resources.displayMetrics.density / viewportScale
            paint.style = Paint.Style.FILL
            listOf(PointF(box.left, box.top), PointF(box.right, box.top), PointF(box.left, box.bottom), PointF(box.right, box.bottom)).forEach { canvas.drawCircle(it.x, it.y, radius, paint) }
        }
        canvas.restore()
    }
}

