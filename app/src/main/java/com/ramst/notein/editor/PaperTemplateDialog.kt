package com.ramst.notein.editor

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ramst.notein.storage.PaperBackground
import com.ramst.notein.storage.PaperColor
import com.ramst.notein.storage.PaperPatternWeight
import com.ramst.notein.storage.PaperSize
import com.ramst.notein.storage.PaperTemplate

@Composable
internal fun PaperTemplateDialog(initial: PaperTemplate, autoMatchingPages: Boolean?, isInfinite: Boolean = false, onDismiss: () -> Unit,
    onSave: (PaperTemplate, Boolean?) -> Unit) {
    var pattern by remember { mutableStateOf(PaperBackground.fromStorage(initial.background)) }
    var size by remember { mutableStateOf(PaperSize.entries.minByOrNull { kotlin.math.abs(it.width - minOf(initial.width, initial.height)) } ?: PaperSize.A4) }
    var landscape by remember { mutableStateOf(initial.width > initial.height) }
    var spacing by remember { mutableStateOf(initial.spacingMm) }
    var weight by remember { mutableStateOf(PaperPatternWeight.fromStorage(initial.patternWeight)) }
    var color by remember { mutableStateOf(initial.backgroundColor) }
    var customColor by remember { mutableStateOf("") }
    var automatic by remember { mutableStateOf(autoMatchingPages ?: false) }
    val template = PaperTemplate(pattern.storageValue, if (isInfinite) 0f else if (landscape) size.height else size.width,
        if (isInfinite) 0f else if (landscape) size.width else size.height, spacing, weight.storageValue, color)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (autoMatchingPages == null) "Page paper" else "Notebook default paper") },
        text = { Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
            PaperTemplatePreview(template, Modifier.fillMaxWidth().height(130.dp))
            Text("Layout")
            Row(Modifier.horizontalScroll(rememberScrollState())) { PaperBackground.entries.forEach { option ->
                AssistChip(onClick = { if (pattern != option) { pattern = option; spacing = if (option == PaperBackground.LINED) 7f else 5f } }, label = { Text(option.label()) }, leadingIcon = if (pattern == option) ({ Text("✓") }) else null)
            } }
            if (pattern != PaperBackground.BLANK) {
                PaperSpacingControls(pattern, spacing) { spacing = it }
                Row(Modifier.horizontalScroll(rememberScrollState())) { PaperPatternWeight.entries.forEach { option ->
                    AssistChip(onClick = { weight = option }, label = { Text(option.label(pattern)) }, leadingIcon = if (weight == option) ({ Text("✓") }) else null)
                } }
            }
            Text("Background")
            Row(Modifier.horizontalScroll(rememberScrollState())) { PaperColor.entries.forEach { option ->
                AssistChip(onClick = { color = option.argb }, label = { Text(option.name.lowercase().replaceFirstChar { it.titlecase() }) }, leadingIcon = if (color == option.argb) ({ Text("✓") }) else null)
            } }
            OutlinedTextField(customColor, { value -> customColor = value; parsePaperColor(value)?.let { color = it } }, label = { Text("Custom color #RRGGBB") }, singleLine = true)
            if (!isInfinite) Row(Modifier.horizontalScroll(rememberScrollState())) { PaperSize.entries.forEach { option ->
                AssistChip(onClick = { size = option }, label = { Text(option.name) }, leadingIcon = if (size == option) ({ Text("✓") }) else null)
            } }
            if (!isInfinite) AssistChip(onClick = { landscape = !landscape }, label = { Text(if (landscape) "Landscape" else "Portrait") })
            if (autoMatchingPages != null) Row(Modifier.fillMaxWidth()) { Switch(automatic, { automatic = it }); Spacer(Modifier.width(8.dp)); Text("Automatically add next page") }
        } },
        confirmButton = { Button(onClick = { onSave(template, autoMatchingPages?.let { automatic }) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
