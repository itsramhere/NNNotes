package com.ramst.notein.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Matrix
import android.graphics.RectF
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.ramst.notein.editor.IMAGE_OBJECT_TYPE
import com.ramst.notein.editor.INK_STROKE_TYPE
import com.ramst.notein.editor.TEXT_OBJECT_TYPE
import com.ramst.notein.editor.ImageAssets
import com.ramst.notein.editor.textLayout
import com.ramst.notein.editor.toContentObject
import com.ramst.notein.editor.toInkStrokeRecord
import com.ramst.notein.storage.A4_PORTRAIT_HEIGHT
import com.ramst.notein.storage.A4_PORTRAIT_WIDTH
import com.ramst.notein.storage.EditableObjectEntity
import com.ramst.notein.storage.EditorRepository
import com.ramst.notein.storage.PageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.ceil
import java.io.OutputStream

data class CanvasExportRegion(val left: Float, val top: Float, val width: Float, val height: Float) {
    init { require(width > 0 && height > 0 && listOf(left, top, width, height, left + width, top + height).all { it.isFinite() }) }
    fun pagination(): Pair<Int, Int> {
        val columns = ceil(width.toDouble() / A4_PORTRAIT_WIDTH).toLong()
        val rows = ceil(height.toDouble() / A4_PORTRAIT_HEIGHT).toLong()
        require(columns in 1..Int.MAX_VALUE.toLong() && rows in 1..Int.MAX_VALUE.toLong() &&
            columns <= Int.MAX_VALUE.toLong() / rows) { "This region is too large to paginate." }
        // Guard metadata allocation using the device's actual memory budget, without a content quota.
        require(columns * rows * 128L < Runtime.getRuntime().maxMemory() / 4) { "Not enough memory to paginate this region." }
        return columns.toInt() to rows.toInt()
    }
}

data class ExportProgress(val completed: Int, val total: Int)

