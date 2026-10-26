package com.ramst.notein.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import com.ramst.notein.pdf.renderPdfBitmap

internal fun textLayout(content: ContentObject): StaticLayout {
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = content.color
        textSize = content.fontSize
        val style = when {
            content.bold && content.italic -> Typeface.BOLD_ITALIC
            content.bold -> Typeface.BOLD
            content.italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        typeface = Typeface.create(Typeface.DEFAULT, style)
    }
    return StaticLayout.Builder.obtain(content.text, 0, content.text.length, paint,
        (content.sourceBounds.right - 16f).toInt().coerceAtLeast(1))
        .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(true).build()
}

/** Disk decoding runs off the render/input thread; only display previews enter this cache. */
internal class ContentRenderer(context: Context, private val invalidate: () -> Unit, private val onError: (String) -> Unit,
    private val renderPdf: (String, Int, Int, Int) -> Bitmap = ::renderPdfBitmap) {
    private val assets = ImageAssets(context)
    private var scope: CoroutineScope? = null
    private val loading = mutableSetOf<String>()
    private val failed = mutableSetOf<String>()
    private val displayKeys = mutableMapOf<String, String>()
    private val pdfRenderSlots = Semaphore(2)
    private val layouts = LruCache<String, StaticLayout>(64)
    private val bitmaps = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    fun attach() { if (scope == null) scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    fun detach() { scope?.cancel(); scope = null; loading.clear(); bitmaps.evictAll(); layouts.evictAll(); failed.clear(); displayKeys.clear() }
    fun prepare(objects: List<ContentObject>, scale: Float = 1f) {
        val pdfObjects = objects.filter { it.entity.type == PDF_OBJECT_TYPE }
        val pdfSideBudget = kotlin.math.sqrt((16 * 1024 * 1024 / 4 / pdfObjects.size.coerceAtLeast(1)).toDouble()).toInt().coerceIn(64, 2048)
        pdfObjects.forEach { content ->
            val side = (maxOf(content.bounds.right - content.bounds.left, content.bounds.bottom - content.bounds.top) * scale)
                .toInt().coerceIn(64, pdfSideBudget)
            val key = "pdf:${content.pdfSourceId}:${content.pdfPageIndex}:$side"
            displayKeys[content.entity.id] = key
            if (bitmaps.get(key) == null && key !in loading && key !in failed) {
                val currentScope = scope ?: return@forEach
                loading += key
                currentScope.launch {
                    var result: Bitmap? = null
                    try {
                        pdfRenderSlots.withPermit {
                            withContext(Dispatchers.IO) {
                                val ratio = side / maxOf(content.sourceBounds.right, content.sourceBounds.bottom)
                                // Assign before returning to Main so prompt cancellation still recycles the allocation.
                                result = renderPdf(requireNotNull(content.pdfPath), requireNotNull(content.pdfPageIndex),
                                    (content.sourceBounds.right * ratio).toInt().coerceAtLeast(1),
                                    (content.sourceBounds.bottom * ratio).toInt().coerceAtLeast(1))
                            }
                        }
                        bitmaps.put(key, result!!); result = null; invalidate()
                    } catch (exception: kotlinx.coroutines.CancellationException) { throw exception }
                    catch (error: OutOfMemoryError) { failed += key; onError("Not enough memory to display a PDF page.") }
                    catch (_: Exception) { failed += key; onError("Could not display an inserted PDF page.") }
                    finally { result?.recycle(); loading -= key }
                }
            }
        }
        val imageIds = objects.filter { it.entity.type == IMAGE_OBJECT_TYPE }.mapNotNull { it.assetId }.distinct()
        val targetSide = kotlin.math.sqrt((16 * 1024 * 1024 / 4 / imageIds.size.coerceAtLeast(1)).toDouble()).toInt().coerceIn(1, 1024)
        displayKeys.keys.retainAll(imageIds.toSet() + objects.filter { it.entity.type == PDF_OBJECT_TYPE }.map { it.entity.id })
        imageIds.forEach { id ->
            val key = "$id:$targetSide"
            displayKeys[id] = key
            if (bitmaps.get(key) == null && key !in loading && id !in failed) {
                val currentScope = scope ?: return@forEach
                loading += key
                currentScope.launch {
                    try {
                        val bitmap = withContext(Dispatchers.IO) {
                            val path = assets.preview(id).absolutePath
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeFile(path, bounds)
                            var sample = 1
                            while (bounds.outWidth / sample > targetSide || bounds.outHeight / sample > targetSide) sample *= 2
                            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
                        }
                        if (bitmap != null) bitmaps.put(key, bitmap) else {
                            failed += id
                            onError("An image preview is unavailable. The original image and your edits have been retained.")
                        }
                        invalidate()
                    } catch (exception: kotlinx.coroutines.CancellationException) { throw exception }
                    catch (error: OutOfMemoryError) { failed += id; onError("Not enough memory to display an image. Your saved content has been retained."); invalidate() }
                    catch (exception: Exception) { failed += id; onError("Could not display an image. Your saved content has been retained.") }
                    finally { loading -= key }
                }
            }
        }
    }
    fun draw(canvas: Canvas, content: ContentObject) {
        canvas.save()
        canvas.translate(content.transform.x.toFloat(), content.transform.y.toFloat())
        canvas.scale(content.transform.scale.toFloat(), content.transform.scale.toFloat())
        when (content.entity.type) {
            TEXT_OBJECT_TYPE, HEADING_OBJECT_TYPE -> {
                val layout = layouts.get(content.entity.payload) ?: textLayout(content).also { layouts.put(content.entity.payload, it) }
                canvas.translate(8f, 8f)
                layout.draw(canvas)
            }
            SHAPE_OBJECT_TYPE, GRAPH_OBJECT_TYPE, TABLE_OBJECT_TYPE -> drawStructuredContent(canvas, content)
            IMAGE_OBJECT_TYPE, PDF_OBJECT_TYPE -> {
                val bitmap = (if (content.entity.type == PDF_OBJECT_TYPE) displayKeys[content.entity.id]
                    else content.assetId?.let { displayKeys[it] })?.let { bitmaps.get(it) }
                val rect = RectF(0f, 0f, content.sourceBounds.right, content.sourceBounds.bottom)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                if (bitmap != null) canvas.drawBitmap(bitmap, null, rect, paint)
                else {
                    paint.color = Color.rgb(233, 237, 242); canvas.drawRect(rect, paint)
                    paint.color = Color.DKGRAY; paint.textSize = 14f
                    canvas.drawText(if (content.assetId in failed) "Image unavailable" else "Loading image…", 8f, 22f, paint)
                }
            }
        }
        canvas.restore()
    }
}
