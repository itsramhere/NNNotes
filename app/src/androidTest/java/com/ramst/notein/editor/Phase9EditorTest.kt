package com.ramst.notein.editor

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ramst.notein.pdf.PdfRepository
import com.ramst.notein.storage.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class Phase9EditorTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val name="phase9-editor-${UUID.randomUUID()}.db"
    private lateinit var db:NoteinDatabase
    private lateinit var repo:EditorRepository
    private lateinit var id:String
    private lateinit var model:EditorViewModel
    private val store=ViewModelStore()
    private fun main(block:()->Unit)=InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private fun await(check:()->Boolean) {
        val until=SystemClock.uptimeMillis()+15000
        while(!check() && SystemClock.uptimeMillis()<until) SystemClock.sleep(20)
        assertTrue("Timed out waiting for phase9 editor",check())
    }
    private fun saved()=await {!model.uiState.value.hasUnsavedChanges && !model.uiState.value.pageBusy}
    @Before fun setup()=runBlocking {
        db=Room.databaseBuilder(context,NoteinDatabase::class.java,name).build();repo=EditorRepository(db.editorDao())
        id=NotebookRepository(db.notebookDao(),db.folderDao()).createNotebook("Editor fixture",null,template=PaperTemplate("dots",420f,595f,5f,"bold",PaperColor.CREAM.argb))
        main {model=EditorViewModel(id,repo,PdfRepository(context,db.pdfDao()));store.put("editor",model)}
        await {!model.uiState.value.isLoading && !model.uiState.value.pageBusy}
    }
    @After fun cleanup() {main {store.clear()};db.close();context.deleteDatabase(name)}

    @Test fun cancelledInkViewportAndSelectionDoNotAppendAndWritingKeepsFocusThroughUndoRedo() {
        val page=model.uiState.value.page!!
        main {model.onPendingInkChanged(1);model.onPendingInkChanged(0);model.updateViewport(-50f,80f,2f);model.setLassoEnabled(true)}
        saved();assertEquals(1,runBlocking {repo.pages(id).size})
        main {model.setLassoEnabled(false);model.onStrokeCommitted(record(pageId=page.id))}
        saved();await {model.uiState.value.pages.size==2}
        assertEquals(page.id,model.uiState.value.page!!.id);assertEquals(6f,model.uiState.value.page!!.brushSize,0f)
        main {model.undo(InkEditorController())};saved();assertEquals(2,runBlocking {repo.pages(id).size})
        main {model.redo(InkEditorController())};saved();assertEquals(2,runBlocking {repo.pages(id).size})
        val next=model.uiState.value.pages.last()
        main {model.selectPage(next.id)};await {model.uiState.value.page!!.id==next.id && !model.uiState.value.pageBusy}
        main {model.saveText(null,"Next page",24f,android.graphics.Color.BLACK,false,false)}
        saved();await {model.uiState.value.pages.size==3};assertEquals(next.id,model.uiState.value.page!!.id)
    }

    @Test fun allocationErrorSurvivesViewportSaveAndExplicitRetryKeepsSavedWriting() {
        val page=model.uiState.value.page!!
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER phase9_full BEFORE INSERT ON pages BEGIN SELECT RAISE(ABORT,'full disk'); END")
        main {model.onStrokeCommitted(record(pageId=page.id))}
        await {model.uiState.value.canRetry && !model.uiState.value.hasUnsavedChanges}
        assertEquals(1,runBlocking {repo.pageObjects(page.id).size});assertEquals(1,runBlocking {repo.pages(id).size})
        main {model.updateViewport(-200f,40f,1.5f)};saved();assertTrue(model.uiState.value.canRetry)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER phase9_full")
        main {repeat(8) {model.retrySave()}}
        await {!model.uiState.value.canRetry};await {model.uiState.value.pages.size==2}
        assertEquals(page.id,model.uiState.value.page!!.id)
    }

    @Test fun failedContentSaveCreatesNoPageAndRetryCommitsInkBeforeAllocation() {
        val page=model.uiState.value.page!!
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER phase9_save_fail BEFORE INSERT ON editable_objects BEGIN SELECT RAISE(ABORT,'full disk'); END")
        main {model.onStrokeCommitted(record(pageId=page.id))}
        await {model.uiState.value.canRetry}
        assertTrue(model.uiState.value.hasUnsavedChanges);assertTrue(runBlocking {repo.pageObjects(page.id).isEmpty()})
        assertEquals(1,runBlocking {repo.pages(id).size})
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER phase9_save_fail")
        main {model.retrySave()};saved();await {model.uiState.value.pages.size==2}
        assertEquals(1,runBlocking {repo.pageObjects(page.id).size})
    }

    @Test fun notebookDefaultAndToggleRemainCurrentAfterPageEmissionsWithoutChangingPenOrOldPaper() {
        val original=model.uiState.value.page!!
        val changed=PaperTemplate("grid",792f,612f,10f,"fine",PaperColor.DARK_GRAY.argb)
        main {model.updateNotebookTemplate(changed,false)}
        await {model.uiState.value.notebook?.autoMatchingPages==false}
        main {model.createPage()}
        await {model.uiState.value.pages.size==2 && model.uiState.value.page?.id!=original.id && !model.uiState.value.pageBusy}
        assertFalse(model.uiState.value.notebook!!.autoMatchingPages)
        assertEquals(changed,model.uiState.value.page!!.paperTemplate())
        assertEquals(original.paperTemplate(),runBlocking {repo.firstPage(id)!!.paperTemplate()})
        assertEquals(original.brushSize,model.uiState.value.page!!.brushSize,0f)
        val manual=model.uiState.value.page!!
        main {model.onStrokeCommitted(record(pageId=manual.id))};saved();assertEquals(2,runBlocking {repo.pages(id).size})
        main {model.updateNotebookTemplate(changed,true)};await {model.uiState.value.notebook?.autoMatchingPages==true}
        main {model.updateViewport(-10f,10f,2f)};saved();assertEquals(2,runBlocking {repo.pages(id).size})
        main {model.onStrokeCommitted(record(pageId=manual.id,y=240f))};saved();await {model.uiState.value.pages.size==3}
        assertTrue(model.uiState.value.notebook!!.autoMatchingPages)
        assertEquals(changed,model.uiState.value.pages.last().paperTemplate())
    }
}
