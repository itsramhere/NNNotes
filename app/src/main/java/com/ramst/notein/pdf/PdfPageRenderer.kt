package com.ramst.notein.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.view.View
import android.util.LruCache
import com.ramst.notein.storage.PageEntity
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.sqrt

/** Visible pages only, bounded pixel/cache budgets, and no stale navigation results. */
class PdfPageRenderer(context: Context) : View(context) {
    private var scope: CoroutineScope? = null
    private var job: Job? = null
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private var page: PageEntity? = null
    private var sourcePath: String? = null
    private var viewportX = 0f
    private var viewportY = 0f
    private var viewportScale = 1f
    private var requestedKey: String? = null
    private var failedKey: String? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    var onError: ((String) -> Unit)? = null

    fun setPdf(source: String?, value: PageEntity?, x: Float, y: Float, scale: Float) {
        sourcePath = source; page = value; viewportX = x; viewportY = y; viewportScale = scale
        request(); invalidate()
    }
    private fun request() {
        val currentScope = scope ?: return
        val current = page ?: return
        val path = sourcePath ?: return
        if (width <= 0 || height <= 0) return
        val requestedWidth = (current.pageWidth * viewportScale).coerceAtLeast(1f)
        val requestedHeight = (current.pageHeight * viewportScale).coerceAtLeast(1f)
        val budget = minOf(4_000_000.0, Runtime.getRuntime().maxMemory() / 32.0)
        val factor = minOf(1.0, sqrt(budget / (requestedWidth.toDouble() * requestedHeight)))
        val w = (requestedWidth * factor).toInt().coerceAtLeast(1)
        val h = (requestedHeight * factor).toInt().coerceAtLeast(1)
        val key = "$path:${current.pdfPageIndex}:$w:$h"
        if (requestedKey == key) return
        requestedKey = key
        job?.cancel()
        if (cache.get(key) != null || failedKey == key) return
        job = currentScope.launch {
            var result: Bitmap? = null
            try {
                withContext(Dispatchers.IO) {
                    ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                        PdfRenderer(fd).use { renderer ->
                            val index = requireNotNull(current.pdfPageIndex)
                            require(index in 0 until renderer.pageCount)
                            renderer.openPage(index).use { source ->
                                result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                result!!.eraseColor(Color.WHITE)
                                source.render(result!!, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            }
                        }
                    }
                }
                ensureActive()
                if (requestedKey == key) { cache.put(key, result!!); result = null; invalidate() }
            } catch (exception: CancellationException) { throw exception }
            catch (error: OutOfMemoryError) {
                if (requestedKey == key) { failedKey = key; onError?.invoke("Not enough memory to display this PDF page. Your content has been retained.") }
            } catch (exception: Exception) {
                if (requestedKey == key) { failedKey = key; onError?.invoke("Could not render this PDF page. The local source and annotations have been retained.") }
            } finally { result?.recycle() }
        }
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        requestedKey = null; failedKey = null; request()
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); request() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = page ?: return
        val path = sourcePath ?: return
        val matching = requestedKey?.let { cache.get(it) } ?: cache.snapshot().entries
            .lastOrNull { it.key.startsWith("$path:${current.pdfPageIndex}:") }?.value ?: return
        canvas.drawBitmap(matching, null,
            RectF(viewportX, viewportY, viewportX + current.pageWidth * viewportScale, viewportY + current.pageHeight * viewportScale), paint)
    }
    override fun onDetachedFromWindow() {
        scope?.cancel(); scope = null; job = null; requestedKey = null; cache.evictAll()
        super.onDetachedFromWindow()
    }
}
