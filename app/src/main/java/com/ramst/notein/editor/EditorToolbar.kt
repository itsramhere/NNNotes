package com.ramst.notein.editor

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ramst.notein.R
import com.ramst.notein.storage.PageLayout
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import com.ramst.notein.storage.PaperBackground

@Composable
internal fun EditorToolbar(
    state: EditorUiState,
    thickness: Float,
    onToolChange: (InkTool) -> Unit,
    onColorChange: (Int) -> Unit,
    onThicknessChange: (Float) -> Unit,
    onFingerWritingChange: (Boolean) -> Unit,
    onLasso: () -> Unit,
    onLockChange: (Boolean) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onPdf: () -> Unit,
    onExport: () -> Unit,
    onText: () -> Unit,
    onPhoto: () -> Unit,
    onImageFile: () -> Unit,
    onCreatePage: () -> Unit,
    onBackground: (PaperBackground) -> Unit,
    onPagePaper: () -> Unit,
    onNotebookPaper: () -> Unit,
    onSelectPage: (String) -> Unit,
    onDuplicatePage: () -> Unit,
    onDeletePage: () -> Unit,
    onLayoutChange: (PageLayout) -> Unit,
    onFit: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onPaste: () -> Unit,
    onDeleteSelection: () -> Unit,
    onEditText: () -> Unit,
    onClear: () -> Unit,
    onInsert: (InsertKind) -> Unit,
    onHeadingSelect: (ContentObject) -> Unit,
) {
    val page = state.page ?: return
    val activeTool = InkTool.fromStorage(page.activeTool)
    val ready = !state.pageBusy && !state.hasPendingInput
    val saved = ready && !state.hasUnsavedChanges
    val locked = state.notebook?.viewportLocked == true
    var showInkOptions by rememberSaveable { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showPageTypes by remember { mutableStateOf(false) }
    var insertTab by remember { mutableStateOf(false) }
    val hasContext = state.lassoEnabled || showInkOptions

    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
        .testTag("Editor toolbar"),
        shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shadowElevation = 1.dp) {
        BoxWithConstraints {
            // Keep a single toolbar even on phones; the outer strip can be swiped there.
            // Tablets reserve the right side while only the tool list scrolls if needed.
            val narrow = maxWidth < 680.dp
            val stripWidth = if (narrow) 1280.dp else maxWidth
            val contextWidth = (stripWidth * .40f).coerceIn(304.dp, 480.dp)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                Row(Modifier.width(stripWidth).height(80.dp).padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f)
                        .then(if (narrow) Modifier else Modifier.horizontalScroll(rememberScrollState()))
                        .testTag("Editor tools")
                        .semantics { contentDescription = "Drawing tools, swipe for more" },
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        listOf(InkTool.Pen, InkTool.BallPen, InkTool.Pencil, InkTool.Eraser, InkTool.Highlighter).forEach { tool ->
                            ToolbarIcon(tool.label, tool.icon(), enabled = ready,
                                selected = tool == activeTool && !state.lassoEnabled,
                                onClick = { showInkOptions = true; onToolChange(tool) })
                        }
                        ToolbarIcon("Lasso", R.drawable.ic_editor_lasso, enabled = ready,
                            selected = state.lassoEnabled, onClick = { showInkOptions = false; onLasso() })
                        ToolbarDivider()
                        ToolbarIcon("Lock", if (locked) R.drawable.ic_editor_lock else R.drawable.ic_editor_unlock,
                            description = "Lock zoom", enabled = ready, selected = locked, toggle = true,
                            onClick = { onLockChange(!locked) })
                        ToolbarIcon("Undo", R.drawable.ic_editor_undo, enabled = ready && state.canUndo, onClick = onUndo)
                        ToolbarIcon("Redo", R.drawable.ic_editor_redo, enabled = ready && state.canRedo, onClick = onRedo)
                        ToolbarDivider()
                        ToolbarIcon("PDF", R.drawable.ic_editor_pdf, description = "Import PDF",
                            enabled = saved && state.preparedPdf == null, onClick = onPdf)
                        Box {
                            ToolbarIcon("Settings", R.drawable.ic_editor_settings,
                                onClick = { showPageTypes = false; insertTab = false; showSettings = true })
                            DropdownMenu(expanded = showSettings, onDismissRequest = { showSettings = false },
                                modifier = Modifier.width(260.dp)) {
                                fun runAction(action: () -> Unit) { showSettings = false; action() }
                                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(!insertTab, onClick = { insertTab = false }, label = { Text("Settings") },
                                        modifier = Modifier.semantics { contentDescription = "Settings tab" })
                                    FilterChip(insertTab, onClick = { insertTab = true }, label = { Text("Insert") },
                                        modifier = Modifier.semantics { contentDescription = "Insert tab" })
                                }
                                HorizontalDivider()
                                if (insertTab) {
                                    InsertKind.entries.forEach { kind ->
                                        DropdownMenuItem(text = { Text(kind.label) }, enabled = ready,
                                            leadingIcon = { Icon(when (kind) {
                                                InsertKind.SHAPE -> Icons.Outlined.Category
                                                InsertKind.GRAPH -> Icons.Outlined.GridOn
                                                InsertKind.HEADING -> Icons.Outlined.Title
                                                InsertKind.TABLE -> Icons.Outlined.TableChart
                                            }, null) }, onClick = { runAction { onInsert(kind) } })
                                    }
                                    val headings = state.objects.mapNotNull { it.toContentObject() }.filter { it.headingLevel != null }
                                    if (headings.isNotEmpty()) {
                                        HorizontalDivider()
                                        Text("HEADINGS ON THIS PAGE", Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                            style = MaterialTheme.typography.labelSmall)
                                        headings.forEach { heading -> DropdownMenuItem(enabled = ready,
                                            text = { Text("H${heading.headingLevel}  ${heading.text}", maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                            onClick = { runAction { onHeadingSelect(heading) } }) }
                                    }
                                } else {
                                DropdownMenuItem(text = { Text("Add text") }, leadingIcon = { Icon(Icons.Outlined.TextFields, null) }, enabled = ready, onClick = { runAction(onText) })
                                DropdownMenuItem(text = { Text("Add photo") }, leadingIcon = { Icon(Icons.Outlined.AddPhotoAlternate, null) }, enabled = ready, onClick = { runAction(onPhoto) })
                                DropdownMenuItem(text = { Text("Image file") }, leadingIcon = { Icon(Icons.Outlined.Image, null) }, enabled = ready, onClick = { runAction(onImageFile) })
                                if (!state.isInfinite) {
                                    DropdownMenuItem(text = { Text("Add page") }, enabled = saved,
                                        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.NoteAdd, null) },
                                        onClick = { runAction(onCreatePage) })
                                    DropdownMenuItem(text = { Text("Duplicate page") }, enabled = saved,
                                        leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) }, onClick = { runAction(onDuplicatePage) })
                                    DropdownMenuItem(text = { Text("Delete page") }, enabled = saved && state.pages.size > 1,
                                        leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) }, onClick = { runAction(onDeletePage) })
                                    HorizontalDivider()
                                    Text("PAGE LAYOUT", Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    PageLayout.entries.forEach { layout ->
                                        val selected = state.notebook?.pageLayout == layout.storageValue
                                        DropdownMenuItem(text = { Text(layout.label) }, enabled = saved,
                                            modifier = Modifier.semantics { this.selected = selected },
                                            trailingIcon = { if (selected) Icon(Icons.Outlined.Check, null) },
                                            onClick = { runAction { onLayoutChange(layout) } })
                                    }
                                }
                                HorizontalDivider()
                                DropdownMenuItem(text = { Text(if (state.isInfinite) "Fit content" else "Fit page") },
                                    leadingIcon = { Icon(Icons.Outlined.FitScreen, null) }, enabled = ready && !locked,
                                    onClick = { runAction(onFit) })
                                Text("${kotlin.math.round(page.viewportScale * 100f).toInt()}% · ${if (locked) "Zoom locked" else "Zoom unlocked"}",
                                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelSmall)
                                Text((if (locked) "Zoom locked · " else "") + "Use " +
                                    (if (page.fingerWriting) "two fingers" else "one finger") +
                                    (if (state.isInfinite) " to navigate" else " to scroll vertically"),
                                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelSmall)
                                HorizontalDivider()
                                DropdownMenuItem(text = { Text("Page type") }, enabled = ready && page.pdfPageIndex == null,
                                    leadingIcon = { Icon(Icons.Outlined.GridView, null) },
                                    modifier = Modifier.semantics { stateDescription = if (showPageTypes) "Expanded" else "Collapsed" },
                                    trailingIcon = { Icon(painterResource(R.drawable.ic_editor_chevron_right), null,
                                        Modifier.size(16.dp)) }, onClick = { showPageTypes = !showPageTypes })
                                if (showPageTypes) PaperBackground.entries.forEach { background ->
                                    val name = background.name.lowercase().replaceFirstChar { it.titlecase() }
                                    DropdownMenuItem(text = { Text(name) }, modifier = Modifier.padding(start = 20.dp)
                                        .semantics { selected = page.paperBackground == background.storageValue },
                                        trailingIcon = {
                                            if (page.paperBackground == background.storageValue)
                                                Icon(painterResource(R.drawable.ic_editor_check), null, Modifier.size(18.dp))
                                        }, onClick = { runAction { onBackground(background) } })
                                }
                                DropdownMenuItem(text = { Text("Page paper") }, enabled = ready && page.pdfPageIndex == null,
                                    leadingIcon = { Icon(Icons.Outlined.Description, null) }, onClick = { runAction(onPagePaper) })
                                DropdownMenuItem(text = { Text("Notebook default") }, enabled = ready && !state.isInfinite,
                                    leadingIcon = { Icon(Icons.Outlined.AutoStories, null) }, onClick = { runAction(onNotebookPaper) })
                                HorizontalDivider()
                                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("Finger writing", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                    Switch(checked = page.fingerWriting, enabled = ready, onCheckedChange = onFingerWritingChange,
                                        modifier = Modifier.semantics { contentDescription = "Finger writing" })
                                }
                                }
                            }
                        }
                        ToolbarIcon("Export", R.drawable.ic_editor_export, enabled = saved && state.exportProgress == null, onClick = onExport)
                    }
                    if (!state.isInfinite) {
                        ToolbarDivider()
                        CompactPageNavigator(state, onSelectPage)
                    }
                    if (hasContext) {
                        ToolbarDivider()
                        Box(Modifier.width(contextWidth).testTag("Tool controls")) {
                            when {
                                state.lassoEnabled -> SelectionActions(state, onCopy, onCut, onPaste, onDeleteSelection, onEditText, onClear, scrollable = !narrow)
                                activeTool == InkTool.Eraser -> Text("Tap a stroke to erase it", Modifier.padding(horizontal = 12.dp),
                                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                else -> InkOptions(activeTool, page.brushColor, thickness, ready, onColorChange, onThicknessChange,
                                    onDismiss = { showInkOptions = false })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactPageNavigator(state: EditorUiState, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val ready = !state.pageBusy && !state.hasPendingInput && !state.hasUnsavedChanges
    Box {
        TextButton(onClick = { expanded = true }, modifier = Modifier.widthIn(min = 68.dp)
            .semantics { contentDescription = "Page navigation"; stateDescription = "Page ${state.pageNumber} of ${state.pages.size}" }) {
            Text("${state.pageNumber}/${state.pages.size}", maxLines = 1, style = MaterialTheme.typography.labelLarge)
            Icon(Icons.Outlined.ExpandMore, null, Modifier.size(16.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.width(232.dp).heightIn(max = 360.dp)) {
            state.pages.forEachIndexed { index, candidate ->
                val selected = candidate.id == state.page?.id
                DropdownMenuItem(text = { Text("Page ${index + 1}") }, enabled = ready,
                    modifier = Modifier.semantics { this.selected = selected },
                    leadingIcon = { MixedPagePreview(candidate, state.previews[candidate.id].orEmpty(),
                        candidate.pdfSourceId?.let { state.pdfSources[it]?.localPath }) },
                    trailingIcon = { if (selected) Icon(Icons.Outlined.Check, null) },
                    onClick = { expanded = false; onSelect(candidate.id) })
            }
        }
    }
}

@Composable
private fun SelectionActions(
    state: EditorUiState, onCopy: () -> Unit, onCut: () -> Unit, onPaste: () -> Unit,
    onDelete: () -> Unit, onEditText: () -> Unit, onClear: () -> Unit, scrollable: Boolean,
) {
    val ready = !state.pageBusy && !state.hasPendingInput
    val selected = state.objects.filter { it.id in state.selectedIds }
    Row(Modifier.fillMaxWidth().then(if (scrollable) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
        .semantics { contentDescription = "Content editing" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        ContextAction("Copy ${selected.size}", Icons.Outlined.ContentCopy, ready && selected.isNotEmpty(), onCopy)
        ContextAction("Cut", Icons.Outlined.ContentCut, ready && selected.isNotEmpty(), onCut)
        ContextAction("Paste", Icons.Outlined.ContentPaste, ready && state.hasSelectionClipboard, onPaste)
        ContextAction("Delete", Icons.Outlined.DeleteOutline, ready && selected.isNotEmpty(), onDelete, "Delete selection")
        if (selected.size == 1 && selected.first().type in DIALOG_CONTENT_TYPES) {
            val label = when (selected.first().type) {
                TABLE_OBJECT_TYPE -> "Edit table"; SHAPE_OBJECT_TYPE -> "Edit shape"
                GRAPH_OBJECT_TYPE -> "Edit graph"; HEADING_OBJECT_TYPE -> "Edit heading"
                else -> "Edit text"
            }
            ContextAction(label, Icons.Outlined.Edit, ready, onEditText)
        }
        ContextAction("Clear", Icons.Outlined.Deselect, ready && selected.isNotEmpty(), onClear, "Clear selection")
    }
}

@Composable
private fun ContextAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean,
    onClick: () -> Unit, description: String = label) {
    Column(Modifier.width(if (label.startsWith("Edit ")) 72.dp else 54.dp).height(54.dp).clip(RoundedCornerShape(12.dp))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics(mergeDescendants = true) { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        val tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else .38f)
        Icon(icon, null, Modifier.size(20.dp), tint = tint)
        Spacer(Modifier.height(4.dp))
        Text(label, color = tint, fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1)
    }
}

@Composable
private fun ToolbarDivider() {
    Box(Modifier.padding(horizontal = 6.dp).width(1.dp).height(24.dp)
        .background(MaterialTheme.colorScheme.outlineVariant))
}

@Composable
private fun ToolbarIcon(
    label: String,
    @DrawableRes icon: Int,
    description: String = label,
    enabled: Boolean = true,
    selected: Boolean? = null,
    toggle: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = when {
        toggle -> Modifier.toggleable(value = selected == true, enabled = enabled, role = Role.Switch,
            onValueChange = { onClick() })
        selected != null -> Modifier.selectable(selected = selected, enabled = enabled, role = Role.RadioButton,
            onClick = onClick)
        else -> Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    }
    val tint = when {
        !enabled -> colors.onSurface.copy(alpha = .38f)
        selected == true -> colors.onPrimary
        else -> colors.onSurfaceVariant
    }
    val background by animateColorAsState(if (selected == true) colors.primary else Color.Transparent, label = "Tool selection")
    Column(Modifier.width(if (label == "Highlighter") 60.dp else 48.dp).height(54.dp).clip(RoundedCornerShape(13.dp))
        .background(background)
        .then(interaction).semantics(mergeDescendants = true) { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(painterResource(icon), null, Modifier.size(22.dp), tint = tint)
        Spacer(Modifier.height(3.dp))
        Text(label, color = tint, fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun InkOptions(
    tool: InkTool,
    color: Int,
    thickness: Float,
    enabled: Boolean,
    onColorChange: (Int) -> Unit,
    onThicknessChange: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().testTag("Ink options").padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.Center) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                INK_COLORS.forEach { (name, option) ->
                    Box(Modifier.size(34.dp).clip(CircleShape)
                        .selectable(selected = color == option, enabled = enabled, role = Role.RadioButton,
                            onClick = { onColorChange(option) })
                        .semantics { contentDescription = "Ink color $name" }, contentAlignment = Alignment.Center) {
                        Box(Modifier.size(24.dp).border(if (color == option) 2.dp else 1.dp,
                            if (color == option) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            CircleShape).padding(4.dp).background(Color(option), CircleShape))
                    }
                }
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(34.dp)) {
                Icon(painterResource(R.drawable.ic_editor_close), "Close ink options", Modifier.size(16.dp))
            }
        }
        Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tool.label, modifier = Modifier.semantics { contentDescription = "${tool.label} options" },
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(value = thickness.coerceIn(tool.range), enabled = enabled, onValueChange = onThicknessChange,
                valueRange = tool.range, modifier = Modifier.weight(1f).height(32.dp)
                    .semantics { contentDescription = "${tool.label} thickness" })
            Text("%.2f".format(thickness), Modifier.width(36.dp), style = MaterialTheme.typography.labelMedium)
            ToolStrokeSample(tool, color, thickness)
        }
    }
}

@DrawableRes
private fun InkTool.icon(): Int = when (this) {
    InkTool.Pen -> R.drawable.ic_editor_ink_pen
    InkTool.BallPen -> R.drawable.ic_editor_ball_pen
    InkTool.Pencil -> R.drawable.ic_editor_pencil
    InkTool.Eraser -> R.drawable.ic_editor_eraser
    InkTool.Highlighter -> R.drawable.ic_editor_highlighter
}

private val INK_COLORS = listOf(
    "Black" to 0xFF1A1C1E.toInt(), "Blue" to 0xFF244C9A.toInt(), "Red" to 0xFFB3261E.toInt(),
    "Green" to 0xFF0A6E4F.toInt(), "Brown" to 0xFF7D5700.toInt(), "Purple" to 0xFF7948A5.toInt(),
    "Yellow" to 0xFFFFD54F.toInt(),
)
