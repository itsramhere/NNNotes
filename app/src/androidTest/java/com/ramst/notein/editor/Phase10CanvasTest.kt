package com.ramst.notein.editor

import android.view.MotionEvent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ramst.notein.MainActivity
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Phase10CanvasTest {
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var canvas: InkCanvasView
    private val commits = mutableListOf<InkStrokeRecord>()
    @Before fun setup() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            canvas = InkCanvasView(activity).apply {
                setPageId("phase10"); setDocument(true, "grid", 0f, 0f)
                setEditorState(InkTool.Pencil, android.graphics.Color.BLACK, .375f, false, 20f, 30f, 1.25f)
                onStrokeCommitted = { commits += it }
            }
            activity.setContentView(canvas)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }
    @After fun cleanup() { SystemClock.sleep(250); scenario.close() }
    private fun finger(x: Float, y: Float, id: Int = 0) = Phase4Pointer(x, y, id, MotionEvent.TOOL_TYPE_FINGER)
    @Test fun zoomLockBlocksScalingAndCancelledPanWhileWritingAndErasing() {
        scenario.onActivity {
            val before = canvas.viewport(); canvas.setViewportLocked(true)
            phase4Event(canvas, MotionEvent.ACTION_DOWN, finger(100f, 100f))
            phase4Event(canvas, MotionEvent.ACTION_MOVE, finger(180f, 190f))
            phase4Event(canvas, MotionEvent.ACTION_POINTER_DOWN or (1 shl 8), finger(180f, 190f), finger(380f, 190f, 1))
            repeat(10) { n -> phase4Event(canvas, MotionEvent.ACTION_MOVE, finger(170f - n * 40f, 220f), finger(390f + n * 40f, 220f, 1)) }
            phase4Event(canvas, MotionEvent.ACTION_CANCEL, finger(10f, 220f), finger(800f, 220f, 1))
            repeat(2) { phase4Event(canvas, MotionEvent.ACTION_DOWN, finger(200f, 200f)); phase4Event(canvas, MotionEvent.ACTION_UP, finger(200f, 200f)) }
            val wheel = MotionEvent.obtain(0, 0, MotionEvent.ACTION_SCROLL, 0f, 0f, 0)
            try { canvas.dispatchGenericMotionEvent(wheel) } finally { wheel.recycle() }
            canvas.fitPage(); canvas.fitContent()
            assertEquals(before, canvas.viewport())
            drawSyntheticStroke(canvas, 100f, 120f)
            assertEquals(.375f, commits.single().decode()!!.stroke.brush.size, .00001f)
            assertEquals(.5f, commits.single().decode()!!.stroke.inputs[0].pressure, .00001f)
            assertEquals(before, canvas.viewport())
        }
        SystemClock.sleep(350)
        scenario.onActivity {
            canvas.setStoredStrokes(commits)
            canvas.setEditorState(InkTool.Eraser, 0, 6f, false, 20f, 30f, 1.25f)
            var erased = 0; canvas.onStrokeErased = { erased++ }
            tapSyntheticStylus(canvas, 100f, 120f)
            assertEquals(1, erased); assertEquals(Triple(20f, 30f, 1.25f), canvas.viewport())
        }
    }
    @Test fun zoomLockAllowsSingleFingerPanAndOverviewAtTheSameScale() {
        scenario.onActivity {
            canvas.setViewportLocked(true)
            var saved: Triple<Float, Float, Float>? = null
            canvas.onViewportChanged = { x, y, scale -> saved = Triple(x, y, scale) }
            phase4Event(canvas, MotionEvent.ACTION_DOWN, finger(100f, 100f))
            phase4Event(canvas, MotionEvent.ACTION_MOVE, finger(180f, 190f))
            phase4Event(canvas, MotionEvent.ACTION_UP, finger(180f, 190f))
            assertEquals(Triple(100f, 120f, 1.25f), canvas.viewport())
            assertEquals(canvas.viewport(), saved)
            canvas.centerOn(400f, 500f)
            assertEquals(1.25f, canvas.viewport().third, 0f)
            assertEquals(400f, canvas.viewportCenter().x, .01f)
            assertEquals(500f, canvas.viewportCenter().y, .01f)
            assertEquals(canvas.viewport(), saved)
        }
    }
    @Test fun zoomLockAllowsTwoFingerPanWithChangingSpanAndFingerWritingEnabled() {
        scenario.onActivity {
            canvas.setEditorState(InkTool.Pencil, android.graphics.Color.BLACK, .375f, true, 20f, 30f, 1.25f)
            canvas.setViewportLocked(true)
            phase4Event(canvas, MotionEvent.ACTION_DOWN, finger(100f, 100f))
            phase4Event(canvas, MotionEvent.ACTION_POINTER_DOWN or (1 shl 8), finger(100f, 100f), finger(300f, 100f, 1))
            // The centroid moves by (40, 60) while the finger spacing grows.
            phase4Event(canvas, MotionEvent.ACTION_MOVE, finger(90f, 160f), finger(390f, 160f, 1))
            phase4Event(canvas, MotionEvent.ACTION_POINTER_UP or (1 shl 8), finger(90f, 160f), finger(390f, 160f, 1))
            phase4Event(canvas, MotionEvent.ACTION_UP, finger(90f, 160f))
            assertEquals(Triple(60f, 90f, 1.25f), canvas.viewport())
            assertTrue(commits.isEmpty())
        }
    }
    @Test fun toggleMidGestureKeepsCurrentViewAndUnlockDoesNotApplyDeferredMovement() {
        scenario.onActivity {
            phase4Event(canvas, MotionEvent.ACTION_DOWN, finger(100f, 100f))
            phase4Event(canvas, MotionEvent.ACTION_MOVE, finger(150f, 160f))
            val before = canvas.viewport(); canvas.setViewportLocked(true)
            phase4Event(canvas, MotionEvent.ACTION_MOVE, finger(350f, 360f))
            canvas.setViewportLocked(false)
            phase4Event(canvas, MotionEvent.ACTION_MOVE, finger(450f, 460f))
            phase4Event(canvas, MotionEvent.ACTION_UP, finger(450f, 460f))
            assertEquals(before, canvas.viewport())
            phase4Event(canvas, MotionEvent.ACTION_DOWN, finger(100f, 100f))
            phase4Event(canvas, MotionEvent.ACTION_MOVE, finger(110f, 120f))
            phase4Event(canvas, MotionEvent.ACTION_UP, finger(110f, 120f))
            assertEquals(before.first + 10f, canvas.viewport().first, .01f)
        }
    }
    @Test fun lockedLassoSelectionAndTextObjectMovementRemainAvailable() {
        scenario.onActivity {
            canvas.setEditorState(InkTool.Pen, 0, 6f, false, 0f, 0f, 1f)
            canvas.setViewportLocked(true)
            val text = phase4Text("phase10", 100.0, 100.0)
            var selected = false; var moved = false
            canvas.setContentObjects(listOf(text), emptySet(), true, { selected = true }, { _, _, _ -> moved = true }, {}, {}, {})
            phase4Event(canvas, MotionEvent.ACTION_DOWN, Phase4Pointer(50f, 50f))
            for ((x, y) in listOf(350f to 50f, 350f to 250f, 50f to 250f, 50f to 50f)) phase4Event(canvas, MotionEvent.ACTION_MOVE, Phase4Pointer(x, y))
            phase4Event(canvas, MotionEvent.ACTION_UP, Phase4Pointer(50f, 50f))
            assertTrue(selected)
            canvas.setContentObjects(listOf(text), setOf(text.id), true, {}, { _, _, _ -> moved = true }, {}, {}, {})
            phase4Event(canvas, MotionEvent.ACTION_DOWN, Phase4Pointer(140f, 130f))
            phase4Event(canvas, MotionEvent.ACTION_MOVE, Phase4Pointer(180f, 170f))
            phase4Event(canvas, MotionEvent.ACTION_UP, Phase4Pointer(180f, 170f))
            assertTrue(moved); assertEquals(Triple(0f, 0f, 1f), canvas.viewport())
        }
    }
}
