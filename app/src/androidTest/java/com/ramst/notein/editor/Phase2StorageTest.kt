package com.ramst.notein.editor

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ramst.notein.storage.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class Phase2StorageTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databaseName = "phase2-test-${UUID.randomUUID()}.db"
    private lateinit var db: NoteinDatabase
    private lateinit var notebookId: String
    private lateinit var page: PageEntity
    private lateinit var model: EditorViewModel
    private val store = ViewModelStore()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Before fun setup() = runBlocking {
        db = Room.databaseBuilder(context, NoteinDatabase::class.java, databaseName).build()
        notebookId = NotebookRepository(db.notebookDao(), db.folderDao()).createNotebook("Ink fixture", null)
        page = db.editorDao().firstPage(notebookId)!!
        instrumentation.runOnMainSync {
            model = EditorViewModel(notebookId, EditorRepository(db.editorDao()), com.ramst.notein.pdf.PdfRepository(context, db.pdfDao()))
            store.put("editor", model)
        }
        await { !model.uiState.value.isLoading }
    }

    @After fun cleanup() {
        instrumentation.runOnMainSync { store.clear() }
        db.close()
        context.deleteDatabase(databaseName) // Unique test DB only; never clear user data.
    }

    private fun await(predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!predicate() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(20)
        assertTrue("Timed out waiting for editor storage", predicate())
    }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun stored() = runBlocking { db.editorDao().observePageObjects(page.id).first() }

    @Test fun writeEraseUndoRedoAgreeWithStoredRecords() {
        val first = record(pageId = page.id)
        val second = record(y = 200f, endY = 200f, pageId = page.id, tool = InkTool.Highlighter)
        val controller = InkEditorController()
        main { model.onStrokeCommitted(first); model.onStrokeCommitted(second) }
        await { model.uiState.value.saveMessage == "Saved" && stored().size == 2 }
        main { model.onStrokeErased(first) }
        await { model.uiState.value.saveMessage == "Saved" && stored().size == 1 }
        assertEquals(second.id, model.uiState.value.strokes.single().id)
        main { model.undo(controller) }
        await { model.uiState.value.saveMessage == "Saved" && stored().size == 2 }
        main { model.redo(controller) }
        await { model.uiState.value.saveMessage == "Saved" && stored().size == 1 }
        assertEquals(second.payload, stored().single().payload)
        assertEquals(second.id, model.uiState.value.strokes.single().id)
    }

    @Test fun failedSaveKeepsLatestStrokeInEditorState() {
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER phase2_fail BEFORE INSERT ON editable_objects BEGIN SELECT RAISE(ABORT, 'injected disk failure'); END",
        )
        val stroke = record(pageId = page.id)
        main { model.onStrokeCommitted(stroke) }
        await { model.uiState.value.saveMessage == "Changes kept in memory" }
        assertTrue(stored().isEmpty())
        assertEquals("Unsaved ink must survive recomposition/activity recreation", stroke.id,
            model.uiState.value.strokes.single().id)
        main { model.selectColor(0xFFB3261E.toInt()); model.updateViewport(40f, 30f) }
        await { model.uiState.value.canRetry }
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER phase2_fail")
        main { model.retrySave() }
        await { model.uiState.value.saveMessage == "Saved" }
        assertEquals(stroke.payload, stored().single().payload)
        val reopened = runBlocking { db.editorDao().firstPage(notebookId)!! }
        assertEquals(0xFFB3261E.toInt(), reopened.brushColor)
        assertEquals(40f, reopened.viewportX, 0.01f)
        assertFalse(model.uiState.value.hasUnsavedChanges)
    }

    @Test fun constantWidthHighlighterDoesNotUsePressureWhilePenDoes() {
        val lowMarker = record(tool = InkTool.Highlighter, pressure = 0.1f).decode()!!.stroke
        val highMarker = record(tool = InkTool.Highlighter, pressure = 0.9f).decode()!!.stroke
        val lowPen = record(pressure = 0.1f).decode()!!.stroke
        val highPen = record(pressure = 0.9f).decode()!!.stroke
        assertEquals(lowMarker.shape.computeBoundingBox()!!.height,
            highMarker.shape.computeBoundingBox()!!.height, 0.01f)
        assertTrue(highPen.shape.computeBoundingBox()!!.height > lowPen.shape.computeBoundingBox()!!.height)
        assertEquals(0.1f, lowPen.inputs[0].pressure, 0.01f)
        assertEquals(0.9f, highPen.inputs[0].pressure, 0.01f)
        val noPressureMarker = record(tool = InkTool.Highlighter,
            pressure = androidx.ink.strokes.StrokeInput.NO_PRESSURE).decode()!!.stroke
        assertEquals(lowMarker.shape.computeBoundingBox()!!.height,
            noPressureMarker.shape.computeBoundingBox()!!.height, 0.01f)
    }

    @Test fun rapidHistoryAndSettingsCannotResurrectErasedInkOrRegressPageState() {
        val controller = InkEditorController()
        val strokes = (0..29).map { record(pageId = page.id, y = 100f + it * 3f, endY = 100f + it * 3f) }
        main {
            strokes.forEach(model::onStrokeCommitted)
            model.onStrokeErased(strokes[4])
            model.undo(controller)
            model.redo(controller)
            model.selectThickness(12f)
            model.updateViewport(90f, -60f)
            model.selectColor(0xFF0A6E4F.toInt())
        }
        await { model.uiState.value.saveMessage == "Saved" }
        assertEquals(strokes.filterNot { it.id == strokes[4].id }.map { it.id }.toSet(), stored().map { it.id }.toSet())
        assertEquals(stored().map { it.id }.toSet(), model.uiState.value.strokes.map { it.id }.toSet())
        val state = runBlocking { db.editorDao().firstPage(notebookId)!! }
        assertEquals(12f, state.brushSize, 0.01f)
        assertEquals(90f, state.viewportX, 0.01f)
        assertEquals(-60f, state.viewportY, 0.01f)
        assertEquals(0xFF0A6E4F.toInt(), state.brushColor)
    }

    @Test fun snapshotFailureRollsBackErasureAndPageStateThenRetryPreservesOtherObjectTypes() = runBlocking {
        val repository = EditorRepository(db.editorDao())
        val original = record(pageId = page.id)
        repository.saveSnapshot(listOf(original.toEntity()), page)
        val unknown = EditableObjectEntity("future-object", page.id, "future-type", "untouched", 1, 1)
        db.editorDao().insertObject(unknown)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER phase2_state_fail BEFORE UPDATE ON pages BEGIN SELECT RAISE(ABORT, 'injected state failure'); END")
        try {
            repository.saveSnapshot(emptyList(), page.copy(viewportX = 99f))
            fail("Expected transactional failure")
        } catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(setOf(original.id, unknown.id), stored().map { it.id }.toSet())
        assertEquals(0f, db.editorDao().firstPage(notebookId)!!.viewportX, 0.01f)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER phase2_state_fail")
        repository.saveSnapshot(emptyList(), page.copy(viewportX = 99f))
        assertEquals(unknown, stored().single())
        assertEquals(99f, db.editorDao().firstPage(notebookId)!!.viewportX, 0.01f)
    }

    @Test fun pendingInkCannotBeReportedSavedBeforeHandoff() {
        main { model.onPendingInkChanged(1) }
        assertEquals("Finishing ink…", model.uiState.value.saveMessage)
        val stroke = record(pageId = page.id)
        main { model.onStrokeCommitted(stroke) }
        await { !model.uiState.value.hasUnsavedChanges }
        assertEquals("Finishing ink…", model.uiState.value.saveMessage)
        main { model.onPendingInkChanged(0) }
        assertEquals("Saved", model.uiState.value.saveMessage)
    }
}
