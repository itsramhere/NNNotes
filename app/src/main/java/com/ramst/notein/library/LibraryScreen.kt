package com.ramst.notein.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ramst.notein.storage.*

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

    LibraryWorkspace(
        state = state,
        inTrash = inTrash,
        snackbarHostState = snackbarHostState,
        onSearch = viewModel::updateSearch,
        onSelectFolder = viewModel::selectFolder,
        onOpenNotebook = onOpenNotebook,
        onOpenTrash = onOpenTrash,
        onNavigateUp = onNavigateUp,
        onCreateNotebook = { dialog = LibraryDialog.CreateNotebook(state.selectedFolderId) },
        onCreateFolder = { dialog = LibraryDialog.CreateFolder },
        onImportPdf = { pdfPicker.launch(arrayOf("application/pdf")) },
        onRename = { dialog = LibraryDialog.Rename(it) },
        onMove = { dialog = LibraryDialog.Move(it) },
        onTrash = { viewModel.trash(it.id) },
        onRestore = { viewModel.restore(it.id) },
        onPermanentDelete = { dialog = LibraryDialog.PermanentDelete(it) },
        onRetry = viewModel::retryStorage,
    )

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
