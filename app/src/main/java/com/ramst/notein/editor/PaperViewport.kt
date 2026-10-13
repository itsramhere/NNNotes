package com.ramst.notein.editor

import com.ramst.notein.storage.PageEntity
import kotlin.math.min

/** Finite sheets stay horizontally centered; vertical scrolling stops at their edges. */
internal fun centeredPaperViewport(y: Float, scale: Float, frameWidth: Float, frameHeight: Float,
    paperWidth: Float, paperHeight: Float): Triple<Float, Float, Float> {
    val x = (frameWidth - paperWidth * scale) / 2f
    val scaledHeight = paperHeight * scale
    val boundedY = if (scaledHeight <= frameHeight) (frameHeight - scaledHeight) / 2f
        else y.coerceIn(frameHeight - scaledHeight, 0f)
    return Triple(x, boundedY, scale)
}

internal fun PageEntity.centeredOnPaper(): PageEntity {
    if (viewportWidth <= 0f || viewportHeight <= 0f || pageWidth <= 0f || pageHeight <= 0f) return this
    val (x, y, scale) = centeredPaperViewport(viewportY, viewportScale, viewportWidth, viewportHeight, pageWidth, pageHeight)
    return copy(viewportX = x, viewportY = y, viewportScale = scale)
}

internal fun PageEntity.fitNewPaper(width: Float, height: Float): PageEntity {
    val scale = min(width / pageWidth, height / pageHeight).coerceIn(0.000001f, 6f)
    return copy(viewportWidth = width, viewportHeight = height, viewportScale = scale).centeredOnPaper()
}
