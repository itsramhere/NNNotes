package com.ramst.notein.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.ramst.notein.storage.EditableObjectEntity
import org.json.JSONObject

internal enum class InsertKind(val label: String) { SHAPE("Shape"), GRAPH("Graph"), HEADING("Heading"), TABLE("Table") }
private val LocalDimensionValidity = compositionLocalOf<androidx.compose.runtime.snapshots.SnapshotStateMap<String, Boolean>?> { null }

@Composable
private fun InsertDialogFrame(title: String, editing: Boolean, valid: Boolean = true, onDismiss: () -> Unit,
    onSave: () -> Unit, onDelete: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    var submitted by remember { mutableStateOf(false) }
    val dimensions = remember { mutableStateMapOf<String, Boolean>() }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { CompositionLocalProvider(LocalDimensionValidity provides dimensions) {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState())
                .testTag("Insert configuration"), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
        } },
        confirmButton = { Button(enabled = valid && dimensions.values.all { it } && !submitted, onClick = {
            if (!submitted) { submitted = true; onSave() }
        }) { Text(if (editing) "Save" else "Insert") } },
        dismissButton = { Row(verticalAlignment = Alignment.CenterVertically) {
            if (onDelete != null) TextButton(onClick = { if (!submitted) { submitted = true; onDelete() } }) {
                Text("Delete table", color = MaterialTheme.colorScheme.error)
            }
            TextButton(onClick = onDismiss) { Text("Cancel") }
        } })
}

@Composable
private fun InsertPreview(type: String, json: JSONObject, modifier: Modifier = Modifier) {
    val content = remember(type, json.toString()) {
        EditableObjectEntity("preview", "preview", type, json.toString(), 0L, 0L).toContentObject()
    }
    Surface(shape = RoundedCornerShape(14.dp), color = Color.White,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Canvas(modifier.fillMaxWidth().height(160.dp).padding(12.dp).semantics { contentDescription = "Insert preview" }) {
            content ?: return@Canvas
            val factor = minOf(size.width / content.sourceBounds.right, size.height / content.sourceBounds.bottom)
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                native.save()
                native.translate((size.width - content.sourceBounds.right * factor) / 2f, (size.height - content.sourceBounds.bottom * factor) / 2f)
                native.scale(factor, factor)
                native.clipRect(0f, 0f, content.sourceBounds.right, content.sourceBounds.bottom)
                if (type == HEADING_OBJECT_TYPE) { native.translate(8f, 8f); textLayout(content).draw(native) }
                else drawStructuredContent(native, content)
                native.restore()
            }
        }
    }
}

@Composable
private fun InsertColors(color: Int, onChange: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("Green" to 0xFF28594C.toInt(), "Black" to 0xFF1A1C1E.toInt(), "Blue" to 0xFF244C9A.toInt(),
            "Red" to 0xFFB3261E.toInt(), "Purple" to 0xFF7948A5.toInt()).forEach { (label, value) ->
            FilterChip(color == value, onClick = { onChange(value) }, label = { Text(label) },
                leadingIcon = { Text("●", color = Color(value)) })
        }
    }
}

