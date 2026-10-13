package com.ramst.notein.editor

import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ramst.notein.MainActivity
import com.ramst.notein.storage.PageEntity
import com.ramst.notein.storage.PageLayout
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PageSwipeCanvasTest {
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var canvas: InkCanvasView
    private val turns = mutableListOf<Int>()
    @Before fun setup() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity -> canvas = InkCanvasView(activity); activity.setContentView(canvas) }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        scenario.onActivity {
            canvas.setPageId("middle"); canvas.setDocument(false, "grid", 300f, canvas.height * 2f)
            canvas.setEditorState(InkTool.Pen, android.graphics.Color.BLACK, 6f, false, 0f, -100f, 1f)
            navigation(PageLayout.HORIZONTAL)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }
    @After fun cleanup() { scenario.close() }
    private fun finger(x: Float, y: Float, id: Int = 0) = Phase4Pointer(x, y, id, MotionEvent.TOOL_TYPE_FINGER)
    private fun navigation(layout: PageLayout, directions: List<Int> = listOf(-1, 1)) {
        canvas.setPageNavigation(layout, directions.map { direction ->
            PageNeighbor(direction, PageEntity("neighbor-$direction", "notebook", direction + 1, 0, 0,
                pageWidth = 300f, pageHeight = canvas.height * 2f), emptyList(), null)
        }) { turns += it }
    }
    private fun drag(dx: Float, dy: Float, cancel: Boolean = false) {
        phase4Event(canvas, MotionEvent.ACTION_DOWN, finger(400f, 400f))
        phase4Event(canvas, MotionEvent.ACTION_MOVE, finger(400f + dx, 400f + dy))
        phase4Event(canvas, if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, finger(400f + dx, 400f + dy))
    }
    @Test fun horizontalTurnsRequireEnoughMovementAndCancellationCannotTurnPages() {
        scenario.onActivity {
            canvas.setViewportLocked(true)
            val before = canvas.viewport()
            drag(-20f, 0f); assertTrue(turns.isEmpty()); assertEquals(before, canvas.viewport())
            drag(-400f, 0f, true); assertTrue(turns.isEmpty()); assertEquals(before, canvas.viewport())
            drag(-400f, 0f); drag(400f, 0f)
            assertEquals(listOf(1, -1), turns)
            assertEquals(before, canvas.viewport())
        }
    }
    @Test fun verticalTurnsOnlyAfterScrollingPastAnEdgeAndReversalRetractsOverscroll() {
        scenario.onActivity {
            navigation(PageLayout.VERTICAL); canvas.setViewportLocked(true)
            drag(0f, -50f); assertTrue(turns.isEmpty()); assertEquals(-150f, canvas.viewport().second, 0f)
            phase4Event(canvas, MotionEvent.ACTION_DOWN, finger(400f, 400f))
            phase4Event(canvas, MotionEvent.ACTION_MOVE, finger(400f, -canvas.height * 3f))
            phase4Event(canvas, MotionEvent.ACTION_MOVE, finger(400f, 400f))
            phase4Event(canvas, MotionEvent.ACTION_UP, finger(400f, 400f))
            assertTrue(turns.isEmpty()); assertEquals(-150f, canvas.viewport().second, 0f)
            drag(0f, -canvas.height * 4f); assertEquals(listOf(1), turns)
            drag(0f, canvas.height * 4f); assertEquals(listOf(1, -1), turns)
            assertEquals(1f, canvas.viewport().third, 0f)
        }
    }
    @Test fun notebookEndsDoNotWrapAndChangingLayoutCancelsTheOldGesture() {
        scenario.onActivity {
            navigation(PageLayout.HORIZONTAL, listOf(1))
            drag(400f, 0f); assertTrue(turns.isEmpty())
            phase4Event(canvas, MotionEvent.ACTION_DOWN, finger(400f, 400f))
            phase4Event(canvas, MotionEvent.ACTION_MOVE, finger(0f, 400f))
            navigation(PageLayout.VERTICAL)
            phase4Event(canvas, MotionEvent.ACTION_UP, finger(0f, 400f))
            assertTrue(turns.isEmpty())
            drag(-400f, 0f); assertTrue(turns.isEmpty())
        }
    }
    @Test fun pinchAndStylusWritingDoNotTurnPages() {
        scenario.onActivity {
            phase4Event(canvas, MotionEvent.ACTION_DOWN, finger(150f, 200f))
            phase4Event(canvas, MotionEvent.ACTION_POINTER_DOWN or (1 shl 8), finger(150f, 200f), finger(350f, 200f, 1))
            repeat(10) { n -> phase4Event(canvas, MotionEvent.ACTION_MOVE, finger(140f - n * 40f, 200f), finger(360f + n * 40f, 200f, 1)) }
            phase4Event(canvas, MotionEvent.ACTION_POINTER_UP or (1 shl 8), finger(-220f, 200f), finger(720f, 200f, 1))
            phase4Event(canvas, MotionEvent.ACTION_UP, finger(-220f, 200f))
            assertTrue(canvas.viewport().third > 1f); assertTrue(turns.isEmpty())
            var strokes = 0; canvas.onStrokeCommitted = { strokes++ }
            val (x, y, scale) = canvas.viewport()
            drawSyntheticStroke(canvas, x + 100f * scale, y + 120f * scale)
            assertEquals(1, strokes); assertTrue(turns.isEmpty())
        }
    }
    @Test fun fingerWritingRequiresTwoFingerNavigationAndLeavesNoStrayStroke() {
        scenario.onActivity {
            canvas.setEditorState(InkTool.Pen, android.graphics.Color.BLACK, 6f, true, 0f, -100f, 1f)
            canvas.setViewportLocked(true)
            var strokes = 0; canvas.onStrokeCommitted = { strokes++ }
            val start = canvas.viewport().first + 100f
            phase4Event(canvas, MotionEvent.ACTION_DOWN, finger(start, 400f))
            phase4Event(canvas, MotionEvent.ACTION_POINTER_DOWN or (1 shl 8), finger(start, 400f), finger(start + 100f, 400f, 1))
            phase4Event(canvas, MotionEvent.ACTION_MOVE, finger(start - 400f, 400f), finger(start - 300f, 400f, 1))
            phase4Event(canvas, MotionEvent.ACTION_POINTER_UP or (1 shl 8), finger(start - 400f, 400f), finger(start - 300f, 400f, 1))
            phase4Event(canvas, MotionEvent.ACTION_UP, finger(start - 400f, 400f))
            assertEquals(listOf(1), turns); assertEquals(0, strokes)
        }
    }
}
