package com.ramst.notein.pdf

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun PdfImportDialog(prepared: PreparedPdf, busy: Boolean, onDismiss: () -> Unit, onImport: (Set<Int>) -> Unit) {
    var selected by rememberSaveable(prepared.sourceId) { mutableStateOf(prepared.dimensions.indices.toList()) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("Import PDF into this notebook") },
        text = { Column {
            Row {
                TextButton(enabled = !busy, onClick = { selected = prepared.dimensions.indices.toList() }) { Text("Select all") }
                TextButton(enabled = !busy, onClick = { selected = emptyList() }) { Text("Clear") }
            }
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(prepared.dimensions.indices.toList(), key = { it }) { index ->
                    Row {
                        Checkbox(index in selected, enabled = !busy, onCheckedChange = { checked ->
                            selected = if (checked) selected + index else selected - index
                        })
                        PdfImportThumbnail(prepared, index)
                        Text("Page ${index + 1}", Modifier.padding(8.dp))
                    }
                }
            }
        } },
        confirmButton = { TextButton(enabled = selected.isNotEmpty() && !busy, onClick = { onImport(selected.toSet()) }) { Text("Import ${selected.size} pages") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun PdfImportThumbnail(prepared: PreparedPdf, index: Int) {
    var bitmap by remember(prepared.sourceId, index) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(prepared.sourceId, index) {
        val size = prepared.dimensions[index]
        var result: android.graphics.Bitmap? = null
        try {
            withContext(Dispatchers.IO) { result = renderPdfBitmap(prepared.localPath, index, 120, (120 * size.second / size.first).toInt()) }
            bitmap = result
            result = null
        } catch (exception: kotlinx.coroutines.CancellationException) { throw exception }
        catch (_: OutOfMemoryError) { /* Retain page selection if a thumbnail cannot be allocated. */ }
        catch (_: Exception) { /* The full source was validated; keep selection usable if a preview fails. */ }
        finally { result?.recycle() }
    }
    DisposableEffect(prepared.sourceId, index) { onDispose { bitmap?.recycle(); bitmap = null } }
    val image = bitmap
    if (image != null) Image(image.asImageBitmap(), "PDF page ${index + 1}", Modifier.size(60.dp, 80.dp))
    else Box(Modifier.size(60.dp, 80.dp)) { Text("${index + 1}") }
}
