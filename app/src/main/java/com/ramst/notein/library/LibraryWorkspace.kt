package com.ramst.notein.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ramst.notein.storage.FolderEntity
import com.ramst.notein.storage.NotebookListItem
import com.ramst.notein.storage.NotebookType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun LibraryWorkspace(
    state: LibraryUiState,
    inTrash: Boolean,
    snackbarHostState: SnackbarHostState,
    onSearch: (String) -> Unit,
    onSelectFolder: (String?) -> Unit,
    onOpenNotebook: (String) -> Unit,
    onOpenTrash: () -> Unit,
    onNavigateUp: (() -> Unit)?,
    onCreateNotebook: () -> Unit,
    onCreateFolder: () -> Unit,
    onImportPdf: () -> Unit,
    onRename: (NotebookListItem) -> Unit,
    onMove: (NotebookListItem) -> Unit,
    onTrash: (NotebookListItem) -> Unit,
    onRestore: (NotebookListItem) -> Unit,
    onPermanentDelete: (NotebookListItem) -> Unit,
    onRetry: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val focusManager = LocalFocusManager.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        Scaffold(
            containerColor = colors.background,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (!wide && !inTrash) Surface(color = colors.background) {
                    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
                        Button(onClick = onCreateNotebook, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                            shape = RoundedCornerShape(14.dp)) {
                            Icon(Icons.Outlined.Add, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp)); Text("New notebook")
                        }
                    }
                }
            },
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                if (wide) {
                    LibrarySidebar(state, inTrash, onSelectFolder, onOpenTrash,
                        onNavigateUp, onCreateNotebook, onCreateFolder)
                    VerticalDivider(color = colors.outlineVariant)
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    if (!wide) Row(
                        Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (inTrash) IconButton(onClick = { onNavigateUp?.invoke() }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to notebooks")
                        } else NoteinWordmark()
                        Spacer(Modifier.weight(1f))
                        if (!inTrash) {
                            IconButton(onClick = onImportPdf) { Icon(Icons.Outlined.UploadFile, "Import PDF") }
                            IconButton(onClick = onCreateFolder) { Icon(Icons.Outlined.CreateNewFolder, "Create folder") }
                            IconButton(onClick = onOpenTrash) { Icon(Icons.Outlined.DeleteOutline, "Open trash") }
                        }
                    }
                    Column(Modifier.fillMaxWidth().padding(horizontal = if (wide) 36.dp else 20.dp)) {
                        Spacer(Modifier.height(if (wide) 36.dp else 4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                if (wide) {
                                    Text(if (inTrash) "A LITTLE ROOM TO RECONSIDER" else "MAKE ROOM FOR IDEAS",
                                        style = MaterialTheme.typography.labelSmall, letterSpacing = 1.5.sp, color = colors.primary)
                                    Spacer(Modifier.height(8.dp))
                                }
                                Text(if (inTrash) "Trash" else "Your notebooks",
                                    style = if (wide) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.headlineMedium)
                                if (wide) {
                                    Spacer(Modifier.height(6.dp))
                                    Text(if (inTrash) "Restore a thought. Or let it go."
                                        else "A quiet place for everything on your mind.",
                                        style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                                }
                            }
                            if (wide && !inTrash) OutlinedButton(onClick = onImportPdf,
                                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)) {
                                Icon(Icons.Outlined.UploadFile, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp)); Text("Import PDF")
                            }
                        }
                        Spacer(Modifier.height(if (wide) 28.dp else 16.dp))
                        OutlinedTextField(
                            value = state.searchQuery, onValueChange = onSearch,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Search titles") },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                            leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(21.dp)) },
                            trailingIcon = {
                                if (state.searchQuery.isNotEmpty()) IconButton(onClick = { onSearch("") }) {
                                    Icon(Icons.Outlined.Close, "Clear search", Modifier.size(20.dp))
                                }
                            },
                            singleLine = true, shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedContainerColor = colors.surface,
                                focusedContainerColor = colors.surface,
                                unfocusedBorderColor = colors.outlineVariant,
                            ),
                        )
                        if (!wide && !inTrash) {
                            Spacer(Modifier.height(8.dp))
                            FolderFilters(state.folders, state.selectedFolderId, onSelectFolder)
                        }
                        Spacer(Modifier.height(if (wide) 26.dp else 4.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(when {
                                state.searchQuery.isNotBlank() -> "Search results"
                                inTrash -> "Deleted notebooks"
                                state.selectedFolderId != null -> state.folders.firstOrNull { it.id == state.selectedFolderId }?.name ?: "Notebooks"
                                else -> "All notebooks"
                            }, style = MaterialTheme.typography.titleSmall)
                            if (!state.isLoading) {
                                Spacer(Modifier.width(8.dp))
                                Surface(shape = CircleShape, color = colors.surfaceContainerHigh) {
                                    Text("${state.notebooks.size}", Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            Text("LAST EDITED", style = MaterialTheme.typography.labelSmall,
                                letterSpacing = 1.sp, color = colors.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(if (wide) 12.dp else 4.dp))
                    }
                    when {
                        state.storageErrorMessage != null -> Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
                            Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Outlined.CloudOff, null, Modifier.size(36.dp), tint = colors.onSurfaceVariant)
                                Spacer(Modifier.height(16.dp))
                                Text(state.storageErrorMessage, textAlign = TextAlign.Center)
                                TextButton(onClick = onRetry) { Text("Try again") }
                            }
                        }
                        state.isLoading -> Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) { CircularProgressIndicator() }
                        state.notebooks.isEmpty() -> EmptyLibrary(inTrash, state.searchQuery.isNotBlank(),
                            state.selectedFolderId != null, onCreateNotebook, onSearch, Modifier.weight(1f).fillMaxWidth())
                        else -> LazyVerticalGrid(
                            columns = GridCells.Adaptive(if (wide) 200.dp else 144.dp),
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentPadding = PaddingValues(start = if (wide) 36.dp else 20.dp,
                                end = if (wide) 36.dp else 20.dp, top = 4.dp, bottom = 24.dp),
                            horizontalArrangement = Arrangement.spacedBy(if (wide) 22.dp else 14.dp),
                            verticalArrangement = Arrangement.spacedBy(24.dp),
                        ) {
                            items(state.notebooks, key = { it.id }) { notebook ->
                                NotebookCard(notebook, inTrash, { onOpenNotebook(notebook.id) },
                                    { onRename(notebook) }, { onMove(notebook) }, { onTrash(notebook) },
                                    { onRestore(notebook) }, { onPermanentDelete(notebook) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NoteinWordmark() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(shape = RoundedCornerShape(11.dp), color = MaterialTheme.colorScheme.primary) {
            Icon(Icons.Outlined.EditNote, null, Modifier.padding(8.dp).size(23.dp), tint = MaterialTheme.colorScheme.onPrimary)
        }
        Text("notein", fontFamily = FontFamily.Serif, fontSize = 28.sp, letterSpacing = (-1).sp,
            color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun LibrarySidebar(
    state: LibraryUiState, inTrash: Boolean, onSelectFolder: (String?) -> Unit,
    onOpenTrash: () -> Unit, onNavigateUp: (() -> Unit)?, onCreateNotebook: () -> Unit, onCreateFolder: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.width(232.dp).fillMaxHeight().background(colors.surfaceContainerLow).padding(20.dp)) {
        Spacer(Modifier.height(16.dp)); NoteinWordmark()
        Spacer(Modifier.height(36.dp))
        Button(onClick = onCreateNotebook, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(vertical = 14.dp)) {
            Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp)); Text("New notebook")
        }
        Spacer(Modifier.height(28.dp))
        SidebarItem("All notebooks", Icons.Outlined.AutoStories, !inTrash && state.selectedFolderId == null,
            onClick = { if (inTrash) onNavigateUp?.invoke(); onSelectFolder(null) })
        Spacer(Modifier.height(4.dp))
        SidebarItem("Trash", Icons.Outlined.DeleteOutline, inTrash, "Open trash", onOpenTrash)
        Spacer(Modifier.height(28.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("FOLDERS", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                letterSpacing = 1.4.sp, color = colors.onSurfaceVariant)
            IconButton(onClick = onCreateFolder, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.CreateNewFolder, "Create folder", Modifier.size(19.dp))
            }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (state.folders.isEmpty()) item {
                Text("Keep your thoughts in order.\nCreate your first folder.", Modifier.padding(vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            items(state.folders, key = { it.id }) { folder ->
                SidebarItem(folder.name, Icons.Outlined.FolderOpen, !inTrash && state.selectedFolderId == folder.id,
                    onClick = { if (inTrash) onNavigateUp?.invoke(); onSelectFolder(folder.id) })
            }
        }
        HorizontalDivider(color = colors.outlineVariant)
        Row(Modifier.padding(top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CheckCircle, null, Modifier.size(17.dp), tint = colors.primary)
            Spacer(Modifier.width(8.dp))
            Column {
                Text("Yours, on this device", style = MaterialTheme.typography.labelMedium)
                Text("No account. Just your ideas.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SidebarItem(label: String, icon: ImageVector, selected: Boolean, description: String? = null, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(onClick = onClick, selected = selected, shape = RoundedCornerShape(12.dp),
        color = if (selected) colors.secondaryContainer else Color.Transparent,
        contentColor = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(20.dp)); Spacer(Modifier.width(12.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun FolderFilters(folders: List<FolderEntity>, selectedFolderId: String?, onSelectFolder: (String?) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = selectedFolderId == null, onClick = { onSelectFolder(null) },
            label = { Text("All notebooks") }, leadingIcon = { Icon(Icons.Outlined.AutoStories, null, Modifier.size(16.dp)) })
        folders.forEach { folder ->
            FilterChip(selected = selectedFolderId == folder.id, onClick = { onSelectFolder(folder.id) },
                label = { Text(folder.name) }, leadingIcon = { Icon(Icons.Outlined.FolderOpen, null, Modifier.size(16.dp)) })
        }
    }
}

@Composable
private fun NotebookCard(
    notebook: NotebookListItem, inTrash: Boolean, onOpen: () -> Unit, onRename: () -> Unit,
    onMove: () -> Unit, onTrash: () -> Unit, onRestore: () -> Unit, onPermanentDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val palette = remember(notebook.id) { COVER_PALETTES[(notebook.id.hashCode() and Int.MAX_VALUE) % COVER_PALETTES.size] }
    Column {
        Card(onClick = { if (!inTrash) onOpen() }, modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerLow),
            border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = .65f))) {
            BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(.94f).padding(18.dp, 16.dp, 18.dp, 14.dp), contentAlignment = Alignment.Center) {
                val compactCover = maxWidth < 140.dp
                NotebookCover(palette.first, palette.second, Modifier.fillMaxSize())
                Column(Modifier.fillMaxSize().padding(start = if (compactCover) 20.dp else 24.dp,
                    top = if (compactCover) 18.dp else 22.dp, end = 10.dp, bottom = 18.dp)) {
                    Text(when (NotebookType.fromStorage(notebook.notebookType)) {
                        NotebookType.INFINITE -> "CANVAS"
                        NotebookType.PDF -> "DOCUMENT"
                        else -> "NOTEBOOK"
                    }, fontSize = 8.sp, letterSpacing = 1.5.sp, color = Color.White.copy(alpha = .72f))
                    Spacer(Modifier.height(10.dp))
                    Text(notebook.title, fontFamily = FontFamily.Serif,
                        fontSize = if (compactCover) 16.sp else 18.sp, lineHeight = if (compactCover) 20.sp else 22.sp,
                        color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.width(22.dp).height(2.dp).background(palette.second))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(when (NotebookType.fromStorage(notebook.notebookType)) {
                    NotebookType.INFINITE -> "Infinite canvas"
                    NotebookType.PDF -> "PDF document"
                    else -> "Standard notebook"
                }, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text((notebook.folderName?.let { "$it · " } ?: "") + SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(notebook.updatedAt)),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.MoreHoriz, "Notebook actions", Modifier.size(21.dp), tint = colors.onSurfaceVariant)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (inTrash) {
                        DropdownMenuItem(text = { Text("Restore") }, onClick = { menuOpen = false; onRestore() },
                            leadingIcon = { Icon(Icons.Outlined.RestoreFromTrash, null) })
                        DropdownMenuItem(text = { Text("Delete permanently") }, onClick = { menuOpen = false; onPermanentDelete() },
                            leadingIcon = { Icon(Icons.Outlined.DeleteForever, null) })
                    } else {
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menuOpen = false; onRename() },
                            leadingIcon = { Icon(Icons.Outlined.Edit, null) })
                        DropdownMenuItem(text = { Text("Move") }, onClick = { menuOpen = false; onMove() },
                            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.DriveFileMove, null) })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Move to trash", color = colors.error) },
                            onClick = { menuOpen = false; onTrash() }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = colors.error) })
                    }
                }
            }
        }
    }
}

