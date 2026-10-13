package com.ramst.notein.editor

import android.graphics.Color
import android.net.Uri
import com.ramst.notein.storage.*
import com.ramst.notein.pdf.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch

@RunWith(AndroidJUnit4::class)
class Phase10PdfTest {
    private lateinit var f: Phase10Fixture
    @Before fun setup() { f = Phase10Fixture() }
    @After fun cleanup() { f.close() }
    private fun exporter() = PdfExporter(f.context, f.editor, f.pdf,
        { File(it.path!!).outputStream() }, { File(it.path!!).delete(); Unit })
    private fun pixel(path: String, index: Int = 0): Int {
        val b = renderPdfBitmap(path, index, 200, 300)
        return try { b.getPixel(40, 40) } finally { b.recycle() }
    }
    @Test fun subsetInsertionPreservesIdentityPaperContentSelectionAndSourceOrder() = runBlocking {
        val id = f.notebook(); val first = f.editor.firstPage(id)!!
        f.editor.saveContentSnapshot(listOf(phase4Text(first.id)), first)
        val before = f.editor.pages(id); val original = f.editor.pageObjects(first.id)
        val prepared = f.prepare()
        f.pdf.insert(prepared, id, first, setOf(2, 0), false, 0f, 0f)
        val pages = f.editor.pages(id)
        assertEquals(listOf(first.id, before.last().id), listOf(pages.first().id, pages.last().id))
        assertEquals(listOf(0, 2), pages.filter { it.pdfPageIndex != null }.map { it.pdfPageIndex })
        assertEquals(listOf(200f to 300f, 240f to 320f), pages.filter { it.pdfPageIndex != null }.map { it.pageWidth to it.pageHeight })
        assertEquals(original, f.editor.pageObjects(first.id)); assertEquals(first.paperTemplate(), pages.first().paperTemplate())
        assertEquals(NotebookType.STANDARD.storageValue, f.editor.notebook(id)!!.notebookType)
        assertTrue(runCatching { f.pdf.insert(prepared, id, first, setOf(0), false, 0f, 0f) }.isFailure)
        assertEquals(pages, f.editor.pages(id))
        val lastPdf = pages[2]
        f.editor.saveContentSnapshot(listOf(phase4Text(lastPdf.id)), lastPdf)
        assertEquals(pages.size, f.editor.pages(id).size)
        f.editor.saveContentSnapshot(listOf(phase4Text(pages.last().id)), pages.last())
        assertEquals(pages.size + 1, f.editor.pages(id).size)
    }
    @Test fun multipleSourcesSurviveReorderDuplicateDeleteReopenAndExport() = runBlocking {
        val source = f.source(Color.BLUE, 1)
        val id = f.pdf.import(Uri.fromFile(source)).notebookId
        val original = f.editor.firstPage(id)!!
        val red = f.prepare(Color.RED, 1)
        f.pdf.insert(red, id, original, setOf(0), false, 0f, 0f)
        val redPage = f.editor.pages(id).last()
        val duplicate = f.editor.duplicatePage(redPage.id)
        f.editor.deletePage(redPage.id, id)
        f.editor.reorderPages(id, listOf(duplicate, original.id))
        source.delete()
        val reopened = PdfRepository(f.context, f.db.pdfDao())
        assertEquals(2, reopened.documents(id).size)
        val pages = f.editor.pages(id)
        assertEquals(red.sourceId, pages.first().pdfSourceId)
        val output = File(f.root, "multiple.pdf")
        exporter().exportNotebook(id, Uri.fromFile(output)) {}
        assertTrue(Color.red(pixel(output.path, 0)) > 200)
        assertTrue(Color.blue(pixel(output.path, 1)) > 200)
        f.editor.deletePage(duplicate, id); f.pdf.cleanupUnusedSources(id)
        assertFalse(File(red.localPath).exists())
        assertTrue(File(reopened.documents(id).single().localPath).exists())
    }
    @Test fun canvasPdfObjectsKeepViewportAndSupportTransformsCopiesDeletionAndExport() = runBlocking {
        val id = f.notebook(NotebookType.INFINITE)
        val page = f.editor.firstPage(id)!!.copy(viewportX = -140f, viewportY = 80f, viewportScale = 2f)
        f.editor.updateEditorState(page); f.editor.setViewportLocked(id, true)
        val blue = f.prepare(Color.BLUE, 1); val red = f.prepare(Color.RED, 1)
        f.pdf.insert(blue, id, page, setOf(0), true, 120f, 40f)
        f.pdf.insert(red, id, page, setOf(0), true, 340f, 40f)
        assertEquals(page, f.editor.firstPage(id)!!.copy(updatedAt = page.updatedAt)); assertTrue(f.editor.notebook(id)!!.viewportLocked)
        val objects = f.editor.pageObjects(page.id)
        assertEquals(2, objects.size)
        val moved = transformContent(objects.first(), 0f, 0f, 10f, 10f, 1f)
        val copy = moved.copy(id = java.util.UUID.randomUUID().toString())
        f.editor.saveContentSnapshot(listOf(copy, objects.last()), page)
        val output = File(f.root, "canvas.pdf")
        exporter().exportCanvasRegion(id, page.id, CanvasExportRegion(0f, 0f, 500f, 400f), Uri.fromFile(output)) {}
        val b = renderPdfBitmap(output.path, 0, 595, 842)
        try { assertTrue(Color.blue(b.getPixel(60, 80)) > 200); assertTrue(Color.red(b.getPixel(280, 80)) > 200) }
        finally { b.recycle() }
        f.editor.saveContentSnapshot(listOf(objects.last()), page); f.pdf.cleanupUnusedSources(id)
        assertFalse(File(blue.localPath).exists()); assertTrue(File(red.localPath).exists())
        assertEquals(1, f.editor.pages(id).size)
    }
    @Test fun transactionFailureAndDiscardLeaveExistingNotebookUntouched() = runBlocking {
        val id = f.notebook(); val page = f.editor.firstPage(id)!!; val prepared = f.prepare()
        f.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_insert BEFORE INSERT ON pages BEGIN SELECT RAISE(ABORT,'injected SQLITE_FULL'); END")
        assertTrue(runCatching { f.pdf.insert(prepared, id, page, setOf(0, 1), false, 0f, 0f) }.isFailure)
        assertEquals(listOf(page), f.editor.pages(id)); assertTrue(f.pdf.documents(id).isEmpty())
        f.pdf.discard(prepared); assertFalse(File(prepared.localPath).exists())
        assertFalse(File(f.context.filesDir, "pdf-documents").listFiles()!!.any())
    }
    @Test fun invalidEncryptedAndCopyFailureCleanFilesWithoutChangingNotes() = runBlocking {
        val id = f.notebook(); val page = f.editor.firstPage(id)!!
        for (asset in listOf("malformed.pdf", "protected.pdf")) {
            val file = File(f.root, asset)
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets.open("phase6/$asset").use { input -> file.outputStream().use { input.copyTo(it) } }
            assertTrue(runCatching { f.pdf.prepare(Uri.fromFile(file)) {} }.isFailure)
        }
        val broken = PdfRepository(f.context, f.db.pdfDao(), { throw java.io.IOException("injected storage failure") })
        assertTrue(runCatching { broken.prepare(Uri.EMPTY) {} }.isFailure)
        assertEquals(listOf(page), f.editor.pages(id))
        assertTrue(File(f.context.filesDir, "pdf-documents").listFiles()!!.isEmpty())
    }
    @Test fun copyingCancellationClosesStreamAndRemovesUncommittedFiles() = runBlocking {
        val started = CompletableDeferred<Unit>(); val proceed = CountDownLatch(1); var closed = false
        val repo = PdfRepository(f.context, f.db.pdfDao(), { object : InputStream() {
            override fun read() = 1
            override fun read(b: ByteArray, off: Int, len: Int): Int { started.complete(Unit); proceed.await(); b[off] = 1; return 1 }
            override fun close() { closed = true }
        } })
        val job = launch(Dispatchers.IO) { repo.prepare(Uri.EMPTY) {} }
        started.await(); job.cancel(); proceed.countDown(); job.join()
        assertTrue(closed); assertTrue(File(f.context.filesDir, "pdf-documents").listFiles()!!.isEmpty())
    }
    @Test fun abandonedCopiesRecoveredAndCommittedSourcesKeptAfterInterruption() = runBlocking {
        val id = f.notebook(); val page = f.editor.firstPage(id)!!; val committed = f.prepare()
        f.pdf.insert(committed, id, page, setOf(0), false, 0f, 0f)
        val abandoned = f.prepare(); val staged = File(File(abandoned.localPath).parentFile, "${java.util.UUID.randomUUID()}.importing").apply { writeText("partial") }
        val repo = PdfRepository(f.context, f.db.pdfDao())
        val fresh = repo.prepare(Uri.fromFile(f.source())) {}
        assertFalse(File(abandoned.localPath).exists()); assertFalse(staged.exists()); assertTrue(File(committed.localPath).exists())
        repo.discard(fresh); repo.discard(committed); assertTrue(File(committed.localPath).exists())
    }
    @Test fun hundredPagesHaveNoImportQuotaAndRasterAllocationIsBounded() = runBlocking {
        val id = f.notebook(); val page = f.editor.firstPage(id)!!; val prepared = f.prepare(count = 100)
        f.pdf.insert(prepared, id, page, prepared.dimensions.indices.toSet(), false, 0f, 0f)
        assertEquals(101, f.editor.pages(id).size)
        val b = renderPdfBitmap(prepared.localPath, 99, 20000, 30000)
        try { assertTrue(b.width.toLong() * b.height <= 4_000_000); assertTrue(Color.blue(b.getPixel(10, 10)) > 200) }
        finally { b.recycle() }
    }
}
