package com.ramst.notein.editor

import android.graphics.PointF
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ramst.notein.storage.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Phase10EditorTest {
    private lateinit var f: Phase10Fixture
    private lateinit var model: EditorViewModel
    private lateinit var id: String
    private val store = ViewModelStore()
    private fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private fun await(check: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 15000
        while (!check() && SystemClock.uptimeMillis() < until) SystemClock.sleep(20)
        assertTrue("Phase 10 editor timed out: ${model.uiState.value}", check())
    }
    private fun saved() = await { !model.uiState.value.hasUnsavedChanges && !model.uiState.value.pageBusy }
    private fun open(notebook: String = id) {
        main { store.clear(); model = EditorViewModel(notebook, f.editor, f.pdf); store.put("editor", model) }
        await { !model.uiState.value.isLoading && !model.uiState.value.pageBusy }
    }
    @Before fun setup() = runBlocking { f = Phase10Fixture(); id = f.notebook(); open() }
    @After fun cleanup() { main { store.clear() }; f.close() }
    @Test fun fractionalIndependentWidthsSurvivePagesNotebooksAndRecreationWithoutRestylingInk() = runBlocking {
        val tools = listOf(InkTool.Pencil, InkTool.BallPen, InkTool.Pen, InkTool.Highlighter)
        val widths = listOf(.375f, 1.25f, 8.5f, 13.25f)
        val page = model.uiState.value.page!!
        val old = record(pageId = page.id).toEntity()
        main { model.onStrokeCommitted(old.toInkStrokeRecord()!!) }; saved()
        tools.zip(widths).forEach { (tool, width) ->
            main { model.selectTool(tool); model.selectThickness(width) }; saved()
            await { runBlocking { f.editor.toolPreferences().any { it.tool == tool.storageValue && it.thickness == width } } }
        }
        assertEquals(old, f.editor.pageObjects(page.id).single())
        main { model.nextPage() }; await { model.uiState.value.page!!.id != page.id && !model.uiState.value.pageBusy }
        tools.zip(widths).forEach { (tool, width) -> assertEquals(width, model.toolThickness(tool), 0f) }
        val other = f.notebook(); open(other)
        tools.zip(widths).forEach { (tool, width) -> assertEquals(width, model.toolThickness(tool), 0f) }
        open(id)
        tools.zip(widths).forEach { (tool, width) -> assertEquals(width, model.toolThickness(tool), 0f) }
        assertEquals(old, f.editor.pageObjects(page.id).single())
    }
    @Test fun lockedZoomAllowsPanAndPersistsAcrossPagesRecreationAndResizeWhileEditingStillWorks() = runBlocking {
        main { model.resizeViewport(1000f, 700f); model.updateViewport(-120f, 80f, 1.75f) }; saved()
        main { model.setViewportLocked(true) }; await { model.uiState.value.notebook!!.viewportLocked }
        main { model.updateViewport(999f, 888f, 4f); model.saveText(null, "Writing while locked", 24f, android.graphics.Color.BLACK, false, false) }; saved()
        assertEquals(132.5f, model.uiState.value.page!!.viewportX, 0f)
        assertEquals(0f, model.uiState.value.page!!.viewportY, 0f)
        assertEquals(1.75f, model.uiState.value.page!!.viewportScale, 0f)
        val current = model.uiState.value.page!!.id
        await { model.uiState.value.pages.size == 2 }
        main { model.nextPage() }; await { model.uiState.value.page!!.id != current && !model.uiState.value.pageBusy }
        assertTrue(model.uiState.value.notebook!!.viewportLocked)
        main { model.previousPage() }; await { model.uiState.value.page!!.id == current && !model.uiState.value.pageBusy }
        open()
        assertTrue(model.uiState.value.notebook!!.viewportLocked)
        main { model.resizeViewport(700f, 1000f) }; saved()
        assertEquals(-17.5f, model.uiState.value.page!!.viewportX, 0f)
        assertEquals(0f, model.uiState.value.page!!.viewportY, 0f)
        assertEquals(1.75f, model.uiState.value.page!!.viewportScale, 0f)
        main { model.undo(InkEditorController()) }; saved()
        main { model.redo(InkEditorController()) }; saved()
        main { model.setViewportLocked(false) }; await { !model.uiState.value.notebook!!.viewportLocked }
        main { model.updateViewport(30f, 40f, 2f) }; saved()
        assertEquals(-70f, model.uiState.value.page!!.viewportX, 0f)
    }
    @Test fun newPaperFitsCenteredAndZoomedVerticalScrollIsBoundedAndDurable() = runBlocking {
        main { model.resizeViewport(1000f, 700f) }; saved()
        val first = model.uiState.value.page!!
        assertEquals(700f / 595f, first.viewportScale, .0001f)
        assertEquals((1000f - 420f * first.viewportScale) / 2f, first.viewportX, .001f)
        assertEquals(0f, first.viewportY, .001f)
        main { model.updateViewport(-9000f, -120f, 2f) }; saved()
        val zoomed = model.uiState.value.page!!
        assertEquals(80f, zoomed.viewportX, 0f)
        assertEquals(-120f, zoomed.viewportY, 0f)
        main { model.setViewportLocked(true) }; await { model.uiState.value.notebook!!.viewportLocked }
        main { model.updateViewport(9000f, -9000f, 5f) }; saved()
        assertEquals(80f, model.uiState.value.page!!.viewportX, 0f)
        assertEquals(-490f, model.uiState.value.page!!.viewportY, 0f)
        assertEquals(2f, model.uiState.value.page!!.viewportScale, 0f)
        open()
        assertEquals(-490f, model.uiState.value.page!!.viewportY, 0f)
        main { model.createPage() }; await { model.uiState.value.page!!.id != first.id && !model.uiState.value.pageBusy }
        main { model.resizeViewport(1000f, 700f) }; saved()
        val next = model.uiState.value.page!!
        assertTrue(model.uiState.value.notebook!!.viewportLocked)
        assertEquals(2f, next.viewportScale, .0001f)
        assertEquals(80f, next.viewportX, .001f)
        assertEquals(0f, next.viewportY, .001f)
    }
    @Test fun insertedPdfPageFitsItsOwnDimensionsAndStaysCenteredWhileZooming() = runBlocking {
        main { model.resizeViewport(1000f, 700f) }; saved()
        val firstId = model.uiState.value.page!!.id
        main { model.preparePdf(Uri.fromFile(f.source()), PointF()) }; await { model.uiState.value.preparedPdf != null }
        main { model.confirmPdfImport(setOf(0)) }; await { model.uiState.value.preparedPdf == null && !model.uiState.value.pageBusy && model.uiState.value.pages.size == 2 }
        main { model.nextPage() }; await { model.uiState.value.page!!.id != firstId && !model.uiState.value.pageBusy }
        main { model.resizeViewport(1000f, 700f) }; saved()
        val pdfPage = model.uiState.value.page!!
        assertNotNull(pdfPage.pdfPageIndex)
        assertEquals(700f / 300f, pdfPage.viewportScale, .0001f)
        assertEquals((1000f - 200f * pdfPage.viewportScale) / 2f, pdfPage.viewportX, .001f)
        main { model.updateViewport(9000f, -80f, 3f) }; saved()
        assertEquals(200f, model.uiState.value.page!!.viewportX, .001f)
        assertEquals(-80f, model.uiState.value.page!!.viewportY, .001f)
    }
    @Test fun notebookZoomLockCoversExistingNewAutomaticAndImportedPagesAndSurvivesReopen() = runBlocking {
        val first = model.uiState.value.page!!.id
        main { model.resizeViewport(1000f, 700f); model.updateViewport(0f, -80f, 2.25f) }; saved()
        main { model.createPage() }; await { model.uiState.value.page!!.id != first && !model.uiState.value.pageBusy }
        main { model.resizeViewport(1000f, 700f); model.updateViewport(0f, 0f, .75f) }; saved()
        main { model.selectPage(first) }; await { model.uiState.value.page!!.id == first && !model.uiState.value.pageBusy }
        main { model.setViewportLocked(true) }; await { model.uiState.value.notebook!!.viewportLocked && !model.uiState.value.pageBusy }
        assertEquals(2.25f, f.editor.notebook(id)!!.lockedZoomScale!!, 0f)
        assertTrue(f.editor.pages(id).all { it.viewportScale == 2.25f })
        main { model.nextPage() }; await { model.uiState.value.page!!.id != first && !model.uiState.value.pageBusy }
        assertEquals(2.25f, model.uiState.value.page!!.viewportScale, 0f)
        main { model.saveText(null, "Automatic page under notebook lock", 24f, android.graphics.Color.BLACK, false, false) }; saved()
        await { model.uiState.value.pages.size == 3 }
        assertTrue(f.editor.pages(id).all { it.viewportScale == 2.25f })
        main { model.createPage() }; await { model.uiState.value.pages.size == 4 && !model.uiState.value.pageBusy }
        main { model.resizeViewport(1000f, 700f) }; saved()
        assertEquals(2.25f, model.uiState.value.page!!.viewportScale, 0f)
        main { model.preparePdf(Uri.fromFile(f.source()), PointF()) }; await { model.uiState.value.preparedPdf != null }
        main { model.confirmPdfImport(setOf(0, 2)) }; await { model.uiState.value.pages.size == 6 && model.uiState.value.preparedPdf == null && !model.uiState.value.pageBusy }
        assertTrue(f.editor.pages(id).all { it.viewportScale == 2.25f })
        main { model.setPageLayout(PageLayout.VERTICAL) }; await { model.uiState.value.notebook!!.pageLayout == "vertical" }
        open()
        assertEquals("vertical", model.uiState.value.notebook!!.pageLayout)
        assertEquals(2.25f, model.uiState.value.page!!.viewportScale, 0f)
        main { model.nextPage() }; await { model.uiState.value.page!!.pdfPageIndex != null && !model.uiState.value.pageBusy }
        assertEquals(2.25f, model.uiState.value.page!!.viewportScale, 0f)
        main { model.setViewportLocked(false) }; await { !model.uiState.value.notebook!!.viewportLocked && !model.uiState.value.pageBusy }
        main { model.updateViewport(0f, 0f, 3f) }; saved()
        assertEquals(3f, model.uiState.value.page!!.viewportScale, 0f)
        val other = f.notebook(); open(other)
        assertFalse(model.uiState.value.notebook!!.viewportLocked)
        assertEquals("horizontal", model.uiState.value.notebook!!.pageLayout)
    }
    @Test fun pageGestureCommitsOutgoingEditsAndEntersAtTheMatchingEdgeWithoutChangingOtherContent() = runBlocking {
        main { model.resizeViewport(1000f, 700f); model.updateViewport(0f, -120f, 2f) }; saved()
        val first = model.uiState.value.page!!.id
        val ink = record(pageId = first).toEntity()
        main { model.onStrokeCommitted(ink.toInkStrokeRecord()!!) }; saved()
        await { model.uiState.value.pages.size == 2 }
        val nextId = model.uiState.value.pages.last().id
        val text = phase4Text(nextId)
        f.editor.saveContentSnapshot(listOf(text), f.editor.pages(id).last())
        main { model.setViewportLocked(true) }; await { model.uiState.value.notebook!!.viewportLocked && !model.uiState.value.pageBusy }
        main { model.updateViewport(0f, -200f, 2f); model.navigateByGesture(1) }
        await { model.uiState.value.page!!.id == nextId && !model.uiState.value.pageBusy }
        assertEquals(-200f, f.editor.pages(id).first().viewportY, 0f)
        assertEquals(ink.payload, f.editor.pageObjects(first).single().payload)
        assertEquals(text.payload, f.editor.pageObjects(nextId).single().payload)
        assertEquals(0f, model.uiState.value.page!!.viewportY, 0f)
        assertEquals(2f, model.uiState.value.page!!.viewportScale, 0f)
        main { model.navigateByGesture(-1) }; await { model.uiState.value.page!!.id == first && !model.uiState.value.pageBusy }
        assertEquals(-490f, model.uiState.value.page!!.viewportY, 0f)
        assertEquals(ink.payload, model.uiState.value.objects.single().payload)
    }
    @Test fun repeatedConfirmationInsertsOnceAndCancellingPreviewCleansItsFile() = runBlocking {
        val source = f.source(); val original = model.uiState.value.page!!.id
        main { model.preparePdf(Uri.fromFile(source), PointF()) }; await { model.uiState.value.preparedPdf != null }
        main { repeat(5) { model.confirmPdfImport(setOf(0, 2)) } }; await { model.uiState.value.preparedPdf == null && !model.uiState.value.pageBusy }
        await { model.uiState.value.pages.size == 3 }
        assertEquals(original, model.uiState.value.page!!.id); assertEquals(1, f.pdf.documents(id).size)
        main { model.preparePdf(Uri.fromFile(source), PointF()) }; await { model.uiState.value.preparedPdf != null }
        val pending = model.uiState.value.preparedPdf!!.localPath
        main { model.cancelPdfImport() }; await { !java.io.File(pending).exists() }
        assertEquals(3, f.editor.pages(id).size)
    }
    @Test fun incomingDatabaseEmissionCannotEnableEditingDuringPdfCopy() = runBlocking {
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val proceed = java.util.concurrent.CountDownLatch(1)
        val slow = com.ramst.notein.pdf.PdfRepository(f.context, f.db.pdfDao(), {
            started.complete(Unit); proceed.await(); f.source().inputStream()
        })
        main { store.clear(); model = EditorViewModel(id, f.editor, slow); store.put("editor", model) }
        await { !model.uiState.value.isLoading && !model.uiState.value.pageBusy }
        try {
            main { model.preparePdf(Uri.EMPTY, PointF()) }; started.await()
            val page = model.uiState.value.page!!
            f.editor.saveContentSnapshot(listOf(phase4Text(page.id)), page)
            await { model.uiState.value.objects.isNotEmpty() }
            assertTrue("A Room emission must not enable editing/navigation while copying", model.uiState.value.pageBusy)
        } finally { proceed.countDown() }
        await { model.uiState.value.preparedPdf != null }; main { model.cancelPdfImport() }
    }
    @Test fun lockedCanvasImportObjectsSupportCopyTransformDeleteUndoAndReopen() = runBlocking {
        val canvasId = f.notebook(NotebookType.INFINITE); open(canvasId)
        main { model.updateViewport(-160f, 80f, 2f) }; saved()
        main { model.setViewportLocked(true) }; await { model.uiState.value.notebook!!.viewportLocked }
        main { model.preparePdf(Uri.fromFile(f.source()), PointF(200f, 60f)) }; await { model.uiState.value.preparedPdf != null }
        main { model.confirmPdfImport(setOf(0, 2)) }; await { model.uiState.value.preparedPdf == null && !model.uiState.value.pageBusy && model.uiState.value.objects.size == 2 }
        val original = model.uiState.value.objects.first(); val sourceId = original.toContentObject()!!.pdfSourceId!!
        main { model.selectObject(original.id); model.copySelection() }; saved()
        assertEquals(3, model.uiState.value.objects.size)
        main { model.transformSelection(20f, 30f, 1.5f) }; saved()
        val copy = model.uiState.value.objects.single { it.id in model.uiState.value.selectedIds }
        assertEquals(sourceId, copy.toContentObject()!!.pdfSourceId)
        main { model.deleteSelection() }; saved(); assertEquals(2, model.uiState.value.objects.size)
        main { model.undo(InkEditorController()) }; saved(); assertEquals(3, model.uiState.value.objects.size)
        assertTrue(java.io.File(f.pdf.source(sourceId)!!.localPath).isFile)
        assertEquals(-160f, model.uiState.value.page!!.viewportX, 0f)
        assertEquals(80f, model.uiState.value.page!!.viewportY, 0f)
        assertEquals(2f, model.uiState.value.page!!.viewportScale, 0f)
        val image = java.io.File(f.root, "locked-image.png")
        val bitmap = android.graphics.Bitmap.createBitmap(40, 30, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(android.graphics.Color.GREEN)
            image.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
        main { model.importImage(f.context, Uri.fromFile(image)) }; saved()
        await { model.uiState.value.objects.any { it.type == IMAGE_OBJECT_TYPE } }
        main { model.transformSelection(15f, 25f, 1.2f) }; saved()
        val insertedImage = model.uiState.value.objects.single { it.type == IMAGE_OBJECT_TYPE }.toContentObject()!!
        assertEquals(1.2f, insertedImage.transform.scale.toFloat(), .00001f)
        assertTrue(ImageAssets(f.context).preview(insertedImage.assetId!!).isFile)
        main { model.saveText(null, "Locked text", 24f, android.graphics.Color.BLACK, false, false) }; saved()
        assertEquals(-160f, model.uiState.value.page!!.viewportX, 0f)
        open(canvasId); assertEquals(5, model.uiState.value.objects.size); assertTrue(model.uiState.value.notebook!!.viewportLocked)
    }
}
