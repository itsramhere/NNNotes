package com.ramst.notein.library

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ramst.notein.editor.PaperSpacingControls
import com.ramst.notein.editor.PaperTemplatePreview
import com.ramst.notein.editor.drawPaperPreview
import com.ramst.notein.editor.label
import com.ramst.notein.editor.parsePaperColor
import com.ramst.notein.storage.*

@Composable
internal fun NewNotebookDialog(
    type: NotebookType,
    folders: List<FolderEntity> = emptyList(),
    initialFolderId: String? = null,
    onDismiss: () -> Unit,
    onTypeChanged: (NotebookType) -> Unit,
    onConfirm: (String, String?, PaperTemplate, Boolean) -> Unit,
) {
    var title by rememberSaveable { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }
    var folderId by rememberSaveable { mutableStateOf(initialFolderId) }
    var pattern by rememberSaveable { mutableStateOf(PaperBackground.BLANK) }
    var paperSize by rememberSaveable { mutableStateOf(PaperSize.A4) }
    var landscape by rememberSaveable { mutableStateOf(false) }
    var spacing by rememberSaveable { mutableStateOf(7f) }
    var weight by rememberSaveable { mutableStateOf(PaperPatternWeight.NORMAL) }
    var color by rememberSaveable { mutableStateOf(PaperColor.WHITE.argb) }
    var customColor by rememberSaveable { mutableStateOf("") }
    var showCustomColor by rememberSaveable { mutableStateOf(false) }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    var autoMatchingPages by rememberSaveable { mutableStateOf(true) }
    val template = PaperTemplate(pattern.storageValue,
        if (landscape) paperSize.height else paperSize.width,
        if (landscape) paperSize.width else paperSize.height,
        spacing, weight.storageValue, color)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // The full-window backdrop keeps outside-tap dismissal available at every dialog size.
        Box(Modifier.fillMaxSize().pointerInput(onDismiss) { detectTapGestures { onDismiss() } }
            .padding(16.dp), contentAlignment = Alignment.Center) {
            BoxWithConstraints(Modifier.widthIn(max = 960.dp).fillMaxWidth()) {
                val wide = maxWidth >= 720.dp
                // Interior taps must not reach the backdrop; children still handle their own gestures.
                Surface(Modifier.fillMaxWidth().height(maxHeight.coerceAtMost(760.dp))
                    .pointerInput(Unit) { detectTapGestures {} },
                    shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp) {
                    Column {
                        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 20.dp, bottom = 18.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                                Icon(Icons.Outlined.AutoStories, null, Modifier.padding(11.dp).size(24.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                            Column(Modifier.weight(1f)) {
                                Text("New notebook", style = MaterialTheme.typography.headlineSmall)
                                Text("A place for your next idea.", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Close new notebook") }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(Modifier.weight(1f).fillMaxWidth()) {
                            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())
                                .testTag("Notebook configuration").padding(if (wide) 24.dp else 20.dp),
                                verticalArrangement = Arrangement.spacedBy(22.dp)) {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    OutlinedTextField(title, { title = it }, label = { Text("Notebook title") },
                                        placeholder = { Text("Untitled notebook") }, singleLine = true,
                                        shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
                                    NotebookFolderPicker(folders, folderId) { folderId = it }
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    SetupHeading("Notebook type")
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        NotebookTypeCard("Standard pages", "Separate sheets in familiar sizes.", Icons.Outlined.Description,
                                            selected = type == NotebookType.STANDARD, modifier = Modifier.weight(1f),
                                            onClick = { onTypeChanged(NotebookType.STANDARD) })
                                        NotebookTypeCard("Infinite canvas", "Draw freely without page edges.", Icons.Outlined.AllInclusive,
                                            selected = type == NotebookType.INFINITE, modifier = Modifier.weight(1f),
                                            onClick = { onTypeChanged(NotebookType.INFINITE) })
                                    }
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    SetupHeading("Paper layout")
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        PaperBackground.entries.forEach { option ->
                                            PaperPatternCard(option, color, selected = pattern == option, Modifier.weight(1f)) {
                                                if (pattern != option) {
                                                    pattern = option
                                                    spacing = if (option == PaperBackground.LINED) 7f else 5f
                                                }
                                            }
                                        }
                                    }
                                }
                                if (!wide) NotebookPaperPreview(title, type, template, paperSize, landscape, compact = true)
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        SetupHeading("Paper color", Modifier.weight(1f))
                                        TextButton(onClick = { showCustomColor = !showCustomColor }) { Text("Custom") }
                                    }
                                    Row(Modifier.fillMaxWidth()) {
                                        PaperColor.entries.forEach { option ->
                                            PaperColorSwatch(option, color == option.argb, Modifier.weight(1f)) { color = option.argb }
                                        }
                                    }
                                    if (showCustomColor) OutlinedTextField(customColor, { value ->
                                        customColor = value
                                        parsePaperColor(value)?.let { color = it }
                                    }, label = { Text("Custom color #RRGGBB") }, singleLine = true,
                                        isError = customColor.isNotBlank() && parsePaperColor(customColor) == null,
                                        supportingText = { Text("Enter six hexadecimal digits, such as #F8F1E4.") },
                                        shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
                                }
                                if (type == NotebookType.STANDARD) {
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        SetupHeading("Paper size")
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            PaperSize.entries.forEach { option ->
                                                PaperSizeCard(option, paperSize == option, Modifier.weight(1f)) { paperSize = option }
                                            }
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OrientationChoice("Portrait", Icons.Outlined.CropPortrait, !landscape, Modifier.weight(1f)) { landscape = false }
                                            OrientationChoice("Landscape", Icons.Outlined.CropLandscape, landscape, Modifier.weight(1f)) { landscape = true }
                                        }
                                    }
                                }
                                if (pattern != PaperBackground.BLANK) {
                                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                        Column(Modifier.fillMaxWidth()) {
                                            TextButton(onClick = { showDetails = !showDetails },
                                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                                                Icon(Icons.Outlined.Tune, null, Modifier.size(20.dp))
                                                Spacer(Modifier.width(10.dp))
                                                Text("More paper options", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                                                Icon(if (showDetails) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                                            }
                                            if (showDetails) Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                PaperSpacingControls(pattern, spacing) { spacing = it }
                                                Text(if (pattern == PaperBackground.DOTS) "Dot size" else "Line weight",
                                                    style = MaterialTheme.typography.labelMedium)
                                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    PaperPatternWeight.entries.forEach { option ->
                                                        FilterChip(selected = weight == option, onClick = { weight = option },
                                                            label = { Text(option.label(pattern)) })
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                if (type == NotebookType.STANDARD) {
                                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                        Row(Modifier.fillMaxWidth().toggleable(autoMatchingPages, role = Role.Switch,
                                            onValueChange = { autoMatchingPages = it }).padding(16.dp),
                                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            Column(Modifier.weight(1f)) {
                                                Text("Automatically add next page", style = MaterialTheme.typography.labelLarge)
                                                Text("Keep writing when a page fills up.", style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            Switch(autoMatchingPages, onCheckedChange = null)
                                        }
                                    }
                                }
                            }
                            if (wide) {
                                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                Box(Modifier.width(300.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(24.dp)) {
                                    NotebookPaperPreview(title, type, template, paperSize, landscape, compact = false)
                                }
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                            horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = onDismiss) { Text("Cancel") }
                            Spacer(Modifier.width(12.dp))
                            Button(enabled = !submitted, modifier = Modifier.height(48.dp).widthIn(min = 128.dp),
                                onClick = { if (!submitted) { submitted = true; onConfirm(title, folderId, template, autoMatchingPages) } }) {
                                Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Create")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupHeading(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun NotebookFolderPicker(folders: List<FolderEntity>, selectedId: String?, onSelect: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .semantics { contentDescription = "Choose folder"; selected = true }) {
            Icon(Icons.Outlined.FolderOpen, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            Text(folders.firstOrNull { it.id == selectedId }?.name ?: "No folder", Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(Icons.Outlined.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded, { expanded = false }, modifier = Modifier.widthIn(min = 220.dp).heightIn(max = 300.dp)) {
            val choices = listOf(null to "No folder") + folders.map { it.id to it.name }
            choices.forEach { (id, name) ->
                DropdownMenuItem(text = { Text(name) }, trailingIcon = { if (selectedId == id) Icon(Icons.Outlined.Check, null) },
                    onClick = { onSelect(id); expanded = false })
            }
        }
    }
}

@Composable
private fun NotebookTypeCard(label: String, description: String, icon: ImageVector, selected: Boolean,
    modifier: Modifier, onClick: () -> Unit) {
    val background by animateColorAsState(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        label = "Notebook type color")
    Surface(modifier.selectable(selected, role = Role.RadioButton, onClick = onClick).semantics(mergeDescendants = true) {},
        shape = RoundedCornerShape(16.dp), color = background,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(14.dp).heightIn(min = 112.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.weight(1f))
                if (selected) Icon(Icons.Outlined.CheckCircle, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PaperPatternCard(pattern: PaperBackground, color: Int, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(14.dp))
        .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)
        .border(if (selected) 1.5.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
        .selectable(selected, role = Role.RadioButton, onClick = onClick).semantics(mergeDescendants = true) {}
        .padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(Modifier.fillMaxWidth().height(68.dp)) {
            drawPaperPreview(PaperTemplate(pattern.storageValue, 100f, 132f, 5f, PaperPatternWeight.NORMAL.storageValue, color))
        }
        Text(pattern.label(), style = MaterialTheme.typography.labelMedium, maxLines = 1)
        Box(Modifier.height(3.dp).width(20.dp).background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape))
    }
}

@Composable
private fun PaperColorSwatch(option: PaperColor, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val name = when (option) {
        PaperColor.WHITE -> "White"; PaperColor.CREAM -> "Cream"; PaperColor.PALE_GRAY -> "Gray"
        PaperColor.PALE_BLUE -> "Blue"; PaperColor.PALE_YELLOW -> "Yellow"; PaperColor.DARK_GRAY -> "Dark"
    }
    Column(modifier.heightIn(min = 64.dp).clip(RoundedCornerShape(10.dp))
        .selectable(selected, role = Role.RadioButton, onClick = onClick)
        .semantics(mergeDescendants = true) { contentDescription = "Paper color $name" },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)) {
        Box(Modifier.size(36.dp).border(if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .padding(4.dp).background(Color(option.argb), CircleShape), contentAlignment = Alignment.Center) {
            if (selected) Icon(Icons.Outlined.Check, null, Modifier.size(16.dp),
                tint = if (Color(option.argb).luminance() > .4f) Color(0xFF28594C) else Color.White)
        }
        Text(name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun PaperSizeCard(size: PaperSize, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(modifier.selectable(selected, role = Role.RadioButton, onClick = onClick).semantics(mergeDescendants = true) {},
        shape = RoundedCornerShape(12.dp), color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(vertical = 12.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(size.name, style = MaterialTheme.typography.labelLarge)
            Text(when (size) { PaperSize.A4 -> "210 × 297 mm"; PaperSize.A5 -> "148 × 210 mm"; PaperSize.LETTER -> "216 × 279 mm" },
                fontSize = 10.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun OrientationChoice(label: String, icon: ImageVector, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(modifier.selectable(selected, role = Role.RadioButton, onClick = onClick).semantics(mergeDescendants = true) {},
        shape = RoundedCornerShape(12.dp), color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun NotebookPaperPreview(title: String, type: NotebookType, template: PaperTemplate, size: PaperSize,
    landscape: Boolean, compact: Boolean) {
    val details = if (type == NotebookType.INFINITE) "Infinite canvas" else "${size.name} · ${if (landscape) "Landscape" else "Portrait"}"
    val preview = if (type == NotebookType.INFINITE) template.copy(width = 360f, height = 420f) else template
    if (compact) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PaperTemplatePreview(preview, Modifier.size(56.dp, 72.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Your paper", style = MaterialTheme.typography.labelLarge)
                    Text("${PaperBackground.fromStorage(template.background).label()} · $details",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    } else {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("PAPER PREVIEW", style = MaterialTheme.typography.labelSmall, letterSpacing = 1.8.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            BoxWithConstraints(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
                val ratio = preview.width / preview.height
                val paperWidth = minOf(maxWidth - 16.dp, maxHeight * ratio)
                PaperTemplatePreview(preview, Modifier.width(paperWidth).aspectRatio(ratio)
                    .shadow(8.dp, RoundedCornerShape(3.dp)).clip(RoundedCornerShape(3.dp)))
            }
            Text(title.trim().ifBlank { "Untitled notebook" }, style = MaterialTheme.typography.headlineSmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                Text(details, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
            }
            Text(PaperBackground.fromStorage(template.background).label() +
                if (template.background == PaperBackground.BLANK.storageValue) " paper" else " · ${template.spacingMm} mm spacing",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
