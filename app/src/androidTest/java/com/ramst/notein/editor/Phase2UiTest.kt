package com.ramst.notein.editor

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ramst.notein.MainActivity
import com.ramst.notein.NoteinApplication
import com.ramst.notein.storage.NoteinDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class Phase2UiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private var id: String? = null
    private val title = "Ink-UI-${UUID.randomUUID()}"
    private val notebooks get() = (compose.activity.application as NoteinApplication).container.notebookRepository
    private val editor get() = (compose.activity.application as NoteinApplication).container.editorRepository

    private fun openFixture() {
        id = runBlocking { notebooks.createNotebook(title, null) }
        compose.onNodeWithText("Search titles").performTextReplacement(title)
        compose.activityRule.scenario.onActivity { activity ->
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(title) and !hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText(title) and !hasSetTextAction()).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Finger writing").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun records() = runBlocking {
        val page = editor.firstPage(id!!)!!
        editor.observeObjects(page.id).first()
    }

    private fun saved(count: Int) {
        compose.waitUntil(10_000) {
            records().size == count && compose.onAllNodesWithText("Saved").fetchSemanticsNodes().isNotEmpty()
        }
        // A persisted toolbar change must reach AndroidView before synthetic native input.
        compose.waitForIdle()
    }

    private fun draw(x: Float, y: Float) {
        compose.activityRule.scenario.onActivity { activity ->
            drawSyntheticStroke(requireNotNull(findInkCanvas(activity.window.decorView)), x, y)
        }
    }

    private fun screenshot(name: String) {
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.getExternalFilesDir(null), name).outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        image.recycle()
    }

    @After fun cleanup() {
        runBlocking { id?.let { notebooks.trash(it); notebooks.permanentlyDelete(it) } }
    }

    @Test fun realEditorToolbarWriteEraseUndoRedoRecreateAndReopen() {
        openFixture()
        compose.onNodeWithText("Highlighter", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("Ink color Red").performClick()
        compose.onNodeWithContentDescription("Ink thickness").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(12f) }
        saved(0)
        draw(80f, 100f)
        saved(1)
        val marker = records().single()
        val decoded = marker.toInkStrokeRecord()!!.decode()!!
        assertEquals(InkTool.Highlighter, InkTool.fromStorage(org.json.JSONObject(marker.payload).getString("tool")))
        assertEquals(0xFFB3261E.toInt(), org.json.JSONObject(marker.payload).getInt("color"))
        assertEquals(12f * compose.activity.resources.displayMetrics.density, decoded.stroke.brush.size, 0.01f)
        compose.onNodeWithText("Pen", useUnmergedTree = true).performClick()
        saved(1)
        draw(250f, 220f)
        saved(2)
        val expected = records().associate { it.id to it.payload }
        compose.onNodeWithText("Eraser", useUnmergedTree = true).performClick()
        saved(2)
        compose.activityRule.scenario.onActivity { activity ->
            tapSyntheticStylus(requireNotNull(findInkCanvas(activity.window.decorView)), 90f, 105f)
        }
        saved(1)
        compose.onNodeWithText("Undo", useUnmergedTree = true).performClick()
        saved(2)
        compose.onNodeWithText("Redo", useUnmergedTree = true).performClick()
        saved(1)
        compose.onNodeWithText("Undo", useUnmergedTree = true).performClick()
        saved(2)
        assertEquals(expected, records().associate { it.id to it.payload })
        screenshot("phase2-editor-saved.png")
        compose.activityRule.scenario.recreate()
        saved(2)
        assertEquals(expected, records().associate { it.id to it.payload })
        screenshot("phase2-editor-recreated.png")
        compose.onNodeWithContentDescription("Back to notebooks").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(title) and !hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText(title) and !hasSetTextAction()).performClick()
        saved(2)
        assertEquals(expected, records().associate { it.id to it.payload })
        // History is intentionally per editor session, not a persistent undo database.
        compose.onNodeWithText("Undo").assertIsNotEnabled()
    }

    @Test fun storageFailureShowsRetryAndKeepsInkAcrossActivityRecreation() {
        openFixture()
        val db = NoteinDatabase.create(compose.activity)
        val pageId = runBlocking { editor.firstPage(id!!)!!.id }
        try {
            // Scoped trigger: other notebooks are unaffected even if a check fails.
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER phase2_ui_fail BEFORE INSERT ON editable_objects " +
                "WHEN NEW.page_id = '$pageId' BEGIN SELECT RAISE(ABORT, 'injected storage failure'); END")
            draw(120f, 140f)
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Retry save").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(records().isEmpty())
            compose.onNodeWithContentDescription("Back to notebooks").assertIsNotEnabled()
            compose.activityRule.scenario.recreate()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Retry save").fetchSemanticsNodes().isNotEmpty() }
            screenshot("phase2-editor-unsaved.png")
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER phase2_ui_fail")
            compose.onNodeWithText("Retry save", useUnmergedTree = true).performClick()
            saved(1)
            compose.onNodeWithContentDescription("Back to notebooks").assertIsEnabled()
            assertTrue(records().single().toInkStrokeRecord()!!.decode()!!.stroke.inputs.size >= 3)
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS phase2_ui_fail")
            db.close()
        }
    }

    @Test fun completedStrokeSurvivesImmediateActivityRecreationBeforeHandoff() {
        openFixture()
        draw(100f, 100f)
        // Do not wait for Saved/Ink handoff first: exercise the lifecycle boundary itself.
        compose.activityRule.scenario.recreate()
        saved(1)
        assertTrue(records().single().toInkStrokeRecord()!!.decode()!!.stroke.inputs.size >= 3)
    }
}

internal fun findInkCanvas(view: View): InkCanvasView? {
    if (view is InkCanvasView) return view
    if (view is ViewGroup) repeat(view.childCount) { findInkCanvas(view.getChildAt(it))?.let { canvas -> return canvas } }
    return null
}

internal fun drawSyntheticStroke(canvas: InkCanvasView, x: Float, y: Float) {
    val start = SystemClock.uptimeMillis()
    sendSynthetic(canvas, start, start, MotionEvent.ACTION_DOWN, x, y, 0.2f)
    sendSynthetic(canvas, start, start + 16, MotionEvent.ACTION_MOVE, x + 60f, y + 30f, 0.6f)
    sendSynthetic(canvas, start, start + 32, MotionEvent.ACTION_UP, x + 120f, y + 60f, 0.9f)
}

internal fun tapSyntheticStylus(canvas: InkCanvasView, x: Float, y: Float) {
    val start = SystemClock.uptimeMillis()
    sendSynthetic(canvas, start, start, MotionEvent.ACTION_DOWN, x, y, 0.5f)
    sendSynthetic(canvas, start, start + 16, MotionEvent.ACTION_UP, x, y, 0.5f)
}

private fun sendSynthetic(canvas: InkCanvasView, down: Long, time: Long, action: Int, x: Float, y: Float, pressure: Float) {
    val property = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS }
    val coord = MotionEvent.PointerCoords().apply { this.x = x; this.y = y; this.pressure = pressure; size = 0.1f }
    val event = MotionEvent.obtain(down, time, action, 1, arrayOf(property), arrayOf(coord),
        0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
    try { canvas.dispatchTouchEvent(event) } finally { event.recycle() }
}