/** Vector artwork keeps the library crisp at every screen size, without loading image assets. */
@Composable
private fun NotebookCover(cover: Color, accent: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        drawRoundRect(Color.Black.copy(alpha = .08f), Offset(3.dp.toPx(), 5.dp.toPx()), Size(w - 2.dp.toPx(), h - 3.dp.toPx()), CornerRadius(6.dp.toPx()))
        drawRoundRect(Color(0xFFECE9DC), Offset(3.dp.toPx(), 0f), Size(w - 3.dp.toPx(), h - 3.dp.toPx()), CornerRadius(5.dp.toPx()))
        drawRoundRect(cover, size = Size(w - 3.dp.toPx(), h - 5.dp.toPx()), cornerRadius = CornerRadius(5.dp.toPx()))
        drawRect(Color.Black.copy(alpha = .12f), Offset(7.dp.toPx(), 0f), Size(4.dp.toPx(), h - 5.dp.toPx()))
        drawLine(Color.White.copy(alpha = .13f), Offset(12.dp.toPx(), 0f), Offset(12.dp.toPx(), h - 5.dp.toPx()), 1.dp.toPx())
        // A restrained, debossed arch motif, kept below the notebook title.
        repeat(5) { index ->
            val left = w * (.46f + index * .045f)
            val right = w * (.94f - index * .045f)
            val top = h * (.59f + index * .035f)
            val path = Path().apply {
                moveTo(left, h * .91f)
                lineTo(left, h * .78f)
                cubicTo(left, top, right, top, right, h * .78f)
                lineTo(right, h * .91f)
            }
            drawPath(path, accent.copy(alpha = .35f - index * .045f), style = Stroke(1.dp.toPx()))
        }
        drawRect(accent, Offset(w * .78f, 0f), Size(8.dp.toPx(), h * .12f))
    }
}

