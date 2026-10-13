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
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class Phase4StorageTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val name="phase4-${UUID.randomUUID()}.db"
    private lateinit var db: NoteinDatabase
    private lateinit var repo: EditorRepository
    private lateinit var id: String
    private lateinit var page: PageEntity
    private val store=ViewModelStore()
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private fun main(block: ()->Unit)=instrumentation.runOnMainSync(block)
    @Before fun setup()=runBlocking {
        db=Room.databaseBuilder(context,NoteinDatabase::class.java,name).build()
        repo=EditorRepository(db.editorDao()); id=NotebookRepository(db.notebookDao(),db.folderDao()).createNotebook("Phase4 fixture",null)
        page=repo.firstPage(id)!!
    }
    @After fun cleanup() { main {store.clear()}; db.close(); context.deleteDatabase(name) }
    private fun await(predicate:()->Boolean) {
        val deadline=SystemClock.uptimeMillis()+15000
        while (!predicate()&&SystemClock.uptimeMillis()<deadline) SystemClock.sleep(20)
        assertTrue("Timed out waiting for mixed-content storage",predicate())
    }
    private fun model(): EditorViewModel {
        lateinit var model: EditorViewModel
        main {model=EditorViewModel(id,repo,com.ramst.notein.pdf.PdfRepository(context,db.pdfDao()));store.put("editor",model)}
        await {!model.uiState.value.pageBusy && !model.uiState.value.isLoading}; return model
    }
    private fun objects()=runBlocking {db.editorDao().pageObjects(page.id)}
    private fun seed()=runBlocking {
        val objects=listOf(record(pageId=page.id).toEntity(),phase4Text(page.id),phase4Image(page.id))
        repo.saveContentSnapshot(objects,page); objects
    }
    @Test fun mixedCopyMoveDeleteUndoRestoresContentPositionAndAssetReferences() {
        val originals=seed(); val model=model()
        main {model.selectLasso(rectangle(0f,0f,700f,400f));model.copySelection()}
        await {!model.uiState.value.hasUnsavedChanges && objects().size==6}
        val copyIds=model.uiState.value.selectedIds
        assertEquals(3,copyIds.size); assertTrue(copyIds.intersect(originals.map {it.id}.toSet()).isEmpty())
        val copiedImage=model.uiState.value.objects.first {it.id in copyIds && it.type==IMAGE_OBJECT_TYPE}
        assertEquals(originals.last().toContentObject()!!.assetId,copiedImage.toContentObject()!!.assetId)
        main {model.transformSelection(75f,-40f,1.5f)}
        await {!model.uiState.value.hasUnsavedChanges}
        val moved=model.uiState.value.objects.filter {it.id in copyIds}
        main {model.deleteSelection()}
        await {!model.uiState.value.hasUnsavedChanges && objects().size==3}
        main {model.undo(InkEditorController())}
        await {!model.uiState.value.hasUnsavedChanges && objects().size==6}
        assertEquals(moved.associate {it.id to it.payload},objects().filter {it.id in copyIds}.associate {it.id to it.payload})
        assertEquals(originals.associate {it.id to it.payload},objects().filter {it.id !in copyIds}.associate {it.id to it.payload})
        main {store.clear()}
        val reopened=model()
        assertEquals(objects().associate {it.id to it.payload},reopened.uiState.value.objects.associate {it.id to it.payload})
    }
    @Test fun failedMixedSaveRollsBackAllTypesAndRetryRetainsDesiredStateAndUnknownObjects() {
        val originals=seed()
        val future=EditableObjectEntity(UUID.randomUUID().toString(),page.id,"future","untouched",0,0)
        runBlocking {db.editorDao().insertObject(future)}
        val model=model()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER phase4_fail BEFORE UPDATE ON pages BEGIN SELECT RAISE(ABORT,'injected full disk'); END")
        main {model.selectLasso(rectangle(0f,0f,700f,400f));model.transformSelection(-75f,120f,2f)}
        await {model.uiState.value.canRetry}
        val desired=model.uiState.value.objects.associate {it.id to it.payload}
        assertEquals((originals+future).associate {it.id to it.payload},objects().associate {it.id to it.payload})
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER phase4_fail")
        main {model.retrySave()}
        await {!model.uiState.value.hasUnsavedChanges}
        assertEquals(desired,objects().filter {it.type in EDITABLE_CONTENT_TYPES}.associate {it.id to it.payload})
        assertEquals(future,objects().single {it.id==future.id})
    }
    @Test fun textEditingPreservesTransformAndUndoRestoresTextAndFormatting() {
        val original=transformContent(phase4Text(page.id,text="Original text"),0f,0f,80f,-90f,2.5f)
        runBlocking {repo.saveContentSnapshot(listOf(original),page)}
        val model=model()
        main {model.saveText(original.id,"Changed\nwrapped text",36f,android.graphics.Color.BLUE,true,true)}
        await {!model.uiState.value.hasUnsavedChanges}
        val edited=objects().single().toContentObject()!!
        assertEquals(original.toContentObject()!!.transform,edited.transform)
        assertEquals("Changed\nwrapped text",edited.text); assertTrue(edited.bold); assertTrue(edited.italic)
        assertEquals(36f,edited.fontSize,0f)
        main {model.undo(InkEditorController())}; await {!model.uiState.value.hasUnsavedChanges}
        assertEquals(original.payload,objects().single().payload)
    }
    @Test fun pageDuplicateContainsIndependentMixedContentAndSharedImmutableAsset()=runBlocking {
        val originals=seed(); val duplicate=repo.duplicatePage(page.id)
        val copied=db.editorDao().pageObjects(duplicate)
        assertEquals(3,copied.size); assertTrue(copied.none {it.id in originals.map {o->o.id}})
        assertEquals(originals.sortedWith(compareBy<EditableObjectEntity> {it.createdAt}.thenBy {it.id}).map {it.payload},copied.map {it.payload})
        repo.saveContentSnapshot(emptyList(),db.editorDao().page(duplicate)!!)
        assertEquals(originals,objects())
    }
    @Test fun snapshotsRejectCrossPageObjectsAndRollbackAllChanges()=runBlocking {
        val originals=seed(); val other=repo.createPage(id,NotebookType.STANDARD)
        val foreign=phase4Text(other)
        repo.saveContentSnapshot(listOf(foreign),db.editorDao().page(other)!!)
        try {repo.saveContentSnapshot(listOf(foreign.copy(pageId=page.id)),page);fail("Reject cross-page ID")}
        catch (_:IllegalStateException) {}
        assertEquals(originals,objects()); assertEquals(foreign,db.editorDao().pageObjects(other).single())
    }
    @Test fun databaseEmissionCannotUnlockNavigationDuringImageImport()=runBlocking {
        val originals=seed(); val other=repo.createPage(id,NotebookType.STANDARD)
        val started=kotlinx.coroutines.CompletableDeferred<Unit>()
        val finish=kotlinx.coroutines.CompletableDeferred<ImportedImage>()
        lateinit var model:EditorViewModel
        main {model=EditorViewModel(id,repo,com.ramst.notein.pdf.PdfRepository(context,db.pdfDao()),imageImporter={_,_->started.complete(Unit);finish.await()});store.put("editor",model)}
        await {!model.uiState.value.pageBusy}
        main {model.importImage(context,android.net.Uri.parse("content://fixture/delayed"))}
        started.await()
        val incoming=phase4Text(page.id,x=800.0)
        repo.saveContentSnapshot(originals+incoming,page)
        await {model.uiState.value.objects.size==4}
        assertTrue(model.uiState.value.pageBusy)
        main {model.selectPage(other)}
        assertEquals(page.id,model.uiState.value.page!!.id)
        finish.complete(ImportedImage(UUID.randomUUID().toString(),100,80))
        await {!model.uiState.value.pageBusy && !model.uiState.value.hasUnsavedChanges && objects().size==5}
        assertTrue(db.editorDao().pageObjects(other).isEmpty())
    }
}