@Composable
private fun DimensionField(label: String, value: Float, range: ClosedFloatingPointRange<Float>, modifier: Modifier = Modifier,
    onChange: (Float) -> Unit) {
    fun formatted() = if (value == value.toInt().toFloat()) value.toInt().toString() else "%.1f".format(java.util.Locale.US, value)
    var input by remember { mutableStateOf(formatted()) }
    LaunchedEffect(value) { if (input.toFloatOrNull() != value) input = formatted() }
    val numeric = input.toFloatOrNull()
    val valid = numeric != null && numeric.isFinite() && numeric in range
    val dimensions = LocalDimensionValidity.current
    LaunchedEffect(valid) { dimensions?.set(label, valid) }
    DisposableEffect(label) { onDispose { dimensions?.remove(label) } }
    OutlinedTextField(input, { text ->
        input = text
        text.toFloatOrNull()?.takeIf { it.isFinite() && it in range }?.let(onChange)
    }, modifier, label = { Text(label) }, singleLine = true,
        isError = !valid,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
}

@Composable
internal fun ShapeDialog(existing: ContentObject?, onDismiss: () -> Unit, onSave: (ShapeSpec) -> Unit) {
    var shape by remember { mutableStateOf(existing?.shape ?: ShapeSpec()) }
    InsertDialogFrame(if (existing == null) "Insert shape" else "Edit shape", existing != null, onDismiss = onDismiss,
        onSave = { onSave(shape) }) {
        InsertPreview(SHAPE_OBJECT_TYPE, shape.json())
        Text("Basic shapes", style = MaterialTheme.typography.labelLarge)
        ShapeKind.entries.chunked(3).forEach { choices ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                choices.forEach { kind -> FilterChip(shape.kind == kind, onClick = {
                    shape = shape.copy(kind = kind, height = if (kind in setOf(ShapeKind.SQUARE, ShapeKind.CIRCLE)) shape.width else shape.height)
                }, label = { Text(kind.label, style = MaterialTheme.typography.labelSmall) }) }
            }
        }
        InsertColors(shape.color) { shape = shape.copy(color = it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DimensionField("Width", shape.width, 24f..2400f, Modifier.weight(1f)) { width ->
                shape = shape.copy(width = width, height = if (shape.kind in setOf(ShapeKind.SQUARE, ShapeKind.CIRCLE)) width else shape.height)
            }
            if (shape.kind !in setOf(ShapeKind.SQUARE, ShapeKind.CIRCLE)) {
                DimensionField("Height", shape.height, 24f..2400f, Modifier.weight(1f)) { shape = shape.copy(height = it) }
            }
        }
        Text("Outline width: ${shape.strokeWidth}")
        Slider(shape.strokeWidth, { shape = shape.copy(strokeWidth = it) }, valueRange = .5f..16f,
            modifier = Modifier.semantics { contentDescription = "Shape outline width" })
        if (shape.kind !in setOf(ShapeKind.LINE, ShapeKind.ARROW)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Filled shape", Modifier.weight(1f)); Switch(shape.filled, { shape = shape.copy(filled = it) })
            }
        }
        Text("Move or resize it with Lasso after inserting.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun GraphDialog(existing: ContentObject?, onDismiss: () -> Unit, onSave: (GraphSpec) -> Unit) {
    val initial = existing?.graph ?: GraphSpec()
    var xMin by remember { mutableStateOf(initial.xMin.toString()) }
    var xMax by remember { mutableStateOf(initial.xMax.toString()) }
    var yMin by remember { mutableStateOf(initial.yMin.toString()) }
    var yMax by remember { mutableStateOf(initial.yMax.toString()) }
    var xStep by remember { mutableStateOf(initial.xStep.toString()) }
    var yStep by remember { mutableStateOf(initial.yStep.toString()) }
    var grid by remember { mutableStateOf(initial.grid) }
    var labels by remember { mutableStateOf(initial.labels) }
    var width by remember { mutableStateOf(initial.width) }
    var height by remember { mutableStateOf(initial.height) }
    val candidate = runCatching { GraphSpec(xMin.toFloat(), xMax.toFloat(), yMin.toFloat(), yMax.toFloat(),
        xStep.toFloat(), yStep.toFloat(), grid, labels, width, height).also { it.json() } }.getOrNull()
    InsertDialogFrame(if (existing == null) "Insert graph" else "Edit graph", existing != null, candidate != null,
        onDismiss, onSave = { candidate?.let(onSave) }) {
        InsertPreview(GRAPH_OBJECT_TYPE, (candidate ?: initial).json())
        Text("Coordinate axes", style = MaterialTheme.typography.labelLarge)
        Text("Choose ranges that include zero. Grid steps can be fractional.", style = MaterialTheme.typography.bodySmall)
        listOf(Triple("X minimum", xMin, { v: String -> xMin = v }), Triple("X maximum", xMax, { v: String -> xMax = v }),
            Triple("Y minimum", yMin, { v: String -> yMin = v }), Triple("Y maximum", yMax, { v: String -> yMax = v }),
            Triple("X grid step", xStep, { v: String -> xStep = v }), Triple("Y grid step", yStep, { v: String -> yStep = v }))
            .chunked(2).forEach { pair -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { (label, value, change) -> OutlinedTextField(value, change, Modifier.weight(1f),
                    label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)) }
            } }
        if (candidate == null) Text("Enter increasing ranges containing zero and 1–100 intervals per axis.",
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Show grid", Modifier.weight(1f)); Switch(grid, { grid = it }, modifier = Modifier.semantics { contentDescription = "Show grid" })
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Number labels", Modifier.weight(1f)); Switch(labels, { labels = it })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DimensionField("Graph width", width, 120f..2400f, Modifier.weight(1f)) { width = it }
            DimensionField("Graph height", height, 120f..2400f, Modifier.weight(1f)) { height = it }
        }
        Text("Write on the graph with any pen tool after insertion.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun HeadingDialog(existing: ContentObject?, onDismiss: () -> Unit, onSave: (String, Int, Int) -> Unit) {
    var text by remember { mutableStateOf(existing?.text.orEmpty()) }
    var level by remember { mutableStateOf(existing?.headingLevel ?: 1) }
    var color by remember { mutableStateOf(existing?.color ?: 0xFF28594C.toInt()) }
    InsertDialogFrame(if (existing == null) "Insert heading" else "Edit heading", existing != null, text.isNotBlank(),
        onDismiss, onSave = { onSave(text, level, color) }) {
        Text("Heading level", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (1..6).forEach { number -> FilterChip(level == number, onClick = { level = number }, label = { Text("H$number") }) }
        }
        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("Heading text") }, minLines = 2)
        if (text.isNotBlank()) InsertPreview(HEADING_OBJECT_TYPE, JSONObject().put("width", 320f).put("height", 140f)
            .put("text", text).put("headingLevel", level).put("fontSize", headingSize(level)).put("bold", true).put("color", color))
        InsertColors(color) { color = it }
        Text("Saved as heading level $level and listed in this page's heading outline.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun TableIndexPicker(label: String, count: Int, value: Int, modifier: Modifier, onChange: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()
            .semantics { contentDescription = "Choose $label" }) { Text("$label ${value + 1}") }
        DropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 240.dp)) {
            repeat(count) { index -> DropdownMenuItem(text = { Text("$label ${index + 1}") },
                onClick = { expanded = false; onChange(index) }) }
        }
    }
}

