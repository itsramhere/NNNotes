package com.ramst.notein.editor

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.ramst.notein.storage.PaperTemplate

internal fun DrawScope.drawPaperPreview(template: PaperTemplate) {
    val width = template.width.takeIf { it > 0f } ?: 595f
    val height = template.height.takeIf { it > 0f } ?: 842f
    val factor = minOf(size.width / width, size.height / height)
    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        native.save()
        native.translate((size.width - width * factor) / 2f, (size.height - height * factor) / 2f)
        native.scale(factor, factor)
        PaperRenderer.draw(native, template, right = width, bottom = height)
        native.restore()
    }
}

@Composable
internal fun PaperTemplatePreview(template: PaperTemplate, modifier: Modifier = Modifier) {
    Canvas(modifier.semantics { contentDescription = "Paper preview" }) { drawPaperPreview(template) }
}
