package com.ramst.notein.pdf

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.ramst.notein.storage.NotebookEntity
import com.ramst.notein.storage.NotebookType
import com.ramst.notein.storage.PageEntity
import com.ramst.notein.storage.PdfDao
import com.ramst.notein.storage.PdfDocumentEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.io.InputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable

data class ImportedPdf(val notebookId: String, val title: String)
data class PreparedPdf(val sourceId: String, val localPath: String, val dimensions: List<Pair<Float, Float>>)

/** Validates the private local copy before any database row is created. */
class PdfRepository(
    private val context: Context,
    private val dao: PdfDao,
    private val openSource: (Uri) -> InputStream? = { context.applicationContext.contentResolver.openInputStream(it) },
) {
    private val appContext = context.applicationContext
    private val directory get() = File(appContext.filesDir, "pdf-documents")
    private val insertionMutex = kotlinx.coroutines.sync.Mutex()
    private var reconciled = false

    private suspend fun reconcileAbandonedImports() {
        insertionMutex.lock()
        try {
            if (reconciled) return
            val referenced = dao.allSourcePaths().toSet()
            directory.listFiles()?.forEach { file ->
                val owned = runCatching { UUID.fromString(file.nameWithoutExtension) }.isSuccess
                if (owned && file.isFile && file.extension in setOf("pdf", "importing") && file.absolutePath !in referenced) file.delete()
            }
            reconciled = true
        } finally { insertionMutex.unlock() }
    }

    suspend fun prepare(uri: Uri, progress: (String) -> Unit): PreparedPdf {
        val id = UUID.randomUUID().toString()
        val pending = File(directory, "$id.importing")
        val source = File(directory, "$id.pdf")
        try {
            return withContext(Dispatchers.IO) {
                check(directory.isDirectory || directory.mkdirs()) { "Could not open PDF storage." }
                reconcileAbandonedImports()
                progress("Copying PDF…")
                requireNotNull(openSource(uri)) { "Could not read this PDF." }.use { input ->
                    FileOutputStream(pending).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                }
                progress("Reading PDF pages…")
                val dimensions = ParcelFileDescriptor.open(pending, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { renderer ->
                        require(renderer.pageCount > 0) { "This PDF has no pages." }
                        List(renderer.pageCount) { index ->
                            currentCoroutineContext().ensureActive()
                            renderer.openPage(index).use { page ->
                                require(page.width > 0 && page.height > 0)
                                page.width.toFloat() to page.height.toFloat()
                            }
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                check(pending.renameTo(source)) { "Could not store this PDF." }
                PreparedPdf(id, source.absolutePath, dimensions)
            }
        } catch (exception: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) { pending.delete(); source.delete() }
            throw exception
        } catch (exception: Exception) {
            withContext(NonCancellable + Dispatchers.IO) { pending.delete(); source.delete() }
            throw java.io.IOException("Could not read PDF. Choose an unlocked, valid file and check available storage.", exception)
        } catch (error: OutOfMemoryError) {
            withContext(NonCancellable + Dispatchers.IO) { pending.delete(); source.delete() }
            throw java.io.IOException("Not enough memory to read this PDF.", error)
        }
    }

    suspend fun discard(prepared: PreparedPdf) = withContext(NonCancellable + Dispatchers.IO) {
        // A cancelled coroutine may return after the insertion transaction committed.
        if (dao.source(prepared.sourceId) == null) File(prepared.localPath).delete()
    }

    suspend fun insert(prepared: PreparedPdf, notebookId: String, currentPage: PageEntity,
        selected: Set<Int>, infinite: Boolean, centerX: Float, centerY: Float) {
        insertionMutex.lock()
        try {
            withContext(Dispatchers.IO) {
                require(selected.isNotEmpty() && selected.all { it in prepared.dimensions.indices })
                val now = System.currentTimeMillis()
                val document = PdfDocumentEntity(notebookId, prepared.localPath, prepared.dimensions.size, now, prepared.sourceId)
                val indices = selected.sorted()
                val pages = if (infinite) emptyList() else indices.map { index ->
                    val size = prepared.dimensions[index]
                    PageEntity(UUID.randomUUID().toString(), notebookId, 0, now, now,
                        pageWidth = size.first, pageHeight = size.second, pdfPageIndex = index, pdfSourceId = prepared.sourceId)
                }
                var y = centerY
                val objects = if (!infinite) emptyList() else indices.mapIndexed { order, index ->
                    val size = prepared.dimensions[index]
                    val payload = org.json.JSONObject().put("width", size.first).put("height", size.second)
                        .put("pdfSourceId", prepared.sourceId).put("pdfPageIndex", index).put("pdfPath", prepared.localPath)
                        .put("transformX", centerX - size.first / 2f).put("transformY", y).put("transformScale", 1.0).toString()
                    y += size.second + 24f
                    com.ramst.notein.storage.EditableObjectEntity(UUID.randomUUID().toString(), currentPage.id,
                        "pdf_page_v1", payload, now + order, now)
                }
                currentCoroutineContext().ensureActive()
                dao.insertIntoNotebook(document, currentPage.id, pages, objects)
            }
        } finally { insertionMutex.unlock() }
    }

    suspend fun documents(notebookId: String) = dao.documents(notebookId)
    suspend fun source(sourceId: String) = dao.source(sourceId)

    suspend fun cleanupUnusedSources(notebookId: String) = withContext(Dispatchers.IO) {
        removeUnreferencedFiles(dao.releaseUnusedSources(notebookId))
    }

    suspend fun removeUnreferencedFiles(sources: List<PdfDocumentEntity>) = withContext(Dispatchers.IO) {
        val root = directory.canonicalFile
        sources.forEach { source ->
            val file = File(source.localPath).canonicalFile
            if (file.parentFile == root && dao.pathReferences(source.localPath) == 0) file.delete()
        }
    }

    suspend fun import(uri: Uri, requestedTitle: String? = null): ImportedPdf = withContext(Dispatchers.IO) {
        check(directory.isDirectory || directory.mkdirs()) { "Could not open PDF storage." }
        reconcileAbandonedImports()
        val notebookId = UUID.randomUUID().toString()
        val pending = File(directory, "$notebookId.importing")
        val source = File(directory, "$notebookId.pdf")
        suspend fun cleanUncommittedCopy() {
            pending.delete()
            if (!source.exists()) return
            // Cancellation can win the DAO return after Room has committed. Keep that valid notebook offline.
            val committed = withContext(NonCancellable) { runCatching { dao.document(notebookId) } }
            if (committed.isSuccess && committed.getOrNull() == null) source.delete()
        }
        try {
            requireNotNull(openSource(uri)) { "Could not read the selected PDF." }.use { input ->
                FileOutputStream(pending).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            val descriptor = ParcelFileDescriptor.open(pending, ParcelFileDescriptor.MODE_READ_ONLY)
            val dimensions = descriptor.use { pfd -> PdfRenderer(pfd).use { renderer ->
                require(renderer.pageCount > 0) { "The PDF has no pages." }
                List(renderer.pageCount) { index -> renderer.openPage(index).use { page ->
                    currentCoroutineContext().ensureActive()
                    require(page.width > 0 && page.height > 0) { "The PDF has an invalid page." }
                    page.width.toFloat() to page.height.toFloat()
                } }
            } }
            check(pending.renameTo(source)) { "Could not store the PDF locally." }
            currentCoroutineContext().ensureActive()
            val now = System.currentTimeMillis()
            val title = requestedTitle?.trim()?.takeIf { it.isNotEmpty() } ?: uri.lastPathSegment
                ?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "Imported PDF"
            val notebook = NotebookEntity(notebookId, title, null, now, now, notebookType = NotebookType.PDF.storageValue, autoMatchingPages = false)
            val document = PdfDocumentEntity(notebookId, source.absolutePath, dimensions.size, now)
            val pages = dimensions.mapIndexed { index, size ->
                PageEntity(UUID.randomUUID().toString(), notebookId, index, now, now,
                    pageWidth = size.first, pageHeight = size.second, pdfPageIndex = index, pdfSourceId = notebookId)
            }
            dao.insertPdfNotebook(notebook, document, pages)
            ImportedPdf(notebookId, title)
        } catch (error: OutOfMemoryError) {
            cleanUncommittedCopy()
            throw java.io.IOException("Not enough memory to import this PDF. Existing notebooks have been retained.", error)
        } catch (exception: Exception) {
            cleanUncommittedCopy()
            if (exception is CancellationException) throw exception
            if (exception is SecurityException) throw java.io.IOException("This PDF is password-protected or cannot be opened. Choose an unlocked PDF.", exception)
            throw java.io.IOException("Could not import PDF. The file may be corrupt, unsupported, unreadable, or storage may be full. ${exception.message.orEmpty()}", exception)
        }
    }

    suspend fun document(notebookId: String): PdfDocumentEntity? = dao.document(notebookId)
}
