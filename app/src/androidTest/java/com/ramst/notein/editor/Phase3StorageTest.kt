package com.ramst.notein.editor

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ramst.notein.storage.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class Phase3StorageTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "phase3-${UUID.randomUUID()}.db"
    private lateinit var db: NoteinDatabase
    private lateinit var repo: EditorRepository
    private lateinit var id: String
    private val store = ViewModelStore()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @Before fun setup() = runBlocking {
        db = Room.databaseBuilder(context, NoteinDatabase::class.java, name).build()
        repo = EditorRepository(db.editorDao())
        id = NotebookRepository(db.notebookDao(), db.folderDao()).createNotebook("Phase3 fixture", null)
    }
    @After fun cleanup() { main { store.clear() }; db.close(); context.deleteDatabase(name) }
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun model(): EditorViewModel {
        lateinit var model: EditorViewModel
        main { model = EditorViewModel(id, repo, com.ramst.notein.pdf.PdfRepository(context, db.pdfDao())); store.put("editor", model) }
        await { !model.uiState.value.isLoading && !model.uiState.value.pageBusy }
        return model
    }
    private fun await(check: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (!check() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(20)
        assertTrue("Timed out waiting for Phase 3 state", check())
    }
    @Test fun duplicateReorderDeletePreserveIndependentInkAndContiguousOrder() = runBlocking {
        val source = repo.firstPage(id)!!
        val ink = listOf(record(pageId = source.id), record(pageId = source.id, y = 200f, endY = 210f))
        repo.saveSnapshot(ink.map { it.toEntity() }, source.copy(viewportX = -500f, viewportScale = 2f))
        repo.updateBackground(source.id, PaperBackground.GRID)
        val duplicate = repo.duplicatePage(source.id)
        val copy = db.editorDao().page(duplicate)!!
        val copied = db.editorDao().pageObjects(duplicate)
        // Match the renderer's source order, including strokes sharing a timestamp.
        assertEquals(ink.sortedWith(compareBy<InkStrokeRecord> { it.createdAt }.thenBy { it.id }).map { it.payload },
            copied.sortedWith(compareBy<EditableObjectEntity> { it.createdAt }.thenBy { it.id }).map { it.payload })
        assertTrue(copied.none { it.id in ink.map { ink -> ink.id } })
        assertEquals("grid", copy.paperBackground)
        assertEquals(2f, copy.viewportScale, 0f)
        repo.saveSnapshot(listOf(copied.last()), copy)
        assertEquals(2, db.editorDao().pageObjects(source.id).size)
        val third = repo.createPage(id, NotebookType.STANDARD)
        repo.reorderPages(id, listOf(third, duplicate, source.id))
        assertEquals(listOf(third, duplicate, source.id), db.editorDao().pages(id).map { it.id })
        repo.deletePage(duplicate, id)
        assertEquals(listOf(0, 1), db.editorDao().pages(id).map { it.sortIndex })
        assertTrue(db.editorDao().pageObjects(duplicate).isEmpty())
        repo.deletePage(third, id)
        try { repo.deletePage(source.id, id); fail("Final page must survive") } catch (_: IllegalStateException) { }
        assertEquals(2, db.editorDao().pageObjects(source.id).size)
    }
    @Test fun pageSelectionViewportBackgroundAndUndoIsolationSurviveReopening() = runBlocking {
        val first = repo.firstPage(id)!!
        val second = repo.createPage(id, NotebookType.STANDARD)
        val model = model()
        main { model.onStrokeCommitted(record(pageId = first.id)) }
        await { !model.uiState.value.hasUnsavedChanges }
        main { model.selectPage(second) }
        await { model.uiState.value.page?.id == second && !model.uiState.value.pageBusy }
        assertFalse(model.uiState.value.canUndo)
        main { model.updateViewport(-12000f, 23000f, 0.05f); model.onStrokeCommitted(record(pageId = second, x = -20000f, endX = -19900f)) }
        await { !model.uiState.value.hasUnsavedChanges }
        main { model.selectBackground(PaperBackground.LINED) }
        await { !model.uiState.value.pageBusy }
        main { store.clear() }
        val reopened = model()
        assertEquals(second, reopened.uiState.value.page!!.id)
        assertEquals(-12000f, reopened.uiState.value.page!!.viewportX, 0f)
        assertEquals(0.05f, reopened.uiState.value.page!!.viewportScale, 0f)
        assertEquals("lined", reopened.uiState.value.page!!.paperBackground)
        assertEquals(-20000f, reopened.uiState.value.strokes.single().decode()!!.stroke.inputs[0].x, 0.1f)
    }
    @Test fun pendingInkAndFailedSaveBlockPageChangesWithoutLosingEdits() = runBlocking {
        val first = repo.firstPage(id)!!
        val second = repo.createPage(id, NotebookType.STANDARD)
        val model = model()
        main { model.onPendingInkChanged(1); model.selectPage(second); model.duplicateCurrentPage() }
        assertEquals(first.id, model.uiState.value.page!!.id)
        assertEquals(2, db.editorDao().pageCount(id))
        main { model.onPendingInkChanged(0) }
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER phase3_fail BEFORE INSERT ON editable_objects BEGIN SELECT RAISE(ABORT, 'injected failure'); END")
        val ink = record(pageId = first.id)
        main { model.onStrokeCommitted(ink) }
        await { model.uiState.value.canRetry }
        main { model.selectPage(second); model.deleteCurrentPage() }
        assertEquals(first.id, model.uiState.value.page!!.id)
        assertEquals(ink.id, model.uiState.value.strokes.single().id)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER phase3_fail")
        main { model.retrySave() }
        await { !model.uiState.value.hasUnsavedChanges }
        assertEquals(ink.id, db.editorDao().pageObjects(first.id).single().id)
    }
    @Test fun rapidInkIsNotReplacedByDatabaseEmissionsDuringPageWork() {
        val model = model()
        val pageId = model.uiState.value.page!!.id
        val inks = (0..79).map { record(pageId = pageId, y = it * 10f) }
        main { inks.forEach(model::onStrokeCommitted); model.updateViewport(30f, -90f, 3f) }
        await { !model.uiState.value.hasUnsavedChanges }
        assertEquals(inks.map { it.id }.toSet(), model.uiState.value.strokes.map { it.id }.toSet())
        assertEquals(80, runBlocking { db.editorDao().pageObjects(pageId).size })
    }
    @Test fun toolbarCannotSaveAnEmptySnapshotWhileDuplicateIsLoading() {
        val model = model()
        val source = model.uiState.value.page!!.id
        main { model.onStrokeCommitted(record(pageId = source)) }
        await { !model.uiState.value.hasUnsavedChanges }
        main { model.duplicateCurrentPage(); model.selectTool(InkTool.Highlighter); model.undo(InkEditorController()) }
        await { !model.uiState.value.pageBusy && model.uiState.value.page?.id != source }
        assertEquals(1, model.uiState.value.strokes.size)
        assertEquals(1, runBlocking { db.editorDao().pageObjects(model.uiState.value.page!!.id).size })
        assertEquals(1, runBlocking { db.editorDao().pageObjects(source).size })
        assertFalse(model.uiState.value.hasUnsavedChanges)
    }
    @Test fun thumbnailGenerationRunsInBackgroundWhileWritingContinues() = runBlocking {
        val model = model()
        val page = model.uiState.value.page!!
        val other = repo.createPage(id, NotebookType.STANDARD)
        val large = (0..499).map { record(pageId = other, y = it.toFloat()) }
        val started = SystemClock.elapsedRealtime()
        repo.saveSnapshot(large.map { it.toEntity() }, db.editorDao().page(other)!!)
        val writingStarted = SystemClock.elapsedRealtime()
        main { model.onStrokeCommitted(record(pageId = page.id)) }
        val dispatchMs = SystemClock.elapsedRealtime() - writingStarted
        await { !model.uiState.value.hasUnsavedChanges && model.uiState.value.previews[other]?.size == 500 }
        println("PHASE3_PREVIEW_PROFILE records=500 generationAndSaveMs=${SystemClock.elapsedRealtime() - started} mainDispatchMs=$dispatchMs")
        assertEquals(1, db.editorDao().pageObjects(page.id).size)
        assertTrue("Preview work must not monopolize the main thread", dispatchMs < 1000)
    }
}
