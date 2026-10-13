package com.ramst.notein.editor

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Debug
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ramst.notein.pdf.*
import com.ramst.notein.storage.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.*
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class Phase6PdfTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val name = "phase6-${UUID.randomUUID()}.db"
    private lateinit var db: NoteinDatabase
    private lateinit var editor: EditorRepository
    private lateinit var pdf: PdfRepository
    private val sources = mutableListOf<File>()
    private val imported = mutableListOf<String>()
    private val evidence get() = File(context.filesDir, "phase6-evidence").apply { mkdirs() }
    @Before fun setup() {
        db = Room.databaseBuilder(context, NoteinDatabase::class.java, name).build()
        editor = EditorRepository(db.editorDao()); pdf = PdfRepository(context, db.pdfDao())
    }
    @After fun cleanup() {
        runBlocking { imported.forEach { pdf.document(it)?.let { doc -> File(doc.localPath).delete() } } }
        sources.forEach { it.delete() }; db.close(); context.deleteDatabase(name)
    }
    private fun fixture(asset: String): File = File(context.cacheDir, "phase6-${UUID.randomUUID()}.pdf").also { file ->
        instrumentation.context.assets.open("phase6/$asset").use { input -> file.outputStream().use { input.copyTo(it) } }
        sources += file
    }
    private suspend fun import(asset: String = "mixed.pdf"): String = pdf.import(Uri.fromFile(fixture(asset)), "Phase6 fixture").notebookId.also { imported += it }
    private fun exporter(open: (Uri) -> OutputStream? = { FileOutputStream(File(it.path!!)) },
        delete: (Uri) -> Unit = { File(it.path!!).delete(); Unit }) = PdfExporter(context, editor, pdf, open, delete)
    private fun destination(label: String) = File(evidence, "$label.pdf").also { it.delete() }
    private fun render(file: File, index: Int, block: (PdfRenderer.Page, Bitmap) -> Unit) {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { renderer ->
            renderer.openPage(index).use { page ->
                val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                try { page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); block(page, bitmap) }
                finally { bitmap.recycle() }
            }
        } }
    }
    private fun assertBlue(color: Int) { assertTrue("Expected blue, got ${Integer.toHexString(color)}", Color.blue(color) > Color.red(color) + 70) }
    private fun image(page: PageEntity, x: Float, y: Float, width: Float, height: Float, color: Int): EditableObjectEntity {
        val id = UUID.randomUUID().toString(); val asset = UUID.randomUUID().toString()
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888); bitmap.eraseColor(color)
        val file = ImageAssets(context).preview(asset); file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle(); sources += file
        val payload = ObjectTransform(x.toDouble(), y.toDouble()).write(JSONObject().put("width", width).put("height", height).put("assetId", asset)).toString()
        return EditableObjectEntity(id, page.id, IMAGE_OBJECT_TYPE, payload, 2, 2)
    }
    @Test fun portraitLandscapeScannedHeavyMixedSizesRemainOffline() = runBlocking {
        val source = fixture("mixed.pdf"); val id = pdf.import(Uri.fromFile(source)).notebookId; imported += id
        source.delete()
        val pages = editor.pages(id)
        assertEquals(listOf(595f to 842f, 842f to 595f, 612f to 792f, 720f to 540f, 400f to 600f), pages.map { it.pageWidth to it.pageHeight })
        val local = File(pdf.document(id)!!.localPath); assertTrue(local.isFile)
        pages.forEachIndexed { index, page ->
            assertEquals(index, page.pdfPageIndex)
            render(local, index) { _, bitmap -> assertBlue(bitmap.getPixel(60, 50)) }
        }
    }
    @Test fun malformedProtectedMissingSourcesCreateNoPartialNotebook() = runBlocking {
        val before = File(context.filesDir, "pdf-documents").list()?.toSet().orEmpty()
        for (asset in listOf("malformed.pdf", "protected.pdf")) {
            val failure = runCatching { pdf.import(Uri.fromFile(fixture(asset))) }.exceptionOrNull()
            assertNotNull(failure); assertTrue(failure!!.message!!.contains(if (asset == "protected.pdf") "password" else "corrupt"))
        }
        assertTrue(runCatching { pdf.import(Uri.fromFile(File(context.cacheDir, "phase6-nonexistent.pdf"))) }.isFailure)
        assertEquals(emptyList<PageEntity>(), db.editorDao().pages("anything"))
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM notebooks").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        assertEquals(before, File(context.filesDir, "pdf-documents").list()?.toSet().orEmpty())
    }
    @Test fun interruptedImportClosesStreamsAndCleansStagedFiles() = runBlocking {
        val before = File(context.filesDir, "pdf-documents").list()?.toSet().orEmpty()
        val started = CompletableDeferred<Unit>(); val proceed = java.util.concurrent.CountDownLatch(1)
        var closed = false
        val repo = PdfRepository(context, db.pdfDao(), openSource = { object : InputStream() {
            override fun read(): Int = 1
            override fun read(b: ByteArray, off: Int, len: Int): Int { started.complete(Unit); proceed.await(); b[off] = 1; return 1 }
            override fun close() { closed = true }
        } })
        val job = launch(Dispatchers.IO) { repo.import(Uri.EMPTY) }
        started.await(); job.cancel(); proceed.countDown(); job.join()
        assertTrue(closed); assertEquals(before, File(context.filesDir, "pdf-documents").list()?.toSet().orEmpty())
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM notebooks").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
    }
    @Test fun failedImportTransactionKeepsExistingNotebookAndRemovesOnlyItsSource() = runBlocking {
        val existing = import(); val before = File(context.filesDir, "pdf-documents").list()!!.toSet()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER phase6_fail BEFORE INSERT ON pages BEGIN SELECT RAISE(ABORT,'injected SQLITE_FULL'); END")
        assertTrue(runCatching { pdf.import(Uri.fromFile(fixture("mixed.pdf"))) }.isFailure)
        assertEquals(5, editor.pages(existing).size); assertEquals(before, File(context.filesDir, "pdf-documents").list()!!.toSet())
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM notebooks").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
    }
    @Test fun cancellationAfterRoomCommitCannotDeleteTheCommittedLocalPdf() = runBlocking {
        var committedId: String? = null
        val delegated = object : PdfDao by db.pdfDao() {
            override suspend fun insertPdfNotebook(notebook: NotebookEntity, document: PdfDocumentEntity, pages: List<PageEntity>) {
                db.pdfDao().insertPdfNotebook(notebook, document, pages)
                committedId = notebook.id
                currentCoroutineContext().cancel()
                currentCoroutineContext().ensureActive()
            }
        }
        val repo = PdfRepository(context, delegated)
        val job = launch { repo.import(Uri.fromFile(fixture("mixed.pdf"))) }
        job.join(); assertTrue(job.isCancelled)
        val id = committedId!!; imported += id
        assertEquals(5, editor.pages(id).size)
        assertTrue("A committed notebook must retain its offline background", File(pdf.document(id)!!.localPath).isFile)
    }
    @Test fun selectedPagesPreserveOrderDimensionsAndEditableAnnotations() = runBlocking {
        val id = import(); val pages = editor.pages(id)
        val text = phase4Text(pages[1].id)
        val ink = record(130f, 170f, 260f, 170f, pageId = pages[1].id).toEntity()
        val image = image(pages[1], 320f, 150f, 80f, 60f, Color.GREEN)
        editor.saveContentSnapshot(listOf(ink, text, image), pages[1])
        val before = editor.pageObjects(pages[1].id)
        val file = destination("selected")
        exporter().exportPages(id, listOf(pages[1].id, pages[4].id, pages[0].id), Uri.fromFile(file)) {}
        val expected = listOf(842 to 595, 400 to 600, 595 to 842)
        expected.forEachIndexed { index, size -> render(file, index) { page, bitmap ->
            assertEquals(size.first, page.width); assertEquals(size.second, page.height); assertBlue(bitmap.getPixel(60, 50))
            if (index == 0) {
                assertTrue(Color.green(bitmap.getPixel(350, 180)) > 200)
                assertTrue("Ink must use the same document coordinates as other annotations", Color.red(bitmap.getPixel(190, 170)) < 150)
            }
        } }
        assertEquals(before, editor.pageObjects(pages[1].id))
    }
    @Test fun canvasCrossingBoundariesUsesRowOrderAndClipsChosenRegion() = runBlocking {
        val id = NotebookRepository(db.notebookDao(), db.folderDao()).createNotebook("Canvas fixture", null, NotebookType.INFINITE)
        val page = editor.firstPage(id)!!
        val crossing = image(page, 650f, 60f, 100f, 80f, Color.BLUE)
        val outside = image(page, 850f, 60f, 100f, 80f, Color.RED)
        editor.saveContentSnapshot(listOf(crossing, outside), page)
        val file = destination("canvas")
        exporter().exportCanvasRegion(id, page.id, CanvasExportRegion(100f, 20f, 720f, 200f), Uri.fromFile(file)) {}
        render(file, 0) { p, b -> assertEquals(595, p.width); assertEquals(842, p.height); assertBlue(b.getPixel(570, 60)) }
        render(file, 1) { _, b -> assertBlue(b.getPixel(20, 60)); assertEquals(Color.WHITE, b.getPixel(180, 60)) }
    }
    @Test fun snapshotDoesNotChangeWhenNotebookIsEditedDuringExport() = runBlocking {
        val id = NotebookRepository(db.notebookDao(), db.folderDao()).createNotebook("Snapshot fixture", null)
        val first = editor.firstPage(id)!!; val secondId = editor.createPage(id, NotebookType.STANDARD)
        val second = editor.pages(id).first { it.id == secondId }
        editor.saveContentSnapshot(listOf(image(second, 50f, 50f, 100f, 100f, Color.BLUE)), second)
        val file = destination("snapshot")
        exporter().exportPages(id, listOf(first.id, second.id), Uri.fromFile(file)) { p ->
            if (p.completed == 1) runBlocking { editor.saveContentSnapshot(listOf(image(second, 50f, 50f, 100f, 100f, Color.RED)), second) }
        }
        render(file, 1) { _, bitmap -> assertBlue(bitmap.getPixel(80, 80)) }
        assertEquals(Color.RED, editor.pageObjects(secondId).single().toContentObject()!!.let { c -> BitmapFactory.decodeFile(ImageAssets(context).preview(c.assetId!!).path).let { b -> b.getPixel(0, 0).also { b.recycle() } } })
    }
    @Test fun canvasVerticalBoundariesAndRowOrderKeepAnnotationsAligned() = runBlocking {
        val id = NotebookRepository(db.notebookDao(), db.folderDao()).createNotebook("Canvas row fixture", null, NotebookType.INFINITE)
        val page = editor.firstPage(id)!!
        val crossing = image(page, 650f, 800f, 100f, 100f, Color.BLUE)
        editor.saveContentSnapshot(listOf(crossing), page)
        val file = destination("canvas-four")
        exporter().exportCanvasRegion(id, page.id, CanvasExportRegion(100f, 20f, 800f, 1000f), Uri.fromFile(file)) {}
        val points = listOf(570 to 800, 20 to 800, 570 to 20, 20 to 20)
        points.forEachIndexed { index, point -> render(file, index) { _, bitmap -> assertBlue(bitmap.getPixel(point.first, point.second)) } }
    }
    @Test fun failedSnapshotRemovesCreatedDestinationAndMemoryFailureIsRecoverable() = runBlocking {
        val id = import(); val page = editor.firstPage(id)!!
        val file = destination("failed-snapshot").apply { writeText("placeholder") }
        assertTrue(runCatching { exporter().exportSelectedPage(id, "missing-page", Uri.fromFile(file)) {} }.isFailure)
        assertFalse(file.exists())
        val failing = exporter(open = { throw OutOfMemoryError("injected output allocation failure") })
        val failure = runCatching { failing.exportSelectedPage(id, page.id, Uri.fromFile(file)) {} }.exceptionOrNull()
        assertTrue(failure is IOException); assertFalse(file.exists()); assertEquals(5, editor.pages(id).size)
    }
    @Test fun cancellationDuringRenderingRemovesOutputAndTemporaryWithoutChangingNotes() = runBlocking {
        val id = import(); val pages = editor.pages(id); val before = pages
        val file = destination("cancelled-render")
        val job = launch { exporter().exportPages(id, pages.map { it.id }, Uri.fromFile(file)) { if (it.completed == 1) cancel() } }
        job.join(); assertTrue(job.isCancelled); assertFalse(file.exists()); assertEquals(before, editor.pages(id))
        assertFalse(context.cacheDir.list()!!.any { it.startsWith("export-") })
    }
    @Test fun cancellationDuringDestinationCopyClosesAndDeletesPartialOutput() = runBlocking {
        val id = import(); val page = editor.firstPage(id)!!; val file = destination("cancelled-copy")
        var closed = false; var exportJob: Job? = null
        val exporter = exporter(open = { object : FileOutputStream(file) {
            override fun write(bytes: ByteArray, offset: Int, count: Int) { super.write(bytes, offset, count); exportJob!!.cancel() }
            override fun close() { closed = true; super.close() }
        } })
        exportJob = launch(start = CoroutineStart.LAZY) { exporter.exportSelectedPage(id, page.id, Uri.fromFile(file)) {} }
        exportJob!!.start(); exportJob!!.join()
        assertTrue(exportJob!!.isCancelled); assertTrue(closed); assertFalse(file.exists())
    }
    @Test fun lowStorageDuringDestinationCopyLeavesNoCompletedOutputOrNotebookChanges() = runBlocking {
        val id = import(); val page = editor.firstPage(id)!!; val file = destination("disk-full")
        val before = editor.pages(id); var closed = false
        val fail = exporter(open = { object : FileOutputStream(file) {
            override fun write(bytes: ByteArray, offset: Int, count: Int) { super.write(bytes, offset, minOf(100, count)); throw IOException("ENOSPC: injected full disk") }
            override fun close() { closed = true; super.close() }
        } })
        assertTrue(runCatching { fail.exportSelectedPage(id, page.id, Uri.fromFile(file)) {} }.isFailure)
        assertTrue(closed); assertFalse(file.exists()); assertEquals(before, editor.pages(id))
        assertFalse(context.cacheDir.list()!!.any { it.startsWith("export-") })
    }
    @Test fun hugeCanvasRegionFailsSafelyWithoutOverflowOrQuota() {
        assertTrue(runCatching { CanvasExportRegion(0f, 0f, Float.MAX_VALUE, Float.MAX_VALUE).pagination() }.isFailure)
        assertEquals(10 to 10, CanvasExportRegion(-100f, -200f, 5950f, 8420f).pagination())
    }
    @Test fun blankPdfNotebookCannotBeCreatedWithoutValidatedImport() = runBlocking {
        assertTrue(runCatching { NotebookRepository(db.notebookDao(), db.folderDao()).createNotebook("Invalid PDF", null, NotebookType.PDF) }.isFailure)
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM notebooks").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
    }
    @Test fun missingLocalSourceOrImageFailsWithoutSilentlyExportingBlankContent() = runBlocking {
        val id = import(); val page = editor.firstPage(id)!!
        val source = File(pdf.document(id)!!.localPath); source.delete()
        val file = destination("missing-source")
        assertTrue(runCatching { exporter().exportSelectedPage(id, page.id, Uri.fromFile(file)) {} }.isFailure)
        assertFalse(file.exists()); assertEquals(5, editor.pages(id).size)
        val standard = NotebookRepository(db.notebookDao(), db.folderDao()).createNotebook("Missing image", null)
        val blank = editor.firstPage(standard)!!
        editor.saveContentSnapshot(listOf(phase4Image(blank.id)), blank)
        assertTrue(runCatching { exporter().exportSelectedPage(standard, blank.id, Uri.fromFile(file)) {} }.isFailure)
        assertFalse(file.exists()); assertEquals(1, editor.pageObjects(blank.id).size)
    }
    @Test fun hundredImageHeavyPagesStreamWithBoundedMemoryAndResponsiveMainThread() = runBlocking {
        val id = import("hundred.pdf"); val pages = editor.pages(id); assertEquals(100, pages.size)
        val file = destination("hundred-export")
        val memory = mutableListOf<Pair<Int, Int>>()
        var heartbeats = 0
        var longestGap = 0L
        val heartbeat = launch(Dispatchers.Main) {
            var last = SystemClock.elapsedRealtime()
            while (isActive) {
                val now = SystemClock.elapsedRealtime(); longestGap = maxOf(longestGap, now - last); last = now
                heartbeats++; delay(20)
            }
        }
        val started = SystemClock.elapsedRealtime()
        try {
            exporter().exportPages(id, pages.map { it.id }, Uri.fromFile(file)) { p ->
                if (p.completed % 10 == 0) { val info = Debug.MemoryInfo(); Debug.getMemoryInfo(info); memory += p.completed to info.totalPss }
                if (p.completed > 0) assertTrue(context.cacheDir.listFiles()!!.first { it.name.startsWith("export-") }.length() > 0)
            }
        } finally { heartbeat.cancelAndJoin() }
        val elapsed = SystemClock.elapsedRealtime() - started
        File(evidence, "profile.txt").writeText("100 image-heavy pages; elapsedMs=$elapsed; mainThreadHeartbeats=$heartbeats; longestMainThreadGapMs=$longestGap; pssKiB=$memory; outputBytes=${file.length()}\n")
        assertTrue("Main thread did not keep responding", heartbeats > 20)
        assertTrue("Main thread stalled for $longestGap ms", longestGap < 1000)
        assertTrue("PSS grew with exported pages: $memory", memory.last().second - memory.first().second < 100 * 1024)
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { assertEquals(100, it.pageCount) } }
        render(file, 99) { _, b -> assertBlue(b.getPixel(60, 50)) }
    }
}