@Composable
private fun TableCountField(label: String, value: Int, maximum: Int, modifier: Modifier, onChange: (Int) -> Unit) {
    var input by remember { mutableStateOf(value.toString()) }
    LaunchedEffect(value) { if (input.toIntOrNull() != value) input = value.toString() }
    val count = input.toIntOrNull()
    val valid = count != null && count in 1..maximum
    val dimensions = LocalDimensionValidity.current
    LaunchedEffect(valid) { dimensions?.set(label, valid) }
    DisposableEffect(label) { onDispose { dimensions?.remove(label) } }
    OutlinedTextField(input, { text ->
        input = text
        text.toIntOrNull()?.takeIf { it in 1..maximum }?.let(onChange)
    }, modifier, label = { Text(label) }, singleLine = true, isError = !valid,
        supportingText = { Text("1–$maximum") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
}

@Composable
internal fun TableDialog(existing: ContentObject?, onDismiss: () -> Unit, onSave: (TableSpec) -> Unit, onDelete: () -> Unit) {
    var table by remember { mutableStateOf(existing?.table ?: TableSpec()) }
    var row by remember { mutableIntStateOf(0) }
    var column by remember { mutableIntStateOf(0) }
    InsertDialogFrame(if (existing == null) "Insert table" else "Edit table", existing != null,
        onDismiss = onDismiss, onSave = { onSave(table) }, onDelete = onDelete.takeIf { existing != null }) {
        if (existing == null) {
            Text("Table size", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TableCountField("Rows", table.rows.size, 50, Modifier.weight(1f)) {
                    table = table.withSize(it, table.columns.size); row = row.coerceAtMost(table.rows.lastIndex)
                }
                TableCountField("Columns", table.columns.size, 30, Modifier.weight(1f)) {
                    table = table.withSize(table.rows.size, it); column = column.coerceAtMost(table.columns.lastIndex)
                }
            }
        }
        val json = table.json()
        val preview = remember(json.toString()) { EditableObjectEntity("table-preview", "preview", TABLE_OBJECT_TYPE, json.toString(), 0, 0).toContentObject()!! }
        Canvas(Modifier.fillMaxWidth().height(170.dp).semantics { contentDescription = "Table preview, tap a cell to edit" }
            .pointerInput(table) { detectTapGestures { point ->
                val factor = minOf(size.width / table.width, size.height / table.height)
                val x = (point.x - (size.width - table.width * factor) / 2f) / factor
                val y = (point.y - (size.height - table.height * factor) / 2f) / factor
                if (x in 0f..table.width && y in 0f..table.height) {
                    var edge = 0f
                    column = table.columns.indexOfFirst { edge += it; x <= edge }.coerceAtLeast(0)
                    edge = 0f
                    row = table.rows.indexOfFirst { edge += it; y <= edge }.coerceAtLeast(0)
                }
            } }) {
            val factor = minOf(size.width / table.width, size.height / table.height)
            val x = (size.width - table.width * factor) / 2f; val y = (size.height - table.height * factor) / 2f
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                native.save(); native.translate(x, y); native.scale(factor, factor); drawStructuredContent(native, preview)
                val paint = android.graphics.Paint().apply { color = 0xFF244C9A.toInt(); style = android.graphics.Paint.Style.STROKE; strokeWidth = 2f / factor }
                val left = table.columns.take(column).sum(); val top = table.rows.take(row).sum()
                native.drawRect(left, top, left + table.columns[column], top + table.rows[row], paint); native.restore()
            }
        }
        Text("${table.rows.size} rows × ${table.columns.size} columns", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TableIndexPicker("Row", table.rows.size, row, Modifier.weight(1f)) { row = it }
            TableIndexPicker("Column", table.columns.size, column, Modifier.weight(1f)) { column = it }
        }
        OutlinedTextField(table.cells[row][column], { table = table.cell(row, column, it) }, Modifier.fillMaxWidth(),
            label = { Text("Cell text") }, minLines = 2, supportingText = { Text("Editing row ${row + 1}, column ${column + 1}") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DimensionField("Row height", table.rows[row], 24f..600f, Modifier.weight(1f)) { table = table.resizeRow(row, it) }
            DimensionField("Column width", table.columns[column], 32f..1000f, Modifier.weight(1f)) { table = table.resizeColumn(column, it) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = table.rows.size < 50, onClick = { table = table.addRow(); row = table.rows.lastIndex }) { Text("Add row") }
            TextButton(enabled = table.rows.size > 1, onClick = { table = table.deleteRow(row); row = row.coerceAtMost(table.rows.lastIndex) }) { Text("Delete row") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = table.columns.size < 30, onClick = { table = table.addColumn(); column = table.columns.lastIndex }) { Text("Add column") }
            TextButton(enabled = table.columns.size > 1, onClick = { table = table.deleteColumn(column); column = column.coerceAtMost(table.columns.lastIndex) }) { Text("Delete column") }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Header row", Modifier.weight(1f)); Switch(table.header, { table = table.copy(header = it) })
        }
        HorizontalDivider()
        Text("Resize whole table", style = MaterialTheme.typography.labelLarge)
        DimensionField("Table width", table.width,
            table.columns.maxOf { 32f / it } * table.width..table.columns.minOf { 1000f / it } * table.width, Modifier.fillMaxWidth()) { width ->
            val resized = table.columns.map { it * width / table.width }
            if (resized.all { it in 32f..1000f }) table = table.copy(columns = resized)
        }
        DimensionField("Table height", table.height,
            table.rows.maxOf { 24f / it } * table.height..table.rows.minOf { 600f / it } * table.height, Modifier.fillMaxWidth()) { height ->
            val resized = table.rows.map { it * height / table.height }
            if (resized.all { it in 24f..600f }) table = table.copy(rows = resized)
        }
        Text("Select rows or columns above to resize or delete them. Save applies all changes together; Cancel keeps the original table.",
            style = MaterialTheme.typography.bodySmall)
    }
}
