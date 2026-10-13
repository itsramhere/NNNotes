package com.ramst.notein.pdf

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.Canvas
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ramst.notein.storage.A4_PORTRAIT_HEIGHT
import com.ramst.notein.storage.A4_PORTRAIT_WIDTH
import com.ramst.notein.editor.PreviewStroke
import com.ramst.notein.editor.drawPreview
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

@Composable
internal fun CanvasExportDialog(onDismiss: () -> Unit, onExport: (CanvasExportRegion) -> Unit, previews: List<PreviewStroke> = emptyList()) {
    val initialLeft = previews.minOfOrNull { it.bounds.left - it.width - 24f } ?: 0f
    val initialTop = previews.minOfOrNull { it.bounds.top - it.width - 24f } ?: 0f
    var left by remember { mutableStateOf(initialLeft.toString()) }
    var top by remember { mutableStateOf(initialTop.toString()) }
    var width by remember { mutableStateOf(maxOf(A4_PORTRAIT_WIDTH, (previews.maxOfOrNull { it.bounds.right + it.width + 24f } ?: A4_PORTRAIT_WIDTH) - initialLeft).toString()) }
    var height by remember { mutableStateOf(maxOf(A4_PORTRAIT_HEIGHT, (previews.maxOfOrNull { it.bounds.bottom + it.width + 24f } ?: A4_PORTRAIT_HEIGHT) - initialTop).toString()) }
    fun parsed(): CanvasExportRegion? = runCatching {
        CanvasExportRegion(left.toFloat(), top.toFloat(), width.toFloat(), height.toFloat())
    }.getOrNull()
    val region = parsed()?.takeIf { runCatching { it.pagination() }.isSuccess }
    val pages = region?.pagination()?.let { (columns, rows) -> columns * rows }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Canvas export region") },
        text = {
            Column {
                Text("Adjust the starting position and region size. Pages export left to right, then top to bottom, on A4 paper at 150 DPI.")
                Row(Modifier.fillMaxWidth()) {
                    OutlinedTextField(left, { left = it }, Modifier.weight(1f), label = { Text("Left") }, singleLine = true)
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(top, { top = it }, Modifier.weight(1f), label = { Text("Top") }, singleLine = true)
                }
                Row(Modifier.fillMaxWidth()) {
                    OutlinedTextField(width, { width = it }, Modifier.weight(1f), label = { Text("Width") }, singleLine = true)
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(height, { height = it }, Modifier.weight(1f), label = { Text("Height") }, singleLine = true)
                }
                Text(if (pages == null) "Enter a positive finite region." else "Preview: $pages A4 page${if (pages == 1) "" else "s"}.", Modifier.padding(top = 12.dp))
                if (region != null) Canvas(Modifier.fillMaxWidth().height(140.dp).semantics { contentDescription = "A4 pagination preview" }) {
                    val factor = minOf(size.width / region.width, size.height / region.height)
                    val w = region.width * factor; val h = region.height * factor
                    drawRect(Color.White, size = Size(w, h))
                    clipRect(0f, 0f, w, h) { drawPreview(previews, factor, Offset(region.left, region.top)) }
                    val (columns, rows) = region.pagination()
                    // Tiny subdivisions are represented by the page count, keeping preview work bounded.
                    if (columns <= 200 && rows <= 200) {
                        for (column in 1 until columns) drawLine(Color(0xFF244C9A), Offset(column * A4_PORTRAIT_WIDTH * factor, 0f), Offset(column * A4_PORTRAIT_WIDTH * factor, h))
                        for (row in 1 until rows) drawLine(Color(0xFF244C9A), Offset(0f, row * A4_PORTRAIT_HEIGHT * factor), Offset(w, row * A4_PORTRAIT_HEIGHT * factor))
                    }
                    drawRect(Color(0xFF244C9A), size = Size(w, h), style = Stroke(1f))
                }
            }
        },
        confirmButton = { Button(enabled = region != null, onClick = { region?.let(onExport) }) { Text("Choose destination") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
