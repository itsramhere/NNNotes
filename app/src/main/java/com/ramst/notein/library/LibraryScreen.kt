package com.ramst.notein.library

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ramst.notein.storage.FolderEntity
import com.ramst.notein.storage.NotebookListItem
import com.ramst.notein.storage.NotebookType
import com.ramst.notein.storage.PaperBackground
import com.ramst.notein.storage.PaperColor
import com.ramst.notein.storage.PaperPatternWeight
import com.ramst.notein.storage.PaperSize
import com.ramst.notein.storage.PaperTemplate
import com.ramst.notein.editor.PaperSpacingControls
import com.ramst.notein.editor.label
import com.ramst.notein.editor.parsePaperColor
import com.ramst.notein.editor.PaperTemplatePreview
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    inTrash: Boolean,
    onOpenNotebook: (String) -> Unit,
    onOpenTrash: () -> Unit,
    onNavigateUp: (() -> Unit)? = null,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<LibraryDialog?>(null) }
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importPdf(uri, onOpenNotebook)
    }

    LaunchedEffect(inTrash) { viewModel.showTrash(inTrash) }
    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(if (inTrash) "Trash" else "Notebooks") },
                navigationIcon = {
                    onNavigateUp?.let { navigateUp ->
                        IconButton(onClick = navigateUp) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to notebooks")
                        }
                    }
                },
                actions = {
                    if (!inTrash) {
                        IconButton(onClick = { dialog = LibraryDialog.CreateFolder }) {
                            Icon(Icons.Default.CreateNewFolder, "Create folder")
                        }
                        IconButton(onClick = onOpenTrash) {
                            Icon(Icons.Default.DeleteOutline, "Open trash")
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        floatingActionButton = {
            if (!inTrash) {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    androidx.compose.material3.FloatingActionButton(onClick = { pdfPicker.launch(arrayOf("application/pdf")) }) { Text("PDF") }
                    ExtendedFloatingActionButton(
                        onClick = { dialog = LibraryDialog.CreateNotebook(state.selectedFolderId) },
                        icon = { Icon(Icons.Default.Add, null) },
                        text = { Text("New notebook") },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LibraryContent(
            state = state,
            inTrash = inTrash,
            modifier = Modifier.padding(padding),
            onSearch = viewModel::updateSearch,
            onSelectFolder = viewModel::selectFolder,
            onOpenNotebook = onOpenNotebook,
            onRename = { dialog = LibraryDialog.Rename(it) },
            onMove = { dialog = LibraryDialog.Move(it) },
            onTrash = { viewModel.trash(it.id) },
            onRestore = { viewModel.restore(it.id) },
            onPermanentDelete = { dialog = LibraryDialog.PermanentDelete(it) },
            onRetry = viewModel::retryStorage,
        )
    }


    when (val activeDialog = dialog) {
        is LibraryDialog.CreateNotebook -> NewNotebookDialog(
            type = activeDialog.type,
            folders = state.folders,
            initialFolderId = activeDialog.folderId,
            onDismiss = { dialog = null },
            onTypeChanged = { dialog = activeDialog.copy(type = it) },
            onConfirm = { title, folderId, template, autoMatchingPages ->
                viewModel.createNotebook(title, folderId, activeDialog.type, template, autoMatchingPages, onOpenNotebook)
                dialog = null
            },
        )
        LibraryDialog.CreateFolder -> TextEntryDialog(
            title = "New folder",
            label = "Folder name",
            confirmLabel = "Create",
            onDismiss = { dialog = null },
            onConfirm = { name ->
                viewModel.createFolder(name)
                dialog = null
            },
        )
        is LibraryDialog.Rename -> TextEntryDialog(
            title = "Rename notebook",
            label = "Notebook title",
            initialValue = activeDialog.notebook.title,
            confirmLabel = "Save",
            onDismiss = { dialog = null },
            onConfirm = { title ->
                viewModel.rename(activeDialog.notebook.id, title)
                dialog = null
            },
        )
        is LibraryDialog.Move -> MoveNotebookDialog(
            notebook = activeDialog.notebook,
            folders = state.folders,
            onDismiss = { dialog = null },
            onMove = { folderId ->
                viewModel.move(activeDialog.notebook.id, folderId)
                dialog = null
            },
        )
        is LibraryDialog.PermanentDelete -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Permanently delete notebook?") },
            text = { Text("\"${activeDialog.notebook.title}\" and its pages will be permanently removed. This cannot be undone.") },
            confirmButton = {
                Button(onClick = {
                    viewModel.permanentlyDelete(activeDialog.notebook.id)
                    dialog = null
                }) { Text("Delete permanently") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
        null -> Unit
    }
}

@Composable
private fun LibraryContent(
    state: LibraryUiState,
    inTrash: Boolean,
    modifier: Modifier = Modifier,
    onSearch: (String) -> Unit,
    onSelectFolder: (String?) -> Unit,
    onOpenNotebook: (String) -> Unit,
    onRename: (NotebookListItem) -> Unit,
    onMove: (NotebookListItem) -> Unit,
    onTrash: (NotebookListItem) -> Unit,
    onRestore: (NotebookListItem) -> Unit,
    onPermanentDelete: (NotebookListItem) -> Unit,
    onRetry: () -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = onSearch,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            label = { Text("Search titles") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            singleLine = true,
        )
        if (!inTrash) {
            FolderFilters(
                folders = state.folders,
                selectedFolderId = state.selectedFolderId,
                onSelectFolder = onSelectFolder,
            )
        }
        HorizontalDivider()
        when {
            state.storageErrorMessage != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    Text(state.storageErrorMessage)
                    TextButton(onClick = onRetry) { Text("Try again") }
                }
            }
            state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.notebooks.isEmpty() -> EmptyLibrary(inTrash, Modifier.fillMaxSize())
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.notebooks, key = { it.id }) { notebook ->
                    NotebookCard(
                        notebook = notebook,
                        inTrash = inTrash,
                        onOpen = { onOpenNotebook(notebook.id) },
                        onRename = { onRename(notebook) },
                        onMove = { onMove(notebook) },
                        onTrash = { onTrash(notebook) },
                        onRestore = { onRestore(notebook) },
                        onPermanentDelete = { onPermanentDelete(notebook) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderFilters(
    folders: List<FolderEntity>,
    selectedFolderId: String?,
    onSelectFolder: (String?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AssistChip(
            onClick = { onSelectFolder(null) },
            label = { Text("All notebooks") },
            leadingIcon = if (selectedFolderId == null) ({ Icon(Icons.AutoMirrored.Filled.Sort, null) }) else null,
        )
        folders.forEach { folder ->
            AssistChip(
                onClick = { onSelectFolder(folder.id) },
                label = { Text(folder.name) },
                leadingIcon = if (folder.id == selectedFolderId) ({ Icon(Icons.AutoMirrored.Filled.Sort, null) }) else null,
            )
        }
    }
}

@Composable
private fun NotebookCard(
    notebook: NotebookListItem,
    inTrash: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onTrash: () -> Unit,
    onRestore: () -> Unit,
    onPermanentDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(onClick = { if (!inTrash) onOpen() }, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(notebook.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                val details = buildString {
                    if (!inTrash && notebook.folderName != null) append(notebook.folderName).append(" • ")
                    append("Modified ").append(formatDate(notebook.updatedAt))
                }
                Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "Notebook actions") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (inTrash) {
                        DropdownMenuItem(
                            text = { Text("Restore") },
                            onClick = { menuOpen = false; onRestore() },
                            leadingIcon = { Icon(Icons.Default.RestoreFromTrash, null) },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete permanently") },
                            onClick = { menuOpen = false; onPermanentDelete() },
                            leadingIcon = { Icon(Icons.Default.DeleteForever, null) },
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            onClick = { menuOpen = false; onRename() },
                            leadingIcon = { Icon(Icons.Default.Edit, null) },
                        )
                        DropdownMenuItem(
                            text = { Text("Move") },
                            onClick = { menuOpen = false; onMove() },
                            leadingIcon = { Icon(Icons.Default.DriveFileMove, null) },
                        )
                        DropdownMenuItem(
                            text = { Text("Move to trash") },
                            onClick = { menuOpen = false; onTrash() },
                            leadingIcon = { Icon(Icons.Default.DeleteOutline, null) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(inTrash: Boolean, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Text(
                if (inTrash) "Trash is empty" else "No notebooks yet",
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (inTrash) "Deleted notebooks will stay here until you remove them permanently."
                else "Create a notebook to start with a blank page.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TextEntryDialog(
    title: String,
    label: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    initialValue: String = "",
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text(label) }, singleLine = true) },
        confirmButton = { Button(onClick = { onConfirm(value) }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun NewNotebookDialog(
    type: NotebookType,
    folders: List<FolderEntity> = emptyList(),
    initialFolderId: String? = null,
    onDismiss: () -> Unit,
    onTypeChanged: (NotebookType) -> Unit,
    onConfirm: (String, String?, PaperTemplate, Boolean) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }
    var folderId by remember { mutableStateOf(initialFolderId) }
    var pattern by remember { mutableStateOf(PaperBackground.BLANK) }
    var paperSize by remember { mutableStateOf(PaperSize.A4) }
    var landscape by remember { mutableStateOf(false) }
    var spacing by remember { mutableStateOf(7f) }
    var weight by remember { mutableStateOf(PaperPatternWeight.NORMAL) }
    var color by remember { mutableStateOf(PaperColor.WHITE.argb) }
    var customColor by remember { mutableStateOf("") }
    var autoMatchingPages by remember { mutableStateOf(true) }
    val template = PaperTemplate(pattern.storageValue,
        if (landscape) paperSize.height else paperSize.width, if (landscape) paperSize.width else paperSize.height,
        spacing, weight.storageValue, color)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New notebook") },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()).testTag("Notebook configuration"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Notebook title") },
                    singleLine = true,
                )
                Text("Folder", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    AssistChip(onClick = { folderId = null }, label = { Text("No folder") })
                    folders.forEach { folder -> AssistChip(onClick = { folderId = folder.id }, label = { Text(folder.name) },
                        leadingIcon = if (folderId == folder.id) ({ Text("Selected") }) else null) }
                }
                Text("Notebook type", style = MaterialTheme.typography.labelLarge)
                listOf(NotebookType.STANDARD, NotebookType.INFINITE).forEach { option ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = type == option, onClick = { onTypeChanged(option) })
                        TextButton(onClick = { onTypeChanged(option) }) {
                            Text(if (option == NotebookType.STANDARD) "Standard pages (A4)" else "Infinite canvas")
                        }
                    }
                }
                PaperTemplatePreview(template, Modifier.fillMaxWidth().height(130.dp))
                Text("Paper layout", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PaperBackground.entries.forEach { option ->
                        AssistChip(onClick = { if (pattern != option) { pattern = option; spacing = if (option == PaperBackground.LINED) 7f else 5f } }, label = { Text(option.label()) }, leadingIcon = if (pattern == option) ({ Text("✓") }) else null)
                    }
                }
                if (pattern != PaperBackground.BLANK) {
                PaperSpacingControls(pattern, spacing) { spacing = it }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PaperPatternWeight.entries.forEach { option -> AssistChip(onClick = { weight = option }, label = { Text(option.label(pattern)) }, leadingIcon = if (weight == option) ({ Text("✓") }) else null) }
                    }
                }
                Text("Paper color", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PaperColor.entries.forEach { option -> AssistChip(onClick = { color = option.argb }, label = { Text(option.name.lowercase().replaceFirstChar { it.titlecase() }) }, leadingIcon = if (color == option.argb) ({ Text("✓") }) else null) }
                }
                OutlinedTextField(value = customColor, onValueChange = { value ->
                    customColor = value
                    parsePaperColor(value)?.let { color = it }
                }, label = { Text("Custom color #RRGGBB") }, singleLine = true)
                if (type == NotebookType.STANDARD) {
                    Text("Paper size", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PaperSize.entries.forEach { option -> AssistChip(onClick = { paperSize = option }, label = { Text(option.name) }, leadingIcon = if (paperSize == option) ({ Text("✓") }) else null) }
                    }
                    AssistChip(onClick = { landscape = !landscape }, label = { Text(if (landscape) "Landscape" else "Portrait") }, leadingIcon = { Text("↔") })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Switch(checked = autoMatchingPages, onCheckedChange = { autoMatchingPages = it })
                        Spacer(Modifier.width(8.dp)); Text("Automatically add next page")
                    }
                }
            }
        },
        confirmButton = { Button(enabled = !submitted, onClick = { if (!submitted) { submitted = true; onConfirm(title, folderId, template, autoMatchingPages) } }) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun MoveNotebookDialog(
    notebook: NotebookListItem,
    folders: List<FolderEntity>,
    onDismiss: () -> Unit,
    onMove: (String?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move \"${notebook.title}\"") },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                FilledTonalButton(onClick = { onMove(null) }, modifier = Modifier.fillMaxWidth()) {
                    Text("No folder")
                }
                folders.forEach { folder ->
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { onMove(folder.id) }, modifier = Modifier.fillMaxWidth()) {
                        Text(folder.name, modifier = Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private sealed interface LibraryDialog {
    data class CreateNotebook(
        val folderId: String?,
        val type: NotebookType = NotebookType.STANDARD,
    ) : LibraryDialog
    data object CreateFolder : LibraryDialog
    data class Rename(val notebook: NotebookListItem) : LibraryDialog
    data class Move(val notebook: NotebookListItem) : LibraryDialog
    data class PermanentDelete(val notebook: NotebookListItem) : LibraryDialog
}

private fun formatDate(timestamp: Long): String = DateFormat.getDateTimeInstance(
    DateFormat.SHORT,
    DateFormat.SHORT,
).format(Date(timestamp))
