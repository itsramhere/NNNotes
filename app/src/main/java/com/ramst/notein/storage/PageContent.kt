package com.ramst.notein.storage

import org.json.JSONObject

/** Only supported, nonempty content qualifies for automatic paper allocation. */
internal fun EditableObjectEntity.isNonemptyPageContent(): Boolean = when (type) {
    "ink_stroke_v1", "image_v1" -> payload.isNotBlank()
    "text_box_v1" -> runCatching { JSONObject(payload).optString("text").isNotBlank() }.getOrDefault(false)
    else -> false
}
