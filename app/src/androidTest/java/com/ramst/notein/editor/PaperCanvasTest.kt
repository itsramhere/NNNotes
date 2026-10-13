package com.ramst.notein.editor

import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ramst.notein.MainActivity
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PaperCanvasTest {
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var canvas: InkCanvasView
    @Before fun setup() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            canvas = InkCanvasView(activity).apply { setPageId("centered-paper") }
            activity.setContentView(canvas)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        scenario.onActivity {
            canvas.setDocument(false, "grid", 300f, canvas.height * 2f)
            canvas.setEditorState(InkTool.Pen, android.graphics.Color.BLACK, 6f, false, 9000f, -100f, 1f)
        }
    }
    @After fun cleanup() { scenario.close() }
    private fun finger(x: Float, y: Float, id: Int = 0) = Phase4Pointer(x, y, id, MotionEvent.TOOL_TYPE_FINGER)
    @Test fun diagonalPanOnlyScrollsVerticallyAndStopsAtPaperEdgesWhileZoomLocked() {
        scenario.onActivity {
            canvas.setViewportLocked(true)
            val before = canvas.viewport()
            phase4Event(canvas, MotionEvent.ACTION_DOWN, finger(100f, 200f))
            phase4Event(canvas, MotionEvent.ACTION_MOVE, finger(240f, 140f))
            phase4Event(canvas, MotionEvent.ACTION_UP, finger(240f, 140f))
            assertEquals(before.first, canvas.viewport().first, 0f)
            assertEquals(-160f, canvas.viewport().second, 0f)
            assertEquals(1f, canvas.viewport().third, 0f)
            canvas.centerOn(-9000f, 90000f)
            assertEquals(before.first, canvas.viewport().first, 0f)
            assertEquals(-canvas.height.toFloat(), canvas.viewport().second, 0f)
            canvas.centerOn(9000f, -90000f)
            assertEquals(0f, canvas.viewport().second, 0f)
        }
    }
    @Test fun offCenterPinchKeepsPaperCenteredAndCancellationRestoresScrollAndScale() {
        scenario.onActivity {
            val before = canvas.viewport()
            phase4Event(canvas, MotionEvent.ACTION_DOWN, finger(150f, 200f))
            phase4Event(canvas, MotionEvent.ACTION_POINTER_DOWN or (1 shl 8), finger(150f, 200f), finger(350f, 200f, 1))
            repeat(10) { n -> phase4Event(canvas, MotionEvent.ACTION_MOVE, finger(140f - n * 40f, 200f), finger(360f + n * 40f, 200f, 1)) }
            val zoomed = canvas.viewport()
            assertTrue(zoomed.third > before.third)
            assertEquals(canvas.width / 2f, zoomed.first + 300f * zoomed.third / 2f, .01f)
            phase4Event(canvas, MotionEvent.ACTION_CANCEL, finger(10f, 200f), finger(800f, 200f, 1))
            assertEquals(before, canvas.viewport())
        }
    }
    @Test fun smallPaperCentersInBothAxesAndWritingUsesTheCenteredTransform() {
        scenario.onActivity {
            canvas.setDocument(false, "blank", 300f, 400f)
            canvas.setEditorState(InkTool.Pen, android.graphics.Color.BLACK, 6f, false, -9000f, -9000f, 1f)
            val before = canvas.viewport()
            assertEquals((canvas.width - 300f) / 2f, before.first, 0f)
            assertEquals((canvas.height - 400f) / 2f, before.second, 0f)
            var committed: InkStrokeRecord? = null
            canvas.onStrokeCommitted = { committed = it }
            drawSyntheticStroke(canvas, before.first + 100f, before.second + 120f)
            val input = committed!!.decode()!!.stroke.inputs[0]
            assertEquals(100f, input.x, .01f)
            assertEquals(120f, input.y, .01f)
            assertEquals(before, canvas.viewport())
        }
    }
}