@Composable
private fun EmptyLibrary(inTrash: Boolean, searching: Boolean, inFolder: Boolean,
    onCreateNotebook: () -> Unit, onSearch: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val compact = maxHeight < 360.dp
        Column(Modifier.widthIn(max = 360.dp).verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = if (compact) 12.dp else 24.dp)
            .padding(bottom = if (compact) 16.dp else 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            if (!inTrash && !searching && !compact) {
                Box(Modifier.size(108.dp, 128.dp).padding(8.dp)) { NotebookCover(Color(0xFF355E50), Color(0xFFD6B98B), Modifier.fillMaxSize()) }
            } else Surface(shape = CircleShape, color = colors.surfaceContainerHigh) {
                Icon(when { searching -> Icons.Outlined.SearchOff; inTrash -> Icons.Outlined.DeleteOutline; else -> Icons.Outlined.EditNote }, null,
                    Modifier.padding(if (compact) 12.dp else 22.dp).size(if (compact) 24.dp else 32.dp), tint = colors.primary)
            }
            Spacer(Modifier.height(if (compact) 12.dp else 22.dp))
            Text(when { searching -> "No matching notebooks"; inTrash -> "Trash is empty"; inFolder -> "Room for a new idea"; else -> "Your next idea starts here" },
                style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Text(when { searching -> "Try a different title or clear your search."
                inTrash -> "Deleted notebooks will stay here until you remove them permanently."
                else -> "Sketch a thought, start a journal, or make something that is entirely yours." },
                style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = colors.onSurfaceVariant)
            Spacer(Modifier.height(if (compact) 12.dp else 20.dp))
            if (searching) TextButton(onClick = { onSearch("") }) { Text("Clear search") }
            else if (!inTrash && !compact) OutlinedButton(onClick = onCreateNotebook) {
                Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Create a notebook")
            }
        }
    }
}

private val COVER_PALETTES = listOf(
    Color(0xFF355E50) to Color(0xFFD6BE8C), Color(0xFF9E6652) to Color(0xFFF0D4B5),
    Color(0xFF596B83) to Color(0xFFD8D5BD), Color(0xFF7C7160) to Color(0xFFE5CF9E),
    Color(0xFF74637B) to Color(0xFFE0C4AD), Color(0xFF537B76) to Color(0xFFD5D6AE),
)
