package com.ramst.notein.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.os.Build
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.geometry.ImmutableAffineTransform
import androidx.ink.geometry.ImmutableBox
import androidx.ink.geometry.ImmutableVec
import androidx.ink.geometry.Intersection
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.rendering.android.view.ViewStrokeRenderer
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import com.ramst.notein.storage.EditableObjectEntity
import android.graphics.PointF
import com.ramst.notein.pdf.PdfPageRenderer
import com.ramst.notein.storage.PageLayout
import kotlin.math.abs

/** AndroidX Ink authoring with immutable, document-coordinate completed strokes. */
internal class InkCanvasView(context: Context) : FrameLayout(context) {
    private val completedView = CompletedInkView(context)
    private var pdfView: PdfPageRenderer? = null
    private val inProgressView = InProgressStrokesView(context, null, 0)
    private val selectionOverlay = SelectionOverlay(context)
    private var contentObjects: List<ContentObject> = emptyList()
    private var contentEntities: List<EditableObjectEntity>? = null
    private var storedRecords: List<InkStrokeRecord>? = null
    private var selectionNavigation = false
    private var selectionPending = 0
    private val completed = LinkedHashMap<String, DecodedInkStroke>()
    private val desiredIds = mutableSetOf<String>()
    private val activeStrokes = mutableMapOf<InProgressStrokeId, ActiveStroke>()
    private val eraserPreview = LinkedHashMap<String, InkStrokeRecord>()
    private var currentPageId: String? = null
    private var tool = InkTool.Pen
    private var color = -15066082
    private var size = 6f
    private var fingerWriting = false
    private var viewportX = 0f
    private var viewportY = 0f
    private var viewportScale = 1f
    private var infiniteCanvas = false
    private var documentConfigured = false
    private var paperBackground = "blank"
    private var pageWidth = 595f
    private var pageHeight = 842f
    private var paperSpacingMm = 7f
    private var paperPatternWeight = "normal"
    private var paperBackgroundColor = android.graphics.Color.WHITE
    private var pdfSourcePath: String? = null
    private var pdfPage: com.ramst.notein.storage.PageEntity? = null
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var activeStrokeId: InProgressStrokeId? = null
    private var activeIsFinger = false
    private var eraserPointerId = MotionEvent.INVALID_POINTER_ID
    private var fingerPointerId = MotionEvent.INVALID_POINTER_ID
    private var lastX = 0f
    private var lastY = 0f
    private var panStartX = 0f
    private var panStartY = 0f
    private var panStartScale = 1f
    private var suppressFingers = false
    private var lastStrokeTime = 0L
    private var acceptingCallbacks = true

    var onStrokeCommitted: ((InkStrokeRecord) -> Unit)? = null
    var onStrokeErased: ((InkStrokeRecord) -> Unit)? = null
    var onViewportChanged: ((Float, Float, Float) -> Unit)? = null
    var onPendingInkChanged: ((Int) -> Unit)? = null
    var currentPageStrokes: ((String) -> List<InkStrokeRecord>)? = null
    var inputEnabled = true
    private var viewportLocked = false
    private var pageLayout = PageLayout.HORIZONTAL
    private var pageNeighbors: List<PageNeighbor> = emptyList()
    private val neighborViews = linkedMapOf<String, InkCanvasView>()
    private var onPageNavigate: ((Int) -> Unit)? = null
    private var pageSwipeOffset = 0f
    private var panTravelX = 0f
    private var panTravelY = 0f
    private var panRawY = 0f
    private var panScaled = false

