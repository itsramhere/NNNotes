package com.ramst.notein.editor

import com.ramst.notein.storage.EditableObjectEntity
import com.ramst.notein.storage.PageEntity

/** Page metadata is cheap; the canvas only hosts sheets intersecting the viewport. */
internal data class PageNeighbor(val direction: Int, val page: PageEntity,
    val objects: List<EditableObjectEntity>, val pdfPath: String?)

internal data class PageScrollPosition(val pageId: String, val viewportY: Float, val scale: Float,
    val horizontalOffset: Float)

/** Offsets from the active sheet's top, at a common zoom, including mixed paper sizes. */
internal fun verticalPageOffsets(pageHeight: Float, scale: Float, gap: Float,
    neighbors: List<PageNeighbor>): Map<String, Float> = buildMap {
    var edge = pageHeight * scale
    neighbors.filter { it.direction > 0 }.sortedBy { it.direction }.forEach {
        put(it.page.id, edge + gap)
        edge += gap + it.page.pageHeight * scale
    }
    edge = 0f
    neighbors.filter { it.direction < 0 }.sortedByDescending { it.direction }.forEach {
        edge -= gap + it.page.pageHeight * scale
        put(it.page.id, edge)
    }
}
