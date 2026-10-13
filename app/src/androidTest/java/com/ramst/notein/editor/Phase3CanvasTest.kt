package com.ramst.notein.editor

import android.os.SystemClock
import android.view.MotionEvent
import android.view.InputDevice
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ramst.notein.MainActivity
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Phase3CanvasTest {
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var canvas: InkCanvasView
    private val commits = java.util.concurrent.CopyOnWriteArrayList<InkStrokeRecord>()
    @Before fun setup() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            canvas = InkCanvasView(activity).apply { setPageId("phase3"); setDocument(true, "grid", 0f, 0f); onStrokeCommitted = { commits += it } }
            activity.setContentView(canvas)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }
    @After fun cleanup() { SystemClock.sleep(300); scenario.close() }
    @Test fun repeatedTransformsKeepNegativeAndFarOriginStrokeCoordinatesStable() {
        for ((x, y, scale) in listOf(Triple(400f, 500f, 2f), Triple(-20000f, 15000f, 0.5f), Triple(120f, -90f, 4f))) {
            scenario.onActivity {
                canvas.setEditorState(InkTool.Pen, -15066082, 6f, false, x, y, scale)
                drawSyntheticStroke(canvas, 100f, 120f)
            }
            val input = commits.last().decode()!!.stroke.inputs[0]
            assertEquals((100f - x) / scale, input.x, 0.02f)
            assertEquals((120f - y) / scale, input.y, 0.02f)
            SystemClock.sleep(250)
        }
        val original = commits.map { it.payload }
        repeat(20) { i -> scenario.onActivity {
            canvas.setStoredStrokes(commits.toList())
            canvas.setEditorState(InkTool.Pen, -15066082, 6f, false, i * -500f, i * 100f, 0.5f + i * 0.2f)
        } }
        assertEquals(original, commits.map { it.payload })
        assertTrue(commits.any { it.decode()!!.stroke.inputs[0].x < 0f })
        assertTrue(commits.any { it.decode()!!.stroke.inputs[0].x > 30000f })
    }
    @Test fun fitContentIncludesWidelySeparatedInkAndRenderingCullsOffscreenInk() {
        val records = listOf(record(-50000f, -30000f, -49900f, -29900f), record(60000f, 40000f, 60100f, 40100f))
        scenario.onActivity {
            canvas.setStoredStrokes(records)
            canvas.fitContent()
            val (x, y, scale) = canvas.viewport()
            records.forEach { record -> val bounds = record.decode()!!.bounds
                assertTrue(bounds.left * scale + x >= 0f)
                assertTrue(bounds.right * scale + x <= canvas.width)
                assertTrue(bounds.top * scale + y >= 0f)
                assertTrue(bounds.bottom * scale + y <= canvas.height)
            }
            assertEquals(2, canvas.renderedStrokeIds().size)
            canvas.setEditorState(InkTool.Pen, -15066082, 6f, false, 0f, 0f, 1f)
            assertTrue(canvas.renderedStrokeIds().isEmpty())
        }
    }
    @Test fun overviewUsesActualSurfaceSizeAndNavigationCentersMappedDocumentPoint() {
        val previews = listOf(record(-300f, -200f, 900f, 600f).toPreview()!!)
        scenario.onActivity {
            val surface = Size(canvas.width.toFloat(), canvas.height.toFloat())
            val geometry = overviewGeometry(previews, 70f, -40f, 2f, surface, Size(128f, 96f))
            assertEquals(surface.width / 2f, geometry.viewport.right - geometry.viewport.left, 0.01f)
            assertEquals(surface.height / 2f, geometry.viewport.bottom - geometry.viewport.top, 0.01f)
            val target = Offset(320f, -100f)
            val mapped = geometry.toWorld(geometry.toPreview(target))
            assertEquals(target.x, mapped.x, 0.01f)
            canvas.setEditorState(InkTool.Pen, -15066082, 6f, false, 70f, -40f, 2f)
            canvas.centerOn(mapped.x, mapped.y)
            val (x,y,s) = canvas.viewport()
            assertEquals(surface.width / 2f, mapped.x * s + x, 0.01f)
            assertEquals(surface.height / 2f, mapped.y * s + y, 0.01f)
        }
    }
    @Test fun symmetricPinchPreservesFocusAndCancellationRestoresOriginalViewport() {
        scenario.onActivity {
            canvas.setEditorState(InkTool.Pen, -15066082, 6f, false, 20f, 30f, 1f)
            var time = SystemClock.uptimeMillis()
            val down = time
            fun event(action: Int, vararg points: android.graphics.PointF) {
                time += 20
                val properties = points.indices.map { MotionEvent.PointerProperties().apply { id = it; toolType = MotionEvent.TOOL_TYPE_FINGER } }.toTypedArray()
                val coords = points.map { p -> MotionEvent.PointerCoords().apply { x = p.x; y = p.y; pressure = 1f; size = 0.1f } }.toTypedArray()
                val event = MotionEvent.obtain(down, time, action, points.size, properties, coords, 0,0,1f,1f,0,0,InputDevice.SOURCE_TOUCHSCREEN,0)
                try { canvas.dispatchTouchEvent(event) } finally { event.recycle() }
            }
            event(MotionEvent.ACTION_DOWN, android.graphics.PointF(150f, 200f))
            event(MotionEvent.ACTION_POINTER_DOWN or (1 shl 8), android.graphics.PointF(150f, 200f), android.graphics.PointF(350f, 200f))
            // Exceed the device's minimum scaling span even on a high-density emulator.
            repeat(10) { i -> event(MotionEvent.ACTION_MOVE, android.graphics.PointF(140f - i * 40f, 200f), android.graphics.PointF(360f + i * 40f, 200f)) }
            val (x,y,s) = canvas.viewport()
            assertTrue("Synthetic pinch must actually zoom", s > 1f)
            assertEquals(230f, (250f - x) / s, 0.05f)
            assertEquals(170f, (200f - y) / s, 0.05f)
            event(MotionEvent.ACTION_CANCEL, android.graphics.PointF(60f,200f), android.graphics.PointF(440f,200f))
            assertEquals(Triple(20f,30f,1f), canvas.viewport())
        }
    }
}