    fun setPageNavigation(layout: PageLayout, neighbors: List<PageNeighbor>, onNavigate: (Int) -> Unit) {
        onPageNavigate = onNavigate
        if (pageLayout == layout && pageNeighbors == neighbors) return
        if (pageLayout != layout) cancelPan()
        pageLayout = layout
        pageNeighbors = neighbors
        val ids = neighbors.map { it.page.id }.toSet()
        neighborViews.keys.filter { it !in ids }.forEach { id -> neighborViews.remove(id)?.let { removeView(it) } }
        neighbors.forEach { neighbor ->
            val view = neighborViews.getOrPut(neighbor.page.id) {
                InkCanvasView(context).also { it.inputEnabled = false; addView(it, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }
            }
            val page = neighbor.page
            view.setPageId(page.id)
            view.setDocument(false, page.paperBackground, page.pageWidth, page.pageHeight,
                page.paperSpacingMm, page.paperPatternWeight, page.paperBackgroundColor)
            view.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            view.setContentObjects(neighbor.objects, emptySet(), false, {}, { _, _, _ -> }, {}, {}, {})
            view.setStoredStrokes(neighbor.objects.mapNotNull { it.toInkStrokeRecord() })
            view.setPdfBackground(neighbor.pdfPath, page) {}
        }
        renderCompleted()
    }

    private fun layoutPageStrip() {
        val extent = if (pageLayout == PageLayout.HORIZONTAL) width else height
        val shift = if (infiniteCanvas) 0f else pageSwipeOffset.coerceIn(-extent * .6f, extent * .6f)
        val shiftX = if (pageLayout == PageLayout.HORIZONTAL) shift else 0f
        val shiftY = if (pageLayout == PageLayout.VERTICAL) shift else 0f
        listOfNotNull(completedView, pdfView, inProgressView, selectionOverlay).forEach {
            it.translationX = shiftX; it.translationY = shiftY
            it.clipBounds = if (it !== selectionOverlay && documentConfigured && !infiniteCanvas)
                android.graphics.Rect(kotlin.math.floor(viewportX).toInt(), kotlin.math.floor(viewportY).toInt(),
                    ceil(viewportX + pageWidth * viewportScale).toInt(), ceil(viewportY + pageHeight * viewportScale).toInt()) else null
        }
        val gap = 24f * resources.displayMetrics.density
        pageNeighbors.forEach { neighbor ->
            val view = neighborViews[neighbor.page.id] ?: return@forEach
            view.setEditorState(InkTool.Pen, android.graphics.Color.BLACK, 6f, false, 0f, 0f, viewportScale)
            val (x, y, _) = view.viewport()
            if (pageLayout == PageLayout.HORIZONTAL) {
                view.translationX = shiftX + if (neighbor.direction > 0) viewportX + pageWidth * viewportScale + gap - x
                    else viewportX - gap - x - neighbor.page.pageWidth * viewportScale
                view.translationY = 0f
            } else {
                view.translationX = 0f
                view.translationY = shiftY + if (neighbor.direction > 0) viewportY + pageHeight * viewportScale + gap - y
                    else viewportY - gap - y - neighbor.page.pageHeight * viewportScale
            }
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        renderCompleted()
    }

    fun setViewportLocked(value: Boolean) {
        if (viewportLocked == value) return
        fingerPointerId = MotionEvent.INVALID_POINTER_ID
        selectionNavigation = false
        selectionOverlay.cancelGesture()
        pageSwipeOffset = 0f
        viewportLocked = value
        scaleDetector = createScaleDetector()
        publishPendingInk()
    }

    fun commitViewportBeforeLock() { onViewportChanged?.invoke(viewportX, viewportY, viewportScale) }


    init {
        // Ink is paper; keep black strokes legible even when the surrounding UI is dark.
        setBackgroundColor(android.graphics.Color.WHITE)
        addView(completedView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(inProgressView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(selectionOverlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        inProgressView.addFinishedStrokesListener(object : InProgressStrokesFinishedListener {
            override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
                // Late GPU handoffs belong to the old view after activity recreation.
                if (!acceptingCallbacks || !isAttachedToWindow) return
                strokes.forEach { (strokeId, stroke) ->
                    val active = activeStrokes.remove(strokeId) ?: return@forEach
                    val record = active.finishedRecord ?: return@forEach
                    if (record.id in desiredIds && record.id !in eraserPreview) {
                        active.finishedBounds?.let { completed[record.id] = DecodedInkStroke(record, stroke, it) }
                    }
                    InkTrace.log("stroke finished id=${record.id} inputs=${stroke.inputs.size}")
                }
                inProgressView.removeFinishedStrokes(strokes.keys)
                publishPendingInk()
                renderCompleted()
            }
        })
        contentDescription = "Handwriting canvas"
    }

    private var scaleDetector = createScaleDetector()
    private fun createScaleDetector() = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (viewportLocked) return false
            if (activePointerId != MotionEvent.INVALID_POINTER_ID || eraserPointerId != MotionEvent.INVALID_POINTER_ID) return false
            val oldScale = viewportScale
            val newScale = (oldScale * detector.scaleFactor).coerceIn(0.000001f, 6f)
            val worldX = (lastX - viewportX) / oldScale
            val worldY = (lastY - viewportY) / oldScale
            viewportScale = newScale
            viewportX = detector.focusX - worldX * newScale
            viewportY = detector.focusY - worldY * newScale
            lastX = detector.focusX
            lastY = detector.focusY
            renderCompleted()
            return true
        }
    })

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean = true
    override fun onTouchEvent(event: MotionEvent): Boolean = handleTouch(this, event)
    override fun performClick(): Boolean { super.performClick(); return true }

    fun setPageId(pageId: String) {
        if (currentPageId != pageId) {
            selectionOverlay.cancelGesture(); pageSwipeOffset = 0f
            storedRecords = null
        }
        currentPageId = pageId
    }

    fun setContentObjects(objects: List<EditableObjectEntity>, selectedIds: Set<String>, lassoEnabled: Boolean,
        onLasso: (List<PointF>) -> Unit, onTransform: (Float, Float, Float) -> Unit,
        onSelect: (String?) -> Unit, onEditText: (String) -> Unit, onError: (String) -> Unit) {
        val contentChanged = contentEntities != objects
        if (contentChanged) {
            val cached = contentObjects.associateBy { it.entity.id }
            contentObjects = objects.mapNotNull { entity ->
                cached[entity.id]?.takeIf { it.entity == entity } ?: entity.toContentObject()
            }
            contentEntities = objects
            lastStrokeTime = maxOf(lastStrokeTime, objects.maxOfOrNull { it.createdAt } ?: 0L)
        }
        selectionOverlay.objects = objects
        selectionOverlay.selectedIds = selectedIds
        if (selectionOverlay.selectionEnabled != lassoEnabled) selectionOverlay.cancelGesture()
        selectionOverlay.selectionEnabled = lassoEnabled
        selectionOverlay.onLasso = onLasso
        selectionOverlay.onTransform = onTransform
        selectionOverlay.onSelect = onSelect
        selectionOverlay.onEditText = onEditText
        selectionOverlay.onPending = { selectionPending = it; publishPendingInk() }
        completedView.onContentError = onError
        if (contentChanged) {
            completedView.setContent(contentObjects.filter { it.entity.type != INK_STROKE_TYPE })
        }
        selectionOverlay.invalidate()
    }

    fun setEditorState(activeTool: InkTool, brushColor: Int, brushSize: Float,
        allowFingerWriting: Boolean, newViewportX: Float, newViewportY: Float, newViewportScale: Float) {
        tool = activeTool
        color = brushColor
        size = brushSize
        fingerWriting = allowFingerWriting
        // A recomposition during a gesture must not reset the local pan or stroke transform.
        if (activePointerId == MotionEvent.INVALID_POINTER_ID &&
            eraserPointerId == MotionEvent.INVALID_POINTER_ID &&
            fingerPointerId == MotionEvent.INVALID_POINTER_ID) {
            val scale = newViewportScale.coerceIn(0.000001f, 6f)
            if (viewportX == newViewportX && viewportY == newViewportY && viewportScale == scale) return
            viewportX = newViewportX
            viewportY = newViewportY
            viewportScale = scale
            renderCompleted()
        }
    }

    fun setDocument(isInfinite: Boolean, background: String, width: Float, height: Float, spacingMm: Float = 7f,
        patternWeight: String = "normal", backgroundColor: Int = android.graphics.Color.WHITE) {
        if (documentConfigured && infiniteCanvas == isInfinite && paperBackground == background &&
            pageWidth == width && pageHeight == height && paperSpacingMm == spacingMm &&
            paperPatternWeight == patternWeight && paperBackgroundColor == backgroundColor) return
        infiniteCanvas = isInfinite
        documentConfigured = true
        setBackgroundColor(if (isInfinite) android.graphics.Color.WHITE else 0xFFE5E7EB.toInt())
        paperBackground = background
        pageWidth = width
        pageHeight = height
        paperSpacingMm = spacingMm
        paperPatternWeight = patternWeight
        paperBackgroundColor = backgroundColor
        completedView.setPaperTemplate(spacingMm, patternWeight, backgroundColor)
        renderCompleted()
    }

    fun setPdfBackground(sourcePath: String?, page: com.ramst.notein.storage.PageEntity?, onError: (String) -> Unit) {
        val hasPdf = page?.pdfPageIndex != null
        if (!hasPdf && pdfView == null) return
        pdfSourcePath = sourcePath
        pdfPage = page
        if (!hasPdf) {
            pdfView?.let { removeView(it) }; pdfView = null
            completedView.setPdfBackground(false)
            return
        }
        val renderer = pdfView ?: PdfPageRenderer(context).also {
            pdfView = it
            addView(it, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        renderer.onError = onError
        completedView.setPdfBackground(true)
        if (sourcePath == null) onError("The local PDF source is unavailable. Your annotations have been retained.")
        renderer.setPdf(sourcePath, page, viewportX, viewportY, viewportScale)
    }

    fun fitPage() {
        if (viewportLocked) return
        if (infiniteCanvas || width == 0 || height == 0 || activeStrokes.isNotEmpty() || fingerPointerId != MotionEvent.INVALID_POINTER_ID || eraserPointerId != MotionEvent.INVALID_POINTER_ID) return
        val scale = min(width / pageWidth, height / pageHeight).coerceIn(0.000001f, 6f)
        viewportScale = scale
        viewportX = (width - pageWidth * scale) / 2f
        viewportY = (height - pageHeight * scale) / 2f
        renderCompleted()
        onViewportChanged?.invoke(viewportX, viewportY, viewportScale)
    }

    fun fitContent() {
        if (viewportLocked) return
        if (width == 0 || height == 0 || activeStrokes.isNotEmpty() || fingerPointerId != MotionEvent.INVALID_POINTER_ID || eraserPointerId != MotionEvent.INVALID_POINTER_ID) return
        if (contentObjects.isEmpty() && completed.isEmpty()) { viewportScale = 1f; viewportX = width / 2f; viewportY = height / 2f }
        else {
            val bounds = contentObjects.map { it.bounds } + completed.values.map { it.bounds }
            val left = bounds.minOf { it.left }
            val top = bounds.minOf { it.top }
            val right = bounds.maxOf { it.right }
            val bottom = bounds.maxOf { it.bottom }
            val contentWidth = (right - left).coerceAtLeast(1f)
            val contentHeight = (bottom - top).coerceAtLeast(1f)
            viewportScale = min(width / (contentWidth + 64f), height / (contentHeight + 64f)).coerceIn(0.000001f, 6f)
            viewportX = (width - contentWidth * viewportScale) / 2f - left * viewportScale
            viewportY = (height - contentHeight * viewportScale) / 2f - top * viewportScale
        }
        renderCompleted()
        onViewportChanged?.invoke(viewportX, viewportY, viewportScale)
    }

    /** Receives the ViewModel's desired state, not a stale database emission. */
    fun setStoredStrokes(requestedRecords: List<InkStrokeRecord>) {
        // Compose can apply a frame prepared before the most recent pointer-up or undo.
        // Read the session's current state at application time when hosted by the editor.
        val records = currentPageId?.let { currentPageStrokes?.invoke(it) } ?: requestedRecords
        if (storedRecords == records) return
        storedRecords = records
        val incoming = records.associateBy { it.id }
        desiredIds.clear()
        desiredIds += incoming.keys
        completed.keys.retainAll(incoming.keys - eraserPreview.keys)
        val waitingIds = activeStrokes.values.mapTo(mutableSetOf()) { it.recordId }
        incoming.values.forEach { record ->
            lastStrokeTime = maxOf(lastStrokeTime, record.createdAt)
            val waitingForHandoff = record.id in waitingIds
            if (!waitingForHandoff && record.id !in eraserPreview && completed[record.id]?.record != record) {
                record.decode()?.let { completed[record.id] = it }
            }
        }
        renderCompleted()
    }

    fun removeStroke(recordId: String) {
        desiredIds.remove(recordId); completed.remove(recordId); renderCompleted()
    }
    fun restoreStroke(record: InkStrokeRecord) {
        desiredIds += record.id
        record.decode()?.let { completed[record.id] = it; renderCompleted() }
    }

    internal fun displayedStrokeIds(): Set<String> = completed.keys.toSet()
    internal fun viewport(): Triple<Float, Float, Float> = Triple(viewportX, viewportY, viewportScale)
    fun viewportCenter() = PointF((width / 2f - viewportX) / viewportScale, (height / 2f - viewportY) / viewportScale)
    internal fun renderedStrokeIds(): Set<String> = completedView.visibleStrokeIds()

    fun centerOn(x: Float, y: Float) {
        if (activeStrokes.isNotEmpty() || eraserPointerId != MotionEvent.INVALID_POINTER_ID || fingerPointerId != MotionEvent.INVALID_POINTER_ID) return
        viewportX = width / 2f - x * viewportScale
        viewportY = height / 2f - y * viewportScale
        renderCompleted()
        onViewportChanged?.invoke(viewportX, viewportY, viewportScale)
    }

    private fun renderCompleted() {
        if (documentConfigured && !infiniteCanvas && width > 0 && height > 0 && pageWidth > 0f && pageHeight > 0f) {
            val (x, y, _) = centeredPaperViewport(viewportY, viewportScale, width.toFloat(), height.toFloat(), pageWidth, pageHeight)
            viewportX = x
            viewportY = y
        }
        completedView.setStrokes(completed.values.toList(),
            viewportX, viewportY, viewportScale, infiniteCanvas, paperBackground, pageWidth, pageHeight)
        selectionOverlay.viewportX = viewportX
        selectionOverlay.viewportY = viewportY
        selectionOverlay.viewportScale = viewportScale
        selectionOverlay.invalidate()
        pdfView?.setPdf(pdfSourcePath, pdfPage, viewportX, viewportY, viewportScale)
        layoutPageStrip()
    }

    private fun handleTouch(view: View, event: MotionEvent): Boolean {
        if (!inputEnabled) return true
        if (documentConfigured && !infiniteCanvas && event.actionMasked == MotionEvent.ACTION_DOWN &&
            (event.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER || fingerWriting)) {
            if (event.x !in viewportX..(viewportX + pageWidth * viewportScale) || event.y !in viewportY..(viewportY + pageHeight * viewportScale)) {
                pageNeighbors.firstOrNull { neighbor ->
                    val adjacent = neighborViews[neighbor.page.id] ?: return@firstOrNull false
                    val (x, y, scale) = adjacent.viewport()
                    event.x in (x + adjacent.translationX)..(x + adjacent.translationX + neighbor.page.pageWidth * scale) &&
                        event.y in (y + adjacent.translationY)..(y + adjacent.translationY + neighbor.page.pageHeight * scale)
                }?.let { onPageNavigate?.invoke(it.direction) }
                return true
            }
        }
        if (selectionOverlay.selectionEnabled) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) selectionNavigation = false
            if (event.pointerCount > 1 && (0 until event.pointerCount).all { event.getToolType(it) == MotionEvent.TOOL_TYPE_FINGER }) {
                selectionOverlay.cancelGesture()
                selectionNavigation = true
            }
            if (!selectionNavigation) return selectionOverlay.handle(event)
        }
        if (!viewportLocked && (0 until event.pointerCount).all { event.getToolType(it) == MotionEvent.TOOL_TYPE_FINGER } && !suppressFingers) scaleDetector.onTouchEvent(event)
        val index = event.actionIndex
        val pointerId = event.getPointerId(index)
        InkTrace.log("input action=${event.actionMasked} id=$pointerId type=${event.getToolType(index)} " +
            "pressure=${event.getPressure(index)} pointers=${event.pointerCount} flags=${event.flags} " +
            "viewport=$viewportX,$viewportY")
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            cancelInk(event)
            cancelEraser()
            cancelPan()
            suppressFingers = false
            return true
        }
        if (Build.VERSION.SDK_INT >= 33 && event.flags and MotionEvent.FLAG_CANCELED != 0 &&
            (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_POINTER_UP)) {
            // Cancellation belongs to the lifted pointer, not every pointer in this event.
            if (pointerId == activePointerId) cancelInk(event)
            if (pointerId == fingerPointerId) cancelPan()
            if (pointerId == eraserPointerId) cancelEraser()
            if (event.actionMasked == MotionEvent.ACTION_UP) suppressFingers = false
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val type = event.getToolType(index)
                if (type == MotionEvent.TOOL_TYPE_STYLUS || type == MotionEvent.TOOL_TYPE_ERASER) {
                    if (activePointerId != MotionEvent.INVALID_POINTER_ID && !activeIsFinger) return true
                    cancelInk(event) // A pen takes priority over an earlier finger/palm.
                    cancelEraser()
                    cancelPan()
                    suppressFingers = true
                    startWriting(view, event, index, type == MotionEvent.TOOL_TYPE_ERASER)
                } else if (type == MotionEvent.TOOL_TYPE_FINGER && !suppressFingers) {
                    val fingers = (0 until event.pointerCount).filter { event.getToolType(it) == MotionEvent.TOOL_TYPE_FINGER }
                    if (fingerWriting && !selectionOverlay.selectionEnabled && fingers.size >= 2) {
                        cancelInk(event)
                        cancelEraser()
                        if (fingerPointerId == MotionEvent.INVALID_POINTER_ID) beginPan(event, fingers.first())
                    } else if (fingerWriting && !selectionOverlay.selectionEnabled && event.actionMasked == MotionEvent.ACTION_DOWN) {
                        startWriting(view, event, index, false)
                    } else if ((!fingerWriting || selectionOverlay.selectionEnabled) && fingerPointerId == MotionEvent.INVALID_POINTER_ID) {
                        beginPan(event, index)
                    }
                    if (fingers.size >= 2) {
                        lastX = fingers.map { event.getX(it) }.average().toFloat()
                        lastY = fingers.map { event.getY(it) }.average().toFloat()
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val activeIndex = event.findPointerIndex(activePointerId)
                val eraserIndex = event.findPointerIndex(eraserPointerId)
                val fingerIndex = event.findPointerIndex(fingerPointerId)
                when {
                    activeIndex >= 0 -> {
                        activeStrokeId?.let { activeStrokes[it]?.append(event, activeIndex) }
                        activeStrokeId?.let { strokeId ->
                            withAuthoringEvent(event) { inProgressView.addToStroke(it, activePointerId, strokeId, null) }
                        }
                    }
                    eraserIndex >= 0 -> {
                        // Include historical samples and the swept path, not just the last event.
                        repeat(event.historySize) { history ->
                            eraseTo(event.getHistoricalX(eraserIndex, history), event.getHistoricalY(eraserIndex, history))
                        }
                        eraseTo(event.getX(eraserIndex), event.getY(eraserIndex))
                    }
                    fingerIndex >= 0 -> {
                        val fingerCount = (0 until event.pointerCount).count { event.getToolType(it) == MotionEvent.TOOL_TYPE_FINGER }
                        val fingers = (0 until event.pointerCount).filter { event.getToolType(it) == MotionEvent.TOOL_TYPE_FINGER }
                        val x = fingers.map { event.getX(it) }.average().toFloat()
                        val y = fingers.map { event.getY(it) }.average().toFloat()
                        if ((!fingerWriting || selectionOverlay.selectionEnabled || fingerCount >= 2) && !scaleDetector.isInProgress) {
                            val dx = x - lastX; val dy = y - lastY
                            panTravelX += dx; panTravelY += dy
                            if (infiniteCanvas || pageNeighbors.isEmpty() || panScaled) {
                                viewportX += dx; viewportY += dy
                                pageSwipeOffset = 0f
                            } else if (pageLayout == PageLayout.HORIZONTAL) {
                                viewportY += dy
                                pageSwipeOffset = if (abs(panTravelX) > abs(panTravelY) * 1.2f) panTravelX else 0f
                            } else {
                                panRawY += dy
                                viewportY = panRawY
                                renderCompleted()
                                pageSwipeOffset = if (abs(panTravelY) > abs(panTravelX) * 1.2f) panRawY - viewportY else 0f
                            }
                            val direction = if (pageSwipeOffset < 0f) 1 else -1
                            if (pageNeighbors.none { it.direction == direction }) pageSwipeOffset = 0f
                            renderCompleted()
                        } else if (scaleDetector.isInProgress) {
                            panScaled = true; pageSwipeOffset = 0f; panRawY = viewportY
                            renderCompleted()
                        }
                        lastX = x
                        lastY = y
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (pointerId == activePointerId) {
                    val active = activeStrokes[activeStrokeId]
                    active?.append(event, index)
                    // Store completed edits on UP, independently of the delayed wet/dry render handoff.
                    active?.let {
                        val record = strokeRecordFromInputs(it.recordId, it.pageId, it.inputs,
                            it.tool, it.color, it.size, it.createdAt)
                        it.finishedRecord = record
                        val json = org.json.JSONObject(record.payload)
                        it.finishedBounds = InkBounds(
                            json.getDouble("left").toFloat(), json.getDouble("top").toFloat(),
                            json.getDouble("right").toFloat(), json.getDouble("bottom").toFloat())
                        desiredIds += record.id
                        onStrokeCommitted?.invoke(record)
                        InkTrace.log("stroke completed id=${record.id} inputs=${it.inputs.size}")
                    }
                    activeStrokeId?.let { strokeId ->
                        withAuthoringEvent(event) { inProgressView.finishStroke(it, activePointerId, strokeId) }
                    }
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                    activeStrokeId = null
                }
                if (pointerId == eraserPointerId) {
                    eraseTo(event.getX(index), event.getY(index))
                    eraserPointerId = MotionEvent.INVALID_POINTER_ID
                    // Only a completed gesture edits storage/history. Rejected palms roll back previews.
                    eraserPreview.values.toList().forEach { onStrokeErased?.invoke(it) }
                    eraserPreview.clear()
                    publishPendingInk()
                }
                if (pointerId == fingerPointerId) {
                    val remaining = (0 until event.pointerCount).filter { it != index && event.getToolType(it) == MotionEvent.TOOL_TYPE_FINGER }
                    fingerPointerId = remaining.firstOrNull()?.let { event.getPointerId(it) } ?: MotionEvent.INVALID_POINTER_ID
                    val turn = if (remaining.isEmpty() && abs(pageSwipeOffset) >= max(64f * resources.displayMetrics.density,
                            min((if (pageLayout == PageLayout.HORIZONTAL) width else height) * .2f, 160f * resources.displayMetrics.density))) {
                        if (pageSwipeOffset < 0f) 1 else -1
                    } else 0
                    if (remaining.isEmpty()) { onViewportChanged?.invoke(viewportX, viewportY, viewportScale); pageSwipeOffset = 0f; renderCompleted() }
                    publishPendingInk()
                    if (turn != 0) onPageNavigate?.invoke(turn)
                    InkTrace.log("pan committed viewport=$viewportX,$viewportY")
                }
                val remaining = (0 until event.pointerCount).filter { it != index && event.getToolType(it) == MotionEvent.TOOL_TYPE_FINGER }
                if (remaining.isNotEmpty() && fingerPointerId != MotionEvent.INVALID_POINTER_ID) {
                    lastX = remaining.map { event.getX(it) }.average().toFloat()
                    lastY = remaining.map { event.getY(it) }.average().toFloat()
                }
                if (event.actionMasked == MotionEvent.ACTION_UP) suppressFingers = false
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
        }
        return true
    }

    private fun startWriting(view: View, event: MotionEvent, index: Int, hardwareEraser: Boolean) {
        if (tool == InkTool.Eraser || hardwareEraser) {
            eraserPointerId = event.getPointerId(index)
            lastX = event.getX(index)
            lastY = event.getY(index)
            publishPendingInk()
            eraseAt(lastX, lastY)
            return
        }
        activePointerId = event.getPointerId(index)
        activeIsFinger = event.getToolType(index) == MotionEvent.TOOL_TYPE_FINGER
        view.requestUnbufferedDispatch(event)
        val strokeSize = size
        val brush = brushFor(tool, color, strokeSize)
        val strokeId = withAuthoringEvent(event) { normalized ->
            inProgressView.startStroke(normalized, activePointerId, brush, screenToDocumentMatrix(), Matrix())
        }
        activeStrokeId = strokeId
        lastStrokeTime = maxOf(System.currentTimeMillis(), lastStrokeTime + 1)
        activeStrokes[strokeId] = ActiveStroke(UUID.randomUUID().toString(),
            requireNotNull(currentPageId), tool, color, strokeSize, lastStrokeTime,
            brush, event.eventTime, viewportX, viewportY, viewportScale,
            if (activeIsFinger) InputToolType.TOUCH else InputToolType.STYLUS).apply { append(event, index) }
        publishPendingInk()
    }

    private fun cancelInk(event: MotionEvent? = null) {
        val strokeId = activeStrokeId ?: return
        inProgressView.cancelStroke(strokeId, event)
        activeStrokes.remove(strokeId)
        activeStrokeId = null
        activePointerId = MotionEvent.INVALID_POINTER_ID
        publishPendingInk()
        InkTrace.log("stroke canceled")
    }

    /** Missing hardware pressure uses the same neutral sample in authoring and saved input. */
    private fun <T> withAuthoringEvent(event: MotionEvent, work: (MotionEvent) -> T): T {
        if (event.device?.getMotionRange(MotionEvent.AXIS_PRESSURE) != null) return work(event)
        val properties = Array(event.pointerCount) { index -> MotionEvent.PointerProperties().also { event.getPointerProperties(index, it) } }
        fun coordinates(history: Int? = null) = Array(event.pointerCount) { index ->
            MotionEvent.PointerCoords().also {
                if (history == null) event.getPointerCoords(index, it) else event.getHistoricalPointerCoords(index, history, it)
                it.pressure = .5f
            }
        }
        val historical = event.actionMasked == MotionEvent.ACTION_MOVE && event.historySize > 0
        val normalized = MotionEvent.obtain(event.downTime, if (historical) event.getHistoricalEventTime(0) else event.eventTime,
            event.action, event.pointerCount, properties, coordinates(if (historical) 0 else null), event.metaState,
            event.buttonState, event.xPrecision, event.yPrecision, event.deviceId, event.edgeFlags, event.source, event.flags)
        try {
            if (historical) {
                for (index in 1 until event.historySize) normalized.addBatch(event.getHistoricalEventTime(index), coordinates(index), event.metaState)
                normalized.addBatch(event.eventTime, coordinates(), event.metaState)
            }
            return work(normalized)
        } finally { normalized.recycle() }
    }

    private fun publishPendingInk() {
        if (acceptingCallbacks) {
            onPendingInkChanged?.invoke(selectionPending + activeStrokes.size +
                (if (eraserPointerId != MotionEvent.INVALID_POINTER_ID) 1 else 0) +
                (if (fingerPointerId != MotionEvent.INVALID_POINTER_ID) 1 else 0))
        }
    }

    private fun cancelEraser() {
        if (eraserPointerId == MotionEvent.INVALID_POINTER_ID) return
        eraserPreview.values.forEach { record ->
            desiredIds += record.id
            record.decode()?.let { completed[record.id] = it }
        }
        eraserPreview.clear()
        eraserPointerId = MotionEvent.INVALID_POINTER_ID
        renderCompleted()
        publishPendingInk()
        InkTrace.log("eraser canceled")
    }

    private fun beginPan(event: MotionEvent, index: Int) {
        fingerPointerId = event.getPointerId(index)
        lastX = event.getX(index)
        lastY = event.getY(index)
        panStartX = viewportX
        panStartY = viewportY
        panStartScale = viewportScale
        panTravelX = 0f; panTravelY = 0f; panRawY = viewportY; panScaled = false; pageSwipeOffset = 0f
        publishPendingInk()
    }

    private fun cancelPan() {
        if (fingerPointerId == MotionEvent.INVALID_POINTER_ID) return
        viewportX = panStartX
        viewportY = panStartY
        viewportScale = panStartScale
        pageSwipeOffset = 0f
        fingerPointerId = MotionEvent.INVALID_POINTER_ID
        publishPendingInk()
        renderCompleted()
        InkTrace.log("pan canceled viewport=$viewportX,$viewportY")
    }

    private fun eraseTo(x: Float, y: Float) {
        val steps = ceil(hypot(x - lastX, y - lastY) / eraserRadius().coerceAtLeast(1f)).toInt().coerceAtLeast(1)
        repeat(steps) { step ->
            val t = (step + 1f) / steps
            eraseAt(lastX + (x - lastX) * t, lastY + (y - lastY) * t)
        }
        lastX = x
        lastY = y
    }

    private fun eraserRadius() = size * resources.displayMetrics.density

    private fun eraseAt(screenX: Float, screenY: Float) {
        val x = (screenX - viewportX) / viewportScale
        val y = (screenY - viewportY) / viewportScale
        val radius = eraserRadius() / viewportScale
        val hitBox = ImmutableBox.fromCenterAndDimensions(ImmutableVec(x, y), radius * 2f, radius * 2f)
        val hits = completed.values.filter {
            it.bounds.contains(x, y, radius + it.stroke.brush.size) &&
                Intersection.run { hitBox.intersects(it.stroke.shape, ImmutableAffineTransform.scale(1f)) }
        }
        hits.forEach { hit ->
            eraserPreview[hit.record.id] = hit.record
            removeStroke(hit.record.id)
            InkTrace.log("eraser preview id=${hit.record.id}")
        }
    }

    override fun onAttachedToWindow() {
        acceptingCallbacks = true
        super.onAttachedToWindow()
        InkTrace.log("canvas attached")
    }

    private fun screenToDocumentMatrix() = Matrix().apply {
        val inverse = 1f / viewportScale
        setValues(floatArrayOf(inverse, 0f, -viewportX * inverse, 0f, inverse, -viewportY * inverse, 0f, 0f, 1f))
    }

    override fun onDetachedFromWindow() {
        selectionOverlay.cancelGesture()
        cancelInk()
        cancelEraser()
        cancelPan()
        // Completed records already belong to the ViewModel; the old render view may never hand off.
        activeStrokes.clear()
        publishPendingInk()
        acceptingCallbacks = false
        InkTrace.log("canvas detached pending=${activeStrokes.size}")
        super.onDetachedFromWindow()
    }

    private class CompletedInkView(context: Context) : View(context) {
        private val renderer = ViewStrokeRenderer(CanvasStrokeRenderer.create(), this)
        var onContentError: ((String) -> Unit)? = null
        private val contentRenderer = ContentRenderer(context, { invalidate() }, { onContentError?.invoke(it) })
        private var content: List<ContentObject> = emptyList()
        fun setContent(value: List<ContentObject>) {
            if (content == value) return
            content = value; rebuildDrawOrder(); invalidate()
        }
        override fun onAttachedToWindow() { super.onAttachedToWindow(); contentRenderer.attach() }
        override fun onDetachedFromWindow() { contentRenderer.detach(); super.onDetachedFromWindow() }
        private var strokes: List<DecodedInkStroke> = emptyList()
        private data class DrawItem(val time: Long, val id: String, val bounds: InkBounds,
            val stroke: Stroke? = null, val content: ContentObject? = null)
        private var drawOrder: List<DrawItem> = emptyList()
        private fun rebuildDrawOrder() {
            drawOrder = (strokes.map { DrawItem(it.record.createdAt, it.record.id,
                InkBounds(it.bounds.left - it.stroke.brush.size, it.bounds.top - it.stroke.brush.size,
                    it.bounds.right + it.stroke.brush.size, it.bounds.bottom + it.stroke.brush.size), stroke = it.stroke) } +
                content.map { DrawItem(it.entity.createdAt, it.entity.id, it.bounds, content = it) })
                .sortedWith(compareBy<DrawItem> { it.time }.thenBy { it.id })
        }
        private var viewportX = 0f
        private var viewportY = 0f
        private var viewportScale = 1f
        private var infinite = false
        private var background = "blank"
        private var pageWidth = 595f
        private var pageHeight = 842f
        private var hasPdfBackground = false
        private var spacingMm = 7f
        private var patternWeight = "normal"
        private var paperColor = android.graphics.Color.WHITE
        fun setPdfBackground(value: Boolean) { hasPdfBackground = value; invalidate() }
        fun setPaperTemplate(spacing: Float, weight: String, color: Int) {
            spacingMm = spacing.coerceIn(2f, 12f); patternWeight = weight; paperColor = color; invalidate()
        }
        fun visibleStrokeIds(): Set<String> {
            val left = -viewportX / viewportScale
            val top = -viewportY / viewportScale
            val right = (width - viewportX) / viewportScale
            val bottom = (height - viewportY) / viewportScale
            return strokes.filter { it.bounds.right + it.stroke.brush.size >= left &&
                it.bounds.left - it.stroke.brush.size <= right &&
                it.bounds.bottom + it.stroke.brush.size >= top &&
                it.bounds.top - it.stroke.brush.size <= bottom }.map { it.record.id }.toSet()
        }
        fun setStrokes(value: List<DecodedInkStroke>, x: Float, y: Float, scale: Float, isInfinite: Boolean, paperBackground: String, width: Float, height: Float) {
            if (strokes != value) { strokes = value; rebuildDrawOrder() }
            viewportX = x
            viewportY = y
            viewportScale = scale
            infinite = isInfinite
            background = paperBackground
            pageWidth = width
            pageHeight = height
            invalidate()
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            renderer.drawWithStrokes(canvas) { scope ->
                canvas.save()
                canvas.translate(viewportX, viewportY)
                canvas.scale(viewportScale, viewportScale)
                drawPaper(canvas)
                val left = -viewportX / viewportScale
                val top = -viewportY / viewportScale
                val right = (width - viewportX) / viewportScale
                val bottom = (height - viewportY) / viewportScale
                val visible = drawOrder.filter { it.bounds.right >= left && it.bounds.left <= right &&
                    it.bounds.bottom >= top && it.bounds.top <= bottom }
                contentRenderer.prepare(visible.mapNotNull { it.content }, viewportScale)
                visible.forEach { item ->
                    item.stroke?.let { scope.drawStroke(it) }
                    item.content?.let { contentRenderer.draw(canvas, it) }
                }
                canvas.restore()
            }
        }
        private fun drawPaper(canvas: Canvas) {
            if (hasPdfBackground) return
            val template = com.ramst.notein.storage.PaperTemplate(background, pageWidth, pageHeight, spacingMm, patternWeight, paperColor)
            if (infinite) {
                val left = -viewportX / viewportScale
                val top = -viewportY / viewportScale
                PaperRenderer.draw(canvas, template, left, top, left + width / viewportScale, top + height / viewportScale)
            } else PaperRenderer.draw(canvas, template)
        }
    }

    private class ActiveStroke(val recordId: String, val pageId: String,
        val tool: InkTool, val color: Int, val size: Float, val createdAt: Long,
        val brush: Brush, val startTime: Long, val viewportX: Float, val viewportY: Float, val viewportScale: Float,
        val inputTool: InputToolType) {
        val inputs = MutableStrokeInputBatch()
        var finishedRecord: InkStrokeRecord? = null
        var finishedBounds: InkBounds? = null

        fun append(event: MotionEvent, index: Int) {
            val hasPressure = event.device?.getMotionRange(MotionEvent.AXIS_PRESSURE) != null
            repeat(event.historySize) { history ->
                appendSample(event.getHistoricalX(index, history), event.getHistoricalY(index, history),
                    event.getHistoricalEventTime(history), if (hasPressure) event.getHistoricalPressure(index, history) else .5f)
            }
            appendSample(event.getX(index), event.getY(index), event.eventTime, if (hasPressure) event.getPressure(index) else .5f)
        }

        private fun appendSample(screenX: Float, screenY: Float, time: Long, pressure: Float) {
            val x = (screenX - viewportX) / viewportScale
            val y = (screenY - viewportY) / viewportScale
            if (!x.isFinite() || !y.isFinite()) return
            val previous = if (inputs.size > 0) inputs[inputs.size - 1] else null
            val elapsed = (time - startTime).coerceAtLeast(previous?.elapsedTimeMillis ?: 0)
            if (previous != null && previous.x == x && previous.y == y && previous.elapsedTimeMillis == elapsed) return
            inputs.add(inputTool, x, y, elapsed, pressure = if (pressure.isFinite() && pressure in 0f..1f) pressure else 0.5f)
        }
    }
}

