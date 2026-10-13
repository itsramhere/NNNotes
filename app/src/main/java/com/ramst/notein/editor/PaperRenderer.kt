package com.ramst.notein.editor

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Shader
import android.util.LruCache
import com.ramst.notein.storage.PageEntity
import com.ramst.notein.storage.PaperTemplate
import kotlin.math.floor

internal fun PageEntity.paperTemplate() = PaperTemplate(paperBackground, pageWidth, pageHeight,
    paperSpacingMm, paperPatternWeight, paperBackgroundColor)

/** All surfaces draw the same paper in document points, including negative canvas coordinates. */
internal object PaperRenderer {
    const val POINTS_PER_MM = 72f / 25.4f
    private data class TileKey(val pattern: String, val spacing: Float, val weight: String, val color: Int)
    private val tiles = LruCache<TileKey, BitmapShader>(8)
    fun draw(canvas: Canvas, template: PaperTemplate, left: Float = 0f, top: Float = 0f,
        right: Float = template.width, bottom: Float = template.height) {
        if (!listOf(left, top, right, bottom).all { it.isFinite() } || right <= left || bottom <= top) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.save()
        try {
            canvas.clipRect(left, top, right, bottom)
            paint.color = template.backgroundColor
            canvas.drawRect(left, top, right, bottom, paint)
            if (template.background == "blank") return
            val color = template.backgroundColor
            val dark = Color.red(color) * .2126f + Color.green(color) * .7152f + Color.blue(color) * .0722f < 115f
            paint.color = if (template.patternWeight == "legacy") Color.rgb(218, 226, 235)
                else if (dark) Color.rgb(185, 199, 214) else Color.rgb(157, 176, 194)
            paint.strokeWidth = when (template.patternWeight) { "legacy" -> 1f; "fine" -> .6f; "bold" -> 1.8f; else -> 1.1f }
            val spacing = template.spacingMm.takeIf { it.isFinite() && it > 0f }?.times(POINTS_PER_MM) ?: 7f * POINTS_PER_MM
            val columns = (right.toDouble() - left) / spacing + 2
            val rows = (bottom.toDouble() - top) / spacing + 2
            val losesPrecision = left + spacing == left || top + spacing == top || right + spacing == right || bottom + spacing == bottom
            if (losesPrecision || (template.background == "dots" && columns * rows > 50_000) || columns + rows > 10_000) {
                paint.shader = tile(TileKey(template.background, spacing, template.patternWeight, paint.color))
                paint.isFilterBitmap = true
                canvas.drawRect(left, top, right, bottom, paint)
                return
            }
            if (template.background == "lined" || template.background == "grid") {
                var y = floor(top / spacing) * spacing
                while (y <= bottom) { canvas.drawLine(left, y, right, y, paint); y += spacing }
            }
            if (template.background == "grid") {
                var x = floor(left / spacing) * spacing
                while (x <= right) { canvas.drawLine(x, top, x, bottom, paint); x += spacing }
            }
            if (template.background == "dots") {
                val radius = when (template.patternWeight) { "fine" -> .65f; "bold" -> 1.7f; else -> 1.1f }
                var y = floor(top / spacing) * spacing
                while (y <= bottom) {
                    var x = floor(left / spacing) * spacing
                    while (x <= right) { canvas.drawCircle(x, y, radius, paint); x += spacing }
                    y += spacing
                }
            }
        } finally { canvas.restore() }
    }

    /** Cache only raster tiles, never saved page/content counts. Shader period remains exact document spacing. */
    private fun tile(key: TileKey): BitmapShader = synchronized(tiles) {
        tiles.get(key) ?: run {
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            bitmap.setHasMipMap(true)
            val canvas = Canvas(bitmap)
            val factor = 64f / key.spacing
            canvas.scale(factor, factor)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = key.color
                strokeWidth = when (key.weight) { "legacy" -> 1f; "fine" -> .6f; "bold" -> 1.8f; else -> 1.1f }
            }
            if (key.pattern == "lined" || key.pattern == "grid") {
                canvas.drawLine(0f, 0f, key.spacing, 0f, paint)
                canvas.drawLine(0f, key.spacing, key.spacing, key.spacing, paint)
            }
            if (key.pattern == "grid") {
                canvas.drawLine(0f, 0f, 0f, key.spacing, paint)
                canvas.drawLine(key.spacing, 0f, key.spacing, key.spacing, paint)
            }
            if (key.pattern == "dots") {
                val radius = when (key.weight) { "fine" -> .65f; "bold" -> 1.7f; else -> 1.1f }
                for (x in listOf(0f, key.spacing)) for (y in listOf(0f, key.spacing)) canvas.drawCircle(x, y, radius, paint)
            }
            BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
                setLocalMatrix(Matrix().apply { setScale(key.spacing / 64f, key.spacing / 64f) })
                tiles.put(key, this)
            }
        }
    }
}
