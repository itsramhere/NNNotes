package com.ramst.notein.editor

import com.ramst.notein.storage.EditableObjectEntity
import com.ramst.notein.storage.PageEntity

/** Only the adjacent sheets are hosted; their ink and media stay in document coordinates. */
internal data class PageNeighbor(val direction: Int, val page: PageEntity,
    val objects: List<EditableObjectEntity>, val pdfPath: String?)
