package com.ramst.notein.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import java.util.UUID

internal data class ImportedImage(val assetId: String, val width: Int, val height: Int)

/** Originals are immutable and shared by copied objects; previews have bounded dimensions. */
internal class ImageAssets(context: Context,
    private val openSource: (Uri) -> InputStream? = { context.applicationContext.contentResolver.openInputStream(it) },
) {
    private val appContext = context.applicationContext
    private val directory = File(appContext.filesDir, "image-assets")

    fun original(assetId: String): File = File(directory, "${safeId(assetId)}.original")
    fun preview(assetId: String): File = File(directory, "${safeId(assetId)}.preview.png")

    suspend fun import(uri: Uri): ImportedImage = withContext(Dispatchers.IO) {
        check(directory.isDirectory || directory.mkdirs()) { "Could not open image storage." }
        val assetId = UUID.randomUUID().toString()
        val temporary = File(directory, "$assetId.importing")
        val source = original(assetId)
        val display = preview(assetId)
        try {
            val input = requireNotNull(openSource(uri)) { "Could not read the selected image." }
            input.use { stream -> FileOutputStream(temporary).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = stream.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
            } }
            val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temporary.absolutePath, dimensions)
            require(dimensions.outWidth > 0 && dimensions.outHeight > 0) { "This file is not a supported image." }
            var sampleSize = 1
            while (dimensions.outWidth / sampleSize > 2048 || dimensions.outHeight / sampleSize > 2048) sampleSize *= 2
            val decoded = requireNotNull(BitmapFactory.decodeFile(temporary.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sampleSize; inPreferredConfig = Bitmap.Config.ARGB_8888 })) {
                "Could not decode the selected image."
            }
            val orientation = runCatching { ExifInterface(temporary.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
                .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            val matrix = Matrix().apply {
                when (orientation) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
                }
            }
            val bitmap = if (matrix.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            try {
                coroutineContext.ensureActive()
                FileOutputStream(display).use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Could not create an image preview." }
                    output.fd.sync()
                }
                check(temporary.renameTo(source)) { "Could not store the original image." }
                val swapsAxes = orientation in setOf(ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_ROTATE_90,
                    ExifInterface.ORIENTATION_TRANSVERSE, ExifInterface.ORIENTATION_ROTATE_270)
                ImportedImage(assetId, if (swapsAxes) dimensions.outHeight else dimensions.outWidth,
                    if (swapsAxes) dimensions.outWidth else dimensions.outHeight)
            } finally {
                if (bitmap !== decoded) bitmap.recycle()
                decoded.recycle()
            }
        } catch (error: OutOfMemoryError) {
            temporary.delete(); source.delete(); display.delete()
            throw java.io.IOException("Not enough memory to decode this image. Please try a smaller image.", error)
        } catch (exception: Exception) {
            // Only this uncommitted import's UUID files are removed.
            temporary.delete(); source.delete(); display.delete()
            throw exception
        }
    }

    private fun safeId(value: String): String = UUID.fromString(value).toString()
}
