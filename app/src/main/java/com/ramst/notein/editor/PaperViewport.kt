package com.ramst.notein.editor

import com.ramst.notein.storage.PageEntity
import kotlin.math.min

/** Centre paper that fits; otherwise allow panning as far as either document edge. */
internal fun boundedPaperX(x: Float, scale: Float, frameWidth: Float, paperWidth: Float,
    stripWidth: Float = paperWidth): Float {
    val centre = (frameWidth - paperWidth * scale) / 2f
    val travel = ((stripWidth * scale - frameWidth) / 2f).coerceAtLeast(0f)
    return centre + (x - centre).coerceIn(-travel, travel)
}

internal fun boundedPaperViewport(x: Float, y: Float, scale: Float, frameWidth: Float, frameHeight: Float,
    paperWidth: Float, paperHeight: Float): Triple<Float, Float, Float> {
    val boundedX = boundedPaperX(x, scale, frameWidth, paperWidth)
    val scaledHeight = paperHeight * scale
    val boundedY = if (scaledHeight <= frameHeight) (frameHeight - scaledHeight) / 2f
        else y.coerceIn(frameHeight - scaledHeight, 0f)
    return Triple(boundedX, boundedY, scale)
}

internal fun PageEntity.boundedOnPaper(): PageEntity {
    if (viewportWidth <= 0f || viewportHeight <= 0f || pageWidth <= 0f || pageHeight <= 0f) return this
    val (x, y, scale) = boundedPaperViewport(viewportX, viewportY, viewportScale, viewportWidth, viewportHeight, pageWidth, pageHeight)
    return copy(viewportX = x, viewportY = y, viewportScale = scale)
}

internal fun PageEntity.fitNewPaper(width: Float, height: Float): PageEntity {
    val scale = min(width / pageWidth, height / pageHeight).coerceIn(0.000001f, 6f)
    return copy(viewportWidth = width, viewportHeight = height, viewportScale = scale,
        viewportX = (width - pageWidth * scale) / 2f, viewportY = (height - pageHeight * scale) / 2f).boundedOnPaper()
}
