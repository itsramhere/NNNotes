package com.ramst.notein.editor

import android.graphics.Color
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ramst.notein.MainActivity
import com.ramst.notein.storage.EditableObjectEntity
import com.ramst.notein.storage.EditorDao
import com.ramst.notein.storage.EditorRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HandwritingRegressionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(check: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20000
        while (!check() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(20)
        assertTrue("Handwriting regression timed out", check())
    }

    @Test fun queuedOldStorageSnapshotCannotDeleteNewerSavedHandwriting() = runBlocking {
        val f = Phase10Fixture()
        val store = ViewModelStore()
        val notifications = Channel<List<EditableObjectEntity>>(Channel.RENDEZVOUS)
        try {
            val notebook = f.notebooks.createNotebook("Stale ink regression", null, autoMatchingPages = false)
            val page = f.editor.firstPage(notebook)!!
            val dao = object : EditorDao by f.db.editorDao() {
                override fun observePageObjects(pageId: String) = notifications.receiveAsFlow()
            }
            lateinit var model: EditorViewModel
            main { model = EditorViewModel(notebook, EditorRepository(dao), f.pdf); store.put("editor", model) }
            kotlinx.coroutines.withTimeout(20000) { notifications.send(emptyList()) }
            await { !model.uiState.value.pageBusy }
            val first = record(pageId = page.id)
            val second = record(x = 150f, pageId = page.id)
            main { model.onStrokeCommitted(first) }
            await { !model.uiState.value.hasUnsavedChanges }
            main { model.onStrokeCommitted(second) }
            await { !model.uiState.value.hasUnsavedChanges }

            // A rendezvous with the following emission ensures the stale one finished processing.
            kotlinx.coroutines.withTimeout(20000) {
                notifications.send(listOf(first.toEntity()))
                notifications.send(listOf(first.toEntity(), second.toEntity()))
            }
            assertEquals(setOf(first.id, second.id), model.uiState.value.strokes.map { it.id }.toSet())
            val third = record(x = 200f, pageId = page.id)
            main { model.onStrokeCommitted(third) }
            await { !model.uiState.value.hasUnsavedChanges }
            val expected = setOf(first.id, second.id, third.id)
            assertEquals(expected, f.editor.pageObjects(page.id).map { it.id }.toSet())
            main { store.clear(); model = EditorViewModel(notebook, f.editor, f.pdf); store.put("editor", model) }
            await { !model.uiState.value.pageBusy }
            assertEquals(expected, model.uiState.value.strokes.map { it.id }.toSet())
        } finally { main { store.clear() }; notifications.close(); f.close() }
    }

    @Test fun immediateDotsAndCrossbarsSurviveLongBurstsHandoffSaveAndReopen() = runBlocking {
        val f = Phase10Fixture()
        val store = ViewModelStore()
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            val notebook = f.notebooks.createNotebook("Fast handwriting regression", null, autoMatchingPages = false)
            val page = f.editor.firstPage(notebook)!!
            lateinit var model: EditorViewModel
            lateinit var canvas: InkCanvasView
            main { model = EditorViewModel(notebook, f.editor, f.pdf); store.put("editor", model) }
            await { !model.uiState.value.pageBusy }
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity { activity ->
                canvas = InkCanvasView(activity).apply {
                    setPageId(page.id)
                    setDocument(true, "blank", 0f, 0f)
                    setEditorState(InkTool.Pen, Color.BLACK, InkTool.Pen.defaultSize, false, 0f, 0f, 1f)
                    onPendingInkChanged = model::onPendingInkChanged
                    currentPageStrokes = { model.uiState.value.strokes }
                    onStrokeCommitted = { stroke ->
                        model.onStrokeCommitted(stroke)
                        val state = model.uiState.value
                        setContentObjects(state.objects, emptySet(), false, {}, { _, _, _ -> }, {}, {}, {})
                        setStoredStrokes(state.strokes)
                    }
                }
                activity.setContentView(canvas)
            }
            instrumentation.waitForIdleSync()
            val started = SystemClock.uptimeMillis()
            // No sleeps or frame boundaries between the stem, its dot, and the crossbar.
            scenario.onActivity {
                repeat(60) { letter ->
                    val x = 30f + (letter % 20) * 20f
                    val y = 80f + (letter / 20) * 60f
                    shortStroke(canvas, x, y, x, y + 25f, 1)
                    shortStroke(canvas, x, y - 8f, x, y - 8f, 0)
                    shortStroke(canvas, x - 5f, y + 5f, x + 5f, y + 5f, 1)
                }
                assertEquals(180, model.uiState.value.strokes.size)
            }
            await { model.uiState.value.pendingInk == 0 && !model.uiState.value.hasUnsavedChanges }
            val expected = model.uiState.value.strokes.map { it.id }.toSet()
            scenario.onActivity {
                assertEquals(expected, canvas.displayedStrokeIds())
                assertEquals(expected, canvas.renderedStrokeIds())
            }
            instrumentation.waitForIdleSync()
            val location = IntArray(2)
            scenario.onActivity { canvas.getLocationOnScreen(location) }
            var visibleDots = 0
            await {
                val image = instrumentation.uiAutomation.takeScreenshot()
                try {
                    visibleDots = (0 until 60).count { letter ->
                        val x = location[0] + 30 + (letter % 20) * 20
                        val y = location[1] + 72 + (letter / 20) * 60
                        (x - 2..x + 2).any { px -> (y - 2..y + 2).any { py -> Color.red(image.getPixel(px, py)) < 100 } }
                    }
                    if (visibleDots == 60) {
                        val evidence = java.io.File(f.base.filesDir, "handwriting-evidence").apply { mkdirs() }
                        java.io.File(evidence, "rapid-strokes.png").outputStream().use {
                            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                        }
                    }
                    visibleDots == 60
                } finally { image.recycle() }
            }
            assertEquals("Every instant dot must have visible pixels after handoff", 60, visibleDots)
            assertEquals(expected, f.editor.pageObjects(page.id).map { it.id }.toSet())
            model.uiState.value.strokes.forEachIndexed { index, record ->
                val decoded = record.decode()!!
                assertTrue(decoded.stroke.inputs.size >= 1)
                if (index % 3 == 1) {
                    assertEquals(0L, decoded.stroke.inputs[0].elapsedTimeMillis)
                    assertEquals(decoded.bounds.left, decoded.bounds.right, 0f)
                }
            }
            android.util.Log.i("HandwritingRegression", "180 strokes committed, handed off and saved in ${SystemClock.uptimeMillis() - started}ms")
            scenario.close(); scenario = null
            main { store.clear(); model = EditorViewModel(notebook, f.editor, f.pdf); store.put("editor", model) }
            await { !model.uiState.value.pageBusy }
            assertEquals(expected, model.uiState.value.strokes.map { it.id }.toSet())
        } finally { scenario?.close(); main { store.clear() }; f.close() }
    }

    private fun shortStroke(canvas: InkCanvasView, x: Float, y: Float, endX: Float, endY: Float, duration: Long) {
        val down = SystemClock.uptimeMillis()
        fun dispatch(action: Int, px: Float, py: Float, time: Long) {
            val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS })
            val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = px; this.y = py; pressure = .5f })
            val event = MotionEvent.obtain(down, time, action, 1, properties, coords,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
            try { canvas.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        dispatch(MotionEvent.ACTION_DOWN, x, y, down)
        dispatch(MotionEvent.ACTION_UP, endX, endY, down + duration)
    }
}
