package com.ramst.notein.editor

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ramst.notein.MainActivity
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class Phase2CanvasTest {
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var canvas: InkCanvasView
    private val commits = CopyOnWriteArrayList<InkStrokeRecord>()
    private val erasures = CopyOnWriteArrayList<InkStrokeRecord>()
    private val pans = CopyOnWriteArrayList<Pair<Float, Float>>()
    private var clock = SystemClock.uptimeMillis()
    private var downTime = clock
    private val pendingInk = java.util.concurrent.atomic.AtomicInteger(0)

    @Before fun setup() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            canvas = InkCanvasView(activity).apply {
                setPageId("canvas-test-page")
                onStrokeCommitted = { commits += it }
                onStrokeErased = { erasures += it }
                onViewportChanged = { x, y, _ -> pans += x to y }
                onPendingInkChanged = { pendingInk.set(it) }
            }
            activity.setContentView(canvas)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    @After fun close() {
        // Separate completed-edit assertions from the async rendering handoff before disposing the harness.
        await { pendingInk.get() == 0 }
        scenario.close()
    }

    private fun state(tool: InkTool = InkTool.Pen, finger: Boolean = false, x: Float = 0f, y: Float = 0f) {
        scenario.onActivity { canvas.setEditorState(tool, 0xFF244C9A.toInt(), 6f, finger, x, y, 1f) }
    }

    private data class Pointer(val id: Int, val x: Float, val y: Float,
        val type: Int = MotionEvent.TOOL_TYPE_STYLUS, val pressure: Float = 0.5f)

    private fun event(action: Int, vararg pointers: Pointer, flags: Int = 0) {
        clock += 16
        if (action == MotionEvent.ACTION_DOWN) downTime = clock
        val properties = pointers.map { pointer -> MotionEvent.PointerProperties().apply {
            id = pointer.id; toolType = pointer.type
        } }.toTypedArray()
        val coords = pointers.map { pointer -> MotionEvent.PointerCoords().apply {
            x = pointer.x; y = pointer.y; pressure = pointer.pressure; size = 0.1f
        } }.toTypedArray()
        scenario.onActivity {
            val motion = MotionEvent.obtain(downTime, clock, action, pointers.size, properties,
                coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, flags)
            try { canvas.dispatchTouchEvent(motion) } finally { motion.recycle() }
        }
    }

    private fun stroke(x: Float = 100f, y: Float = 100f, pressure: Float = 0.5f) {
        event(MotionEvent.ACTION_DOWN, Pointer(0, x, y, pressure = pressure))
        event(MotionEvent.ACTION_MOVE, Pointer(0, x + 50f, y + 20f, pressure = pressure))
        event(MotionEvent.ACTION_UP, Pointer(0, x + 100f, y + 40f, pressure = pressure))
    }

    private fun await(predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!predicate() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(20)
        assertTrue("Timed out waiting for Ink handoff", predicate())
    }

    @Test fun eraserDragRemovesCrossedStroke() {
        scenario.onActivity { canvas.setStoredStrokes(listOf(record(100f, 100f, 300f, 100f))) }
        state(InkTool.Eraser)
        event(MotionEvent.ACTION_DOWN, Pointer(0, 200f, 250f))
        event(MotionEvent.ACTION_MOVE, Pointer(0, 200f, 100f))
        event(MotionEvent.ACTION_UP, Pointer(0, 200f, 100f))
        assertEquals("Eraser must work during a drag, not only on DOWN", 1, erasures.size)
    }

    @Test fun eraserDoesNotDeleteEmptySpaceInsideDiagonalBounds() {
        scenario.onActivity { canvas.setStoredStrokes(listOf(record(100f, 100f, 300f, 300f))) }
        state(InkTool.Eraser)
        event(MotionEvent.ACTION_DOWN, Pointer(0, 100f, 300f))
        event(MotionEvent.ACTION_UP, Pointer(0, 100f, 300f))
        assertEquals("A bounding rectangle is not the ink geometry", 0, erasures.size)
    }

    @Test fun twoFingerNavigationCancelsFingerInk() {
        state(finger = true)
        val finger = MotionEvent.TOOL_TYPE_FINGER
        event(MotionEvent.ACTION_DOWN, Pointer(0, 100f, 100f, finger))
        event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            Pointer(0, 100f, 100f, finger), Pointer(1, 200f, 100f, finger))
        event(MotionEvent.ACTION_MOVE, Pointer(0, 140f, 130f, finger), Pointer(1, 240f, 130f, finger))
        event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            Pointer(0, 140f, 130f, finger), Pointer(1, 240f, 130f, finger))
        event(MotionEvent.ACTION_UP, Pointer(0, 140f, 130f, finger))
        SystemClock.sleep(500)
        assertEquals("Two-finger navigation must not commit the first finger as ink", 0, commits.size)
        assertEquals(40f, pans.last().first, 0.01f)
        assertEquals(30f, pans.last().second, 0.01f)
    }

    @Test fun canceledPointerDoesNotLeaveWritingStuck() {
        state()
        event(MotionEvent.ACTION_DOWN, Pointer(0, 100f, 100f))
        event(MotionEvent.ACTION_CANCEL, Pointer(0, 150f, 120f), flags = MotionEvent.FLAG_CANCELED)
        stroke(250f, 250f)
        await { commits.size == 1 }
        assertTrue(commits.single().decode()!!.stroke.inputs[0].x > 200f)
    }

    @Test fun fingerNavigationNeverCreatesInkInStylusMode() {
        state()
        val finger = MotionEvent.TOOL_TYPE_FINGER
        event(MotionEvent.ACTION_DOWN, Pointer(0, 100f, 100f, finger))
        event(MotionEvent.ACTION_MOVE, Pointer(0, 140f, 130f, finger))
        event(MotionEvent.ACTION_UP, Pointer(0, 140f, 130f, finger))
        assertEquals(0, commits.size)
        assertEquals(40f, pans.single().first, 0.01f)
    }

    @Test fun rapidPressureStrokesDotsAndViewportUseDocumentCoordinates() {
        state(x = 40f, y = 30f)
        repeat(8) { stroke(100f + it * 20f, 100f + it * 15f, 0.1f + it * 0.1f) }
        event(MotionEvent.ACTION_DOWN, Pointer(0, 80f, 70f))
        event(MotionEvent.ACTION_UP, Pointer(0, 80f, 70f))
        await { commits.size == 9 }
        val inputs = commits.first().decode()!!.stroke.inputs
        assertEquals(60f, inputs[0].x, 0.01f)
        assertEquals(70f, inputs[0].y, 0.01f)
        assertEquals(0.1f, inputs[0].pressure, 0.01f)
        assertTrue(commits.last().decode()!!.stroke.inputs.size >= 1)
        assertEquals(9, commits.map { it.id }.distinct().size)
    }

    @Test fun palmCancellationDoesNotCancelAnotherStylusPointer() {
        state()
        val finger = MotionEvent.TOOL_TYPE_FINGER
        event(MotionEvent.ACTION_DOWN, Pointer(0, 100f, 100f))
        event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            Pointer(0, 100f, 100f), Pointer(1, 300f, 300f, finger))
        event(MotionEvent.ACTION_MOVE, Pointer(0, 150f, 120f), Pointer(1, 340f, 330f, finger))
        event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            Pointer(0, 150f, 120f), Pointer(1, 340f, 330f, finger), flags = MotionEvent.FLAG_CANCELED)
        event(MotionEvent.ACTION_UP, Pointer(0, 200f, 140f))
        await { commits.size == 1 }
        assertEquals(0, pans.size)
        val inputs = commits.single().decode()!!.stroke.inputs
        assertEquals(200f, inputs[inputs.size - 1].x, 0.01f)
    }

    @Test fun fingerBeforeStylusAndFingerAfterStylusDoNotMoveOrInkPage() {
        state(finger = true)
        val finger = MotionEvent.TOOL_TYPE_FINGER
        event(MotionEvent.ACTION_DOWN, Pointer(1, 300f, 300f, finger))
        event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            Pointer(1, 300f, 300f, finger), Pointer(0, 100f, 100f))
        event(MotionEvent.ACTION_MOVE, Pointer(1, 350f, 350f, finger), Pointer(0, 150f, 120f))
        event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            Pointer(1, 350f, 350f, finger), Pointer(0, 200f, 140f))
        event(MotionEvent.ACTION_MOVE, Pointer(1, 400f, 400f, finger))
        event(MotionEvent.ACTION_UP, Pointer(1, 400f, 400f, finger))
        await { commits.size == 1 }
        assertEquals(0, pans.size)
        assertEquals(100f, commits.single().decode()!!.stroke.inputs[0].x, 0.01f)
    }

    @Test fun actionCancelRollsBackPanAndAllowsNextStroke() {
        state()
        val finger = MotionEvent.TOOL_TYPE_FINGER
        event(MotionEvent.ACTION_DOWN, Pointer(0, 100f, 100f, finger))
        event(MotionEvent.ACTION_MOVE, Pointer(0, 180f, 160f, finger))
        event(MotionEvent.ACTION_CANCEL, Pointer(0, 180f, 160f, finger))
        stroke()
        await { commits.size == 1 }
        assertEquals(100f, commits.single().decode()!!.stroke.inputs[0].x, 0.01f)
        assertEquals(0, pans.size)
    }

    @Test fun hardwareEraserTipDeletesInkInsteadOfDrawing() {
        state()
        scenario.onActivity { canvas.setStoredStrokes(listOf(record())) }
        event(MotionEvent.ACTION_DOWN, Pointer(0, 200f, 100f, MotionEvent.TOOL_TYPE_ERASER))
        event(MotionEvent.ACTION_UP, Pointer(0, 200f, 100f, MotionEvent.TOOL_TYPE_ERASER))
        assertEquals(1, erasures.size)
        assertEquals(0, commits.size)
    }

    @Test fun edgeDotsAndPressureChangesKeepAllSamples() {
        state()
        event(MotionEvent.ACTION_DOWN, Pointer(0, 1f, 1f, pressure = 0.1f))
        event(MotionEvent.ACTION_MOVE, Pointer(0, 20f, 20f, pressure = 0.9f))
        event(MotionEvent.ACTION_UP, Pointer(0, 40f, 40f, pressure = 0.4f))
        await { commits.size == 1 }
        val inputs = commits.single().decode()!!.stroke.inputs
        assertEquals(1f, inputs[0].x, 0.01f)
        assertEquals(0.1f, inputs[0].pressure, 0.01f)
        assertEquals(0.9f, inputs[1].pressure, 0.01f)
        assertEquals(0.4f, inputs[2].pressure, 0.01f)
    }

    @Test fun canceledEraserGestureRestoresPreviewAndDoesNotDeleteStoredInk() {
        state(InkTool.Eraser)
        scenario.onActivity { canvas.setStoredStrokes(listOf(record())) }
        event(MotionEvent.ACTION_DOWN, Pointer(0, 200f, 100f))
        event(MotionEvent.ACTION_CANCEL, Pointer(0, 200f, 100f), flags = MotionEvent.FLAG_CANCELED)
        assertEquals(0, erasures.size)
        event(MotionEvent.ACTION_DOWN, Pointer(0, 200f, 100f))
        event(MotionEvent.ACTION_UP, Pointer(0, 200f, 100f))
        assertEquals(1, erasures.size)
    }

    @Test fun delayedRenderHandoffCannotResurrectRemovedStroke() {
        state()
        // All three input events and the removal occur in one main-thread turn before Ink can hand off.
        scenario.onActivity {
            drawSyntheticStroke(canvas, 100f, 100f)
            canvas.removeStroke(commits.single().id)
            canvas.setStoredStrokes(emptyList())
        }
        await { pendingInk.get() == 0 }
        scenario.onActivity { assertTrue(canvas.displayedStrokeIds().isEmpty()) }
    }
}

internal fun record(x: Float = 100f, y: Float = 100f, endX: Float = 300f, endY: Float = 100f,
    pageId: String = "canvas-test-page", tool: InkTool = InkTool.Pen,
    pressure: Float = 0.5f, size: Float = 6f): InkStrokeRecord {
    val inputs = MutableStrokeInputBatch()
        .add(InputToolType.STYLUS, x, y, 0, pressure = pressure)
        .add(InputToolType.STYLUS, endX, endY, 20, pressure = pressure)
    return strokeRecordFrom(UUID.randomUUID().toString(), pageId,
        Stroke(brushFor(tool, 0xFF244C9A.toInt(), size), inputs), tool, 0xFF244C9A.toInt(), size)
}
