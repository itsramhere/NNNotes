package com.ramst.notein.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ramst.notein.storage.PaperBackground
import com.ramst.notein.storage.PaperPatternWeight

internal fun PaperBackground.label() = when (this) {
    PaperBackground.BLANK -> "Blank"; PaperBackground.LINED -> "Lined"
    PaperBackground.GRID -> "Squares"; PaperBackground.DOTS -> "Dots"
}

internal fun PaperPatternWeight.label(pattern: PaperBackground) = if (pattern == PaperBackground.DOTS) {
    when (this) { PaperPatternWeight.FINE -> "Small"; PaperPatternWeight.NORMAL -> "Normal"; PaperPatternWeight.BOLD -> "Large" }
} else name.lowercase().replaceFirstChar { it.titlecase() }

@Composable
internal fun PaperSpacingControls(pattern: PaperBackground, spacing: Float, onChange: (Float) -> Unit) {
    val minimum = if (pattern == PaperBackground.LINED) 4f else 2f
    val maximum = if (pattern == PaperBackground.LINED) 12f else 10f
    var input by remember(pattern, spacing) { mutableStateOf(spacing.toString()) }
    val value = input.toFloatOrNull()
    Text("Spacing: $spacing mm")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val presets = if (pattern == PaperBackground.LINED) listOf("Narrow" to 4f, "Normal" to 7f, "Wide" to 12f)
            else listOf("Fine" to 2f, "Normal" to 5f, "Wide" to 10f)
        presets.forEach { (name, mm) -> FilterChip(selected = spacing == mm, onClick = { onChange(mm) }, label = { Text("$name ${mm.toInt()} mm") }) }
    }
    Slider(spacing.coerceIn(minimum, maximum), onChange, valueRange = minimum..maximum)
    OutlinedTextField(input, { text ->
        input = text
        text.toFloatOrNull()?.takeIf { it.isFinite() && it in minimum..maximum }?.let(onChange)
    }, label = { Text("Spacing in mm (${minimum.toInt()}–${maximum.toInt()})") },
        isError = value == null || !value.isFinite() || value !in minimum..maximum, singleLine = true)
}

internal fun parsePaperColor(value: String): Int? = value.removePrefix("#").takeIf { it.matches(Regex("[0-9a-fA-F]{6}")) }
    ?.toLongOrNull(16)?.let { (0xFF000000L or it).toInt() }
