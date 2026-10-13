package com.ramst.notein.editor

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
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
) {
    val page = state.page ?: return
    val activeTool = InkTool.fromStorage(page.activeTool)
    val ready = !state.pageBusy && state.pendingInk == 0
    val saved = ready && !state.hasUnsavedChanges
    val locked = state.notebook?.viewportLocked == true
    var showInkOptions by rememberSaveable { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showPageTypes by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp).testTag("Editor tools")
                .semantics { contentDescription = "Drawing tools, swipe for more" },
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf(InkTool.Pen, InkTool.BallPen, InkTool.Pencil, InkTool.Eraser, InkTool.Highlighter).forEach { tool ->
                ToolbarIcon(tool.label, tool.icon(), enabled = ready,
                    selected = tool == activeTool && !state.lassoEnabled,
                    onClick = {
                        showInkOptions = tool != InkTool.Eraser &&
                            (tool != activeTool || state.lassoEnabled || !showInkOptions)
                        onToolChange(tool)
                    })
            }
            ToolbarIcon("Lasso", R.drawable.ic_editor_lasso, enabled = ready,
                selected = state.lassoEnabled, onClick = { showInkOptions = false; onLasso() })
            ToolbarIcon("Lock", if (locked) R.drawable.ic_editor_lock else R.drawable.ic_editor_unlock,
                description = "Lock zoom", enabled = ready, selected = locked, toggle = true,
                onClick = { onLockChange(!locked) })
            ToolbarIcon("Undo", R.drawable.ic_editor_undo, enabled = ready && state.canUndo, onClick = onUndo)
            ToolbarIcon("Redo", R.drawable.ic_editor_redo, enabled = ready && state.canRedo, onClick = onRedo)
            ToolbarIcon("PDF", R.drawable.ic_editor_pdf, description = "Import PDF",
                enabled = saved && state.preparedPdf == null,
                onClick = { showInkOptions = false; onPdf() })
            Box {
                ToolbarIcon("Settings", R.drawable.ic_editor_settings,
                    onClick = { showInkOptions = false; showPageTypes = false; showSettings = true })
                DropdownMenu(expanded = showSettings, onDismissRequest = { showSettings = false },
                    modifier = Modifier.width(232.dp)) {
                    fun runAction(action: () -> Unit) { showSettings = false; action() }
                    DropdownMenuItem(text = { Text("Add text") }, enabled = ready, onClick = { runAction(onText) })
                    DropdownMenuItem(text = { Text("Add photo") }, enabled = ready, onClick = { runAction(onPhoto) })
                    DropdownMenuItem(text = { Text("Image file") }, enabled = ready, onClick = { runAction(onImageFile) })
                    DropdownMenuItem(text = { Text("Add page") }, enabled = saved && !state.isInfinite,
                        onClick = { runAction(onCreatePage) })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Page type") }, enabled = ready && page.pdfPageIndex == null,
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
                        onClick = { runAction(onPagePaper) })
                    DropdownMenuItem(text = { Text("Notebook default") }, enabled = ready && !state.isInfinite,
                        onClick = { runAction(onNotebookPaper) })
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("Finger writing", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = page.fingerWriting, enabled = ready, onCheckedChange = onFingerWritingChange,
                            modifier = Modifier.semantics { contentDescription = "Finger writing" })
                    }
                }
            }
            ToolbarIcon("Export", R.drawable.ic_editor_export, enabled = saved && state.exportProgress == null,
                onClick = { showInkOptions = false; onExport() })
        }
        if (showInkOptions && activeTool != InkTool.Eraser && !state.lassoEnabled) {
            InkOptions(activeTool, page.brushColor, thickness, ready,
                onColorChange, onThicknessChange, onDismiss = { showInkOptions = false })
        }
    }
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
        selected == true -> colors.onSecondaryContainer
        else -> colors.onSurfaceVariant
    }
    Column(Modifier.width(52.dp).height(52.dp).clip(RoundedCornerShape(12.dp))
        .background(if (selected == true) colors.secondaryContainer else Color.Transparent)
        .then(interaction).semantics(mergeDescendants = true) { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(painterResource(icon), null, Modifier.size(22.dp), tint = tint)
        Spacer(Modifier.height(3.dp))
        Text(label, color = tint, fontSize = 9.sp, lineHeight = 11.sp, maxLines = 1,
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
    Surface(Modifier.padding(horizontal = 8.dp, vertical = 2.dp).widthIn(max = 480.dp)
        .fillMaxWidth().testTag("Ink options"),
        shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${tool.label} options", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                ToolStrokeSample(tool, color, thickness)
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.ic_editor_close), "Close ink options", Modifier.size(18.dp))
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Ink", Modifier.width(38.dp), style = MaterialTheme.typography.labelMedium)
                INK_COLORS.forEach { (name, option) ->
                    Box(Modifier.size(48.dp).clip(CircleShape)
                        .selectable(selected = color == option, enabled = enabled, role = Role.RadioButton,
                            onClick = { onColorChange(option) })
                        .semantics { contentDescription = "Ink color $name" }, contentAlignment = Alignment.Center) {
                        Box(Modifier.size(28.dp).border(if (color == option) 2.dp else 1.dp,
                            if (color == option) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            CircleShape).padding(4.dp).background(Color(option), CircleShape))
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Thickness", style = MaterialTheme.typography.labelMedium)
                Slider(value = thickness.coerceIn(tool.range), enabled = enabled, onValueChange = onThicknessChange,
                    valueRange = tool.range, modifier = Modifier.weight(1f)
                        .semantics { contentDescription = "${tool.label} thickness" })
                Text("%.2f".format(thickness), Modifier.width(40.dp), style = MaterialTheme.typography.labelMedium)
            }
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
