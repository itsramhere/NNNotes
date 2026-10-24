package com.ramst.notein.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
internal fun TextBoxDialog(
    content: ContentObject?,
    onDismiss: () -> Unit,
    onSave: (String, Float, Int, Boolean, Boolean) -> Unit,
) {
    var text by remember(content?.entity?.id) { mutableStateOf(content?.text.orEmpty()) }
    var fontSize by remember(content?.entity?.id) { mutableStateOf(content?.fontSize ?: 24f) }
    var color by remember(content?.entity?.id) { mutableStateOf(content?.color ?: 0xFF1A1C1E.toInt()) }
    var bold by remember(content?.entity?.id) { mutableStateOf(content?.bold ?: false) }
    var italic by remember(content?.entity?.id) { mutableStateOf(content?.italic ?: false) }
    val palette = listOf(0xFF1A1C1E.toInt(), 0xFF244C9A.toInt(), 0xFFB3261E.toInt(), 0xFF0A6E4F.toInt(), 0xFF7D5700.toInt())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (content == null) "Add text box" else "Edit text box") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Text") }, minLines = 3,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color(color),
                        fontWeight = if (bold) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
                        fontStyle = if (italic) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal))
                Text("Font size: ${fontSize.toInt()}")
                Slider(value = fontSize, onValueChange = { fontSize = it }, valueRange = 8f..144f)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = bold, onClick = { bold = !bold }, label = { Text("Bold") })
                    FilterChip(selected = italic, onClick = { italic = !italic }, label = { Text("Italic") })
                }
                Text("Text color")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    palette.forEachIndexed { index, value -> item {
                        FilterChip(selected = color == value, onClick = { color = value },
                            label = { Text(listOf("Black", "Blue", "Red", "Green", "Brown")[index], color = Color(value)) })
                    } }
                }
            }
        },
        confirmButton = { Button(enabled = text.isNotBlank(), onClick = { onSave(text, fontSize, color, bold, italic) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
