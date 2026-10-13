package com.ramst.notein.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File

/** The caller owns the bitmap. Each request opens one page and closes its native resources. */
internal fun renderPdfBitmap(path: String, index: Int, requestedWidth: Int, requestedHeight: Int): Bitmap {
    val pixels = requestedWidth.toDouble() * requestedHeight
    val factor = minOf(1.0, kotlin.math.sqrt(4_000_000.0 / pixels.coerceAtLeast(1.0)))
    val width = (requestedWidth * factor).toInt().coerceAtLeast(1)
    val height = (requestedHeight * factor).toInt().coerceAtLeast(1)
    return ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
        PdfRenderer(fd).use { renderer ->
            renderer.openPage(index).use { page ->
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                } catch (error: Throwable) { bitmap.recycle(); throw error }
            }
        }
    }
}