/** A flattened 150 DPI exporter with an immutable content snapshot and streamed pages. */
class PdfExporter(
    private val context: Context,
    private val editorRepository: EditorRepository,
    private val pdfRepository: PdfRepository,
    private val openDestination: (Uri) -> OutputStream? = { context.applicationContext.contentResolver.openOutputStream(it, "wt") },
    private val deleteDestination: (Uri) -> Unit = { discardPdfDestination(context.applicationContext, it) },
) {
    private val appContext = context.applicationContext
    private val dpiScale = 150f / 72f

    suspend fun exportSelectedPage(notebookId: String, pageId: String, destination: Uri, progress: (ExportProgress) -> Unit) {
        exportPages(notebookId, listOf(pageId), destination, progress)
    }

    suspend fun exportNotebook(notebookId: String, destination: Uri, progress: (ExportProgress) -> Unit) = withDestination(destination) {
        val snapshot = editorRepository.defaultExportSnapshot(notebookId)
        export(destination, snapshot.map { (page, objects) -> ExportPage(page, objects, 0f, 0f) }, notebookId, progress)
    }

    suspend fun exportPages(notebookId: String, pageIds: List<String>, destination: Uri, progress: (ExportProgress) -> Unit) = withDestination(destination) {
        val snapshot = editorRepository.exportSnapshot(notebookId, pageIds)
        export(destination, snapshot.map { (page, objects) -> ExportPage(page, objects, 0f, 0f) }, notebookId, progress)
    }

    suspend fun exportCanvasRegion(notebookId: String, pageId: String, region: CanvasExportRegion, destination: Uri,
        progress: (ExportProgress) -> Unit) = withDestination(destination) {
        val (page, objects) = editorRepository.exportSnapshot(notebookId, listOf(pageId)).single()
        val (columns, rows) = region.pagination()
        val pages = buildList {
            repeat(rows) { row -> repeat(columns) { column ->
                add(ExportPage(page.copy(pageWidth = A4_PORTRAIT_WIDTH, pageHeight = A4_PORTRAIT_HEIGHT, pdfPageIndex = null), objects,
                    -region.left - column * A4_PORTRAIT_WIDTH, -region.top - row * A4_PORTRAIT_HEIGHT,
                    minOf(A4_PORTRAIT_WIDTH, region.width - column * A4_PORTRAIT_WIDTH),
                    minOf(A4_PORTRAIT_HEIGHT, region.height - row * A4_PORTRAIT_HEIGHT)))
            } }
        }
        export(destination, pages, notebookId, progress)
    }

    private data class ExportPage(val page: PageEntity, val objects: List<EditableObjectEntity>, val offsetX: Float, val offsetY: Float,
        val clipWidth: Float = page.pageWidth, val clipHeight: Float = page.pageHeight)

    private suspend fun withDestination(destination: Uri, work: suspend () -> Unit) {
        try { work() }
        catch (error: OutOfMemoryError) {
            runCatching { deleteDestination(destination) }
            throw java.io.IOException("Not enough memory to export. Your notebook has been retained.", error)
        } catch (exception: Exception) {
            runCatching { deleteDestination(destination) }
            throw exception
        }
    }

    private suspend fun export(destination: Uri, pages: List<ExportPage>, notebookId: String, progress: (ExportProgress) -> Unit) =
        withContext(Dispatchers.IO) {
            require(pages.isNotEmpty())
            val temporary = File(appContext.cacheDir, "export-${System.nanoTime()}.pdf")
            try {
                val sources = pdfRepository.documents(notebookId).associateBy { it.sourceId }
                require(pages.all { it.page.pdfPageIndex == null || it.page.pdfSourceId in sources }) { "The local PDF source is unavailable." }
                progress(ExportProgress(0, pages.size))
                FileOutputStream(temporary).use { stream ->
                val document = StreamingPdfWriter(stream, pages.size)
                pages.forEachIndexed { index, exportPage ->
                    currentCoroutineContext().ensureActive()
                    val page = exportPage.page
                    val source = page.pdfSourceId?.let { sources[it] }
                    require(page.pageWidth > 0 && page.pageHeight > 0) { "Choose a canvas region before exporting." }
                    val bitmapWidth = ceil(page.pageWidth * dpiScale).toInt().coerceAtLeast(1)
                    val bitmapHeight = ceil(page.pageHeight * dpiScale).toInt().coerceAtLeast(1)
                    val pixels = bitmapWidth.toLong() * bitmapHeight
                    require(pixels <= Runtime.getRuntime().maxMemory() / 12) { "This page is too large to render safely on this device." }
                    val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
                    try {
                        val canvas = Canvas(bitmap)
                        canvas.drawColor(Color.WHITE)
                        if (source != null && page.pdfPageIndex != null) drawPdfBackground(canvas, source.localPath, page.pdfPageIndex, bitmap)
                        canvas.save()
                        canvas.clipRect(0f, 0f, exportPage.clipWidth * dpiScale, exportPage.clipHeight * dpiScale)
                        if (source == null || page.pdfPageIndex == null) drawPaper(canvas, page, dpiScale, exportPage.offsetX, exportPage.offsetY)
                        drawObjects(canvas, exportPage.objects, dpiScale, exportPage.offsetX, exportPage.offsetY)
                        canvas.restore()
                        currentCoroutineContext().ensureActive()
                        document.page(bitmap, page.pageWidth, page.pageHeight)
                    } finally { bitmap.recycle() }
                    progress(ExportProgress(index + 1, pages.size))
                }
                document.finish(); stream.fd.sync()
                }
                currentCoroutineContext().ensureActive()
                requireNotNull(openDestination(destination)) { "Could not open the export destination." }.use { out ->
                    temporary.inputStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            out.write(buffer, 0, count)
                        }
                        out.flush()
                        currentCoroutineContext().ensureActive()
                    }
                }
            } finally {
                temporary.delete()
            }
        }

    private fun drawPdfBackground(canvas: Canvas, path: String, index: Int, target: Bitmap) {
        ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                require(index in 0 until renderer.pageCount) { "The PDF source page is unavailable." }
                renderer.openPage(index).use { it.render(target, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT) }
            }
        }
    }

    private fun drawPaper(canvas: Canvas, page: PageEntity, scale: Float, offsetX: Float, offsetY: Float) {
        canvas.save()
        canvas.scale(scale, scale)
        canvas.translate(offsetX, offsetY)
        com.ramst.notein.editor.PaperRenderer.draw(canvas, com.ramst.notein.storage.PaperTemplate(page.paperBackground,
            page.pageWidth, page.pageHeight, page.paperSpacingMm, page.paperPatternWeight, page.paperBackgroundColor),
            -offsetX, -offsetY, page.pageWidth - offsetX, page.pageHeight - offsetY)
        canvas.restore()
    }

    private suspend fun drawObjects(canvas: Canvas, objects: List<EditableObjectEntity>, scale: Float, offsetX: Float, offsetY: Float) {
        val assets = ImageAssets(appContext)
        val inkRenderer = CanvasStrokeRenderer.create()
        objects.sortedWith(compareBy<EditableObjectEntity> { it.createdAt }.thenBy { it.id }).forEach { entity ->
            currentCoroutineContext().ensureActive()
            when (entity.type) {
                com.ramst.notein.editor.PDF_OBJECT_TYPE -> {
                    val content = requireNotNull(entity.toContentObject())
                    val source = requireNotNull(content.pdfSourceId?.let { pdfRepository.source(it) }) { "The local PDF source is unavailable." }
                    val bitmap = renderPdfBitmap(source.localPath, requireNotNull(content.pdfPageIndex),
                        (content.sourceBounds.right * content.transform.scale * scale).toInt().coerceAtLeast(1),
                        (content.sourceBounds.bottom * content.transform.scale * scale).toInt().coerceAtLeast(1))
                    try {
                        canvas.save()
                        canvas.translate((content.transform.x.toFloat() + offsetX) * scale, (content.transform.y.toFloat() + offsetY) * scale)
                        canvas.scale(content.transform.scale.toFloat() * scale, content.transform.scale.toFloat() * scale)
                        canvas.drawBitmap(bitmap, null, RectF(0f, 0f, content.sourceBounds.right, content.sourceBounds.bottom), Paint(Paint.FILTER_BITMAP_FLAG))
                        canvas.restore()
                    } finally { bitmap.recycle() }
                }
                INK_STROKE_TYPE -> {
                    val decoded = requireNotNull(entity.toInkStrokeRecord()?.decode()) { "An annotation could not be decoded." }
                    val matrix = Matrix().apply { setScale(scale, scale); postTranslate(offsetX * scale, offsetY * scale) }
                    canvas.save()
                    canvas.translate(offsetX * scale, offsetY * scale)
                    canvas.scale(scale, scale)
                    inkRenderer.draw(canvas, decoded.stroke, matrix)
                    canvas.restore()
                }
                TEXT_OBJECT_TYPE -> requireNotNull(entity.toContentObject()) { "A text annotation could not be decoded." }.let { content ->
                    canvas.save(); canvas.translate((content.transform.x.toFloat() + offsetX) * scale, (content.transform.y.toFloat() + offsetY) * scale)
                    canvas.scale(content.transform.scale.toFloat() * scale, content.transform.scale.toFloat() * scale)
                    canvas.translate(8f, 8f); textLayout(content).draw(canvas); canvas.restore()
                }
                IMAGE_OBJECT_TYPE -> requireNotNull(entity.toContentObject()) { "An image annotation could not be decoded." }.let { content ->
                    val bitmap = requireNotNull(content.assetId?.let { BitmapFactory.decodeFile(assets.preview(it).absolutePath) }) { "An image is unavailable. Export was not completed." }
                    try {
                        canvas.save(); canvas.translate((content.transform.x.toFloat() + offsetX) * scale, (content.transform.y.toFloat() + offsetY) * scale)
                        canvas.scale(content.transform.scale.toFloat() * scale, content.transform.scale.toFloat() * scale)
                        canvas.drawBitmap(bitmap, null, RectF(0f, 0f, content.sourceBounds.right, content.sourceBounds.bottom), Paint(Paint.FILTER_BITMAP_FLAG)); canvas.restore()
                    } finally { bitmap.recycle() }
                }
            }
        }
    }
}
