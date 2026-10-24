package com.ramst.notein.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ramst.notein.storage.PaperBackground
import com.ramst.notein.storage.EditableObjectEntity
import com.ramst.notein.storage.PageEntity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import android.graphics.PointF
import com.ramst.notein.pdf.CanvasExportDialog
import com.ramst.notein.pdf.CanvasExportRegion
import com.ramst.notein.storage.PaperTemplate
import com.ramst.notein.storage.PageLayout

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditorScreen(
    viewModel: EditorViewModel,
    onNavigateUp: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val controller = remember { InkEditorController() }
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    var showTextDialog by remember { mutableStateOf(false) }
    var editingTextId by remember { mutableStateOf<String?>(null) }
    var canvasExportRegion by remember { mutableStateOf<CanvasExportRegion?>(null) }
    var showCanvasExport by remember { mutableStateOf(false) }
    var showPagePaper by remember { mutableStateOf(false) }
    var showNotebookPaper by remember { mutableStateOf(false) }
    var showPaperExport by remember { mutableStateOf(false) }
    var exportWholeNotebook by rememberSaveable { mutableStateOf(false) }
    val imageFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importImage(context.applicationContext, uri)
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.importImage(context.applicationContext, uri)
    }
    val notebookPdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.preparePdf(uri, controller.viewportCenter())
    }
    val pdfDestination = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) {
            val region = canvasExportRegion
            if (exportWholeNotebook) viewModel.exportNotebook(context.applicationContext, uri)
            else if (region == null) viewModel.exportSelectedPage(context.applicationContext, uri)
            else viewModel.exportCanvasRegion(context.applicationContext, region, uri)
        }
        canvasExportRegion = null
        exportWholeNotebook = false
    }
    val editText: (String) -> Unit = { id -> editingTextId = id; showTextDialog = true }

    val cannotLeave = state.hasUnsavedChanges || state.pendingInk > 0 || state.pageBusy
    val guardedNavigateUp: () -> Unit = {
        if (!cannotLeave) onNavigateUp()
    }
    BackHandler(enabled = cannotLeave) { /* Keep completed, unsaved edits available for retry. */ }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                expandedHeight = 56.dp,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = {
                    Column {
                        Text(if (state.isInfinite) "INFINITE CANVAS" else "NOTEBOOK",
                            style = MaterialTheme.typography.labelSmall, fontSize = 9.sp,
                            letterSpacing = 1.6.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(state.notebook?.title ?: "Notebook", style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = guardedNavigateUp, enabled = !cannotLeave) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to notebooks")
                    }
                },
                actions = {
                    val exportProgress = state.exportProgress
                    if (exportProgress != null) {
                        Text("Exporting ${exportProgress.completed}/${exportProgress.total}", style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = viewModel::cancelExport) { Text("Cancel") }
                    }
                    Surface(shape = CircleShape, color = if (state.hasUnsavedChanges || state.canRetry)
                        MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(5.dp).background(if (state.hasUnsavedChanges || state.canRetry)
                        MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary, CircleShape))
                    Text(
                        when (state.saveMessage) {
                            "Changes kept in memory" -> "Unsaved"
                            "Storage unavailable" -> "Unavailable"
                            "Finishing ink…" -> "Finishing…"
                            else -> state.saveMessage
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (state.hasUnsavedChanges || state.canRetry) MaterialTheme.colorScheme.onTertiaryContainer
                            else MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    }
                    }
                    Spacer(Modifier.width(16.dp))
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val page = state.page
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (page == null) {
            Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("This notebook’s page could not be opened.")
                if (state.canRetry) TextButton(onClick = viewModel::retrySave) { Text("Try again") }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (state.canRetry) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (state.hasUnsavedChanges) "Unsaved changes — keep this editor open" else "Changes saved · next page pending", Modifier.weight(1f))
                        TextButton(onClick = viewModel::retrySave) { Text("Retry save") }
                    }
                }
                EditorToolbar(
                    state = state,
                    thickness = viewModel.toolThickness(InkTool.fromStorage(page.activeTool)),
                    onToolChange = viewModel::selectTool,
                    onColorChange = viewModel::selectColor,
                    onThicknessChange = viewModel::selectThickness,
                    onFingerWritingChange = viewModel::setFingerWriting,
                    onUndo = { viewModel.undo(controller) },
                    onRedo = { viewModel.redo(controller) },
                    onLockChange = { locked -> controller.commitViewportBeforeLock(); viewModel.setViewportLocked(locked) },
                    onLasso = { viewModel.setLassoEnabled(!state.lassoEnabled) },
                    onText = { editingTextId = null; showTextDialog = true },
                    onPhoto = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onImageFile = { imageFilePicker.launch(arrayOf("image/*")) },
                    onPdf = { notebookPdfPicker.launch(arrayOf("application/pdf")) },
                    onExport = { if (state.isInfinite) showCanvasExport = true else showPaperExport = true },
                    onCreatePage = viewModel::createPage,
                    onBackground = { background ->
                        if (state.isInfinite) viewModel.updateCurrentPageTemplate(page.paperTemplate().copy(background = background.storageValue))
                        else viewModel.selectBackground(background)
                    },
                    onPagePaper = { showPagePaper = true },
                    onNotebookPaper = { showNotebookPaper = true },
                    onSelectPage = viewModel::selectPage,
                    onDuplicatePage = viewModel::duplicateCurrentPage,
                    onDeletePage = viewModel::deleteCurrentPage,
                    onLayoutChange = viewModel::setPageLayout,
                    onFit = { if (state.isInfinite) controller.fitContent() else controller.fitPage() },
                    onCopy = viewModel::copySelection,
                    onCut = viewModel::cutSelection,
                    onPaste = viewModel::pasteSelection,
                    onDeleteSelection = viewModel::deleteSelection,
                    onEditText = { state.selectedIds.singleOrNull()?.let(editText) },
                    onClear = { viewModel.selectObject(null) },
                )
                InkSurface(
                    page = page,
                    pageId = page.id,
                    records = state.strokes,
                    currentPageStrokes = { pageId -> viewModel.uiState.value.let { current ->
                        if (current.page?.id == pageId) current.strokes else emptyList()
                    } },
                    objects = state.objects,
                    selectedIds = state.selectedIds,
                    lassoEnabled = state.lassoEnabled,
                    onLasso = viewModel::selectLasso,
                    onTransform = viewModel::transformSelection,
                    onSelectObject = viewModel::selectObject,
                    onEditText = editText,
                    onContentError = viewModel::showError,
                    previews = state.previews[page.id].orEmpty(),
                    pageLayout = PageLayout.fromStorage(state.notebook?.pageLayout.orEmpty()),
                    neighbors = listOf(-1, 1).mapNotNull { direction ->
                        state.pages.getOrNull(state.pageNumber - 1 + direction)?.let { neighbor ->
                            PageNeighbor(direction, neighbor, state.pageObjects[neighbor.id].orEmpty(), neighbor.pdfSourceId?.let { state.pdfSources[it]?.localPath })
                        }
                    }.takeUnless { state.isInfinite }.orEmpty(),
                    onPageNavigate = viewModel::navigateByGesture,
                    inputEnabled = !state.pageBusy,
                    activeTool = InkTool.fromStorage(page.activeTool),
                    color = page.brushColor,
                    thickness = viewModel.toolThickness(InkTool.fromStorage(page.activeTool)),
                    fingerWriting = page.fingerWriting,
                    viewportX = page.viewportX,
                    viewportY = page.viewportY,
                    viewportScale = page.viewportScale,
                    isInfinite = state.isInfinite,
                    pdfSourcePath = page.pdfSourceId?.let { state.pdfSources[it]?.localPath },
                    paperBackground = page.paperBackground,
                    paperSpacingMm = page.paperSpacingMm,
                    paperPatternWeight = page.paperPatternWeight,
                    paperBackgroundColor = page.paperBackgroundColor,
                    pageWidth = page.pageWidth,
                    pageHeight = page.pageHeight,
                    controller = controller,
                    onStrokeCommitted = viewModel::onStrokeCommitted,
                    onStrokeErased = viewModel::onStrokeErased,
                    onViewportChanged = viewModel::updateViewport,
                    viewportLocked = state.notebook?.viewportLocked == true,
                    onViewportResized = viewModel::resizeViewport,
                    onPendingInkChanged = viewModel::onPendingInkChanged,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
    state.preparedPdf?.let { prepared ->
        com.ramst.notein.pdf.PdfImportDialog(prepared, state.pdfImportProgress != null,
            viewModel::cancelPdfImport, viewModel::confirmPdfImport)
    }
    if (state.pdfImportProgress != null && state.preparedPdf == null) {
        AlertDialog(onDismissRequest = viewModel::cancelPdfImport, title = { Text("Import PDF into this notebook") },
            text = { Text(state.pdfImportProgress.orEmpty()) }, confirmButton = {},
            dismissButton = { TextButton(onClick = viewModel::cancelPdfImport) { Text("Cancel") } })
    }
    if (showPaperExport) {
        AlertDialog(onDismissRequest = { showPaperExport = false }, title = { Text("Export PDF") },
            text = { Text("Choose the whole notebook or the current page.") },
            confirmButton = { TextButton(onClick = {
                exportWholeNotebook = true; showPaperExport = false
                pdfDestination.launch("${state.notebook?.title ?: "note"}.pdf")
            }) { Text("Whole notebook") } },
            dismissButton = { Row {
                TextButton(onClick = {
                    exportWholeNotebook = false; showPaperExport = false
                    pdfDestination.launch("${state.notebook?.title ?: "note"}.pdf")
                }) { Text("Current page") }
                TextButton(onClick = { showPaperExport = false }) { Text("Cancel") }
            } })
    }
    if (showTextDialog) {
        TextBoxDialog(
            content = state.objects.firstOrNull { it.id == editingTextId }?.toContentObject(),
            onDismiss = { showTextDialog = false },
            onSave = { text, fontSize, color, bold, italic ->
                viewModel.saveText(editingTextId, text, fontSize, color, bold, italic)
                showTextDialog = false
            },
        )
    }
    if (showCanvasExport) {
        CanvasExportDialog(
            previews = state.previews[state.page?.id].orEmpty(),
            onDismiss = { showCanvasExport = false },
            onExport = { region ->
                canvasExportRegion = region
                showCanvasExport = false
                pdfDestination.launch("${state.notebook?.title ?: "canvas"}.pdf")
            },
        )
    }
    val selectedPage = state.page
    if (showPagePaper && selectedPage != null) {
        PaperTemplateDialog(
            initial = PaperTemplate(selectedPage.paperBackground, selectedPage.pageWidth, selectedPage.pageHeight, selectedPage.paperSpacingMm, selectedPage.paperPatternWeight, selectedPage.paperBackgroundColor),
            autoMatchingPages = null,
            isInfinite = state.isInfinite,
            onDismiss = { showPagePaper = false },
            onSave = { template, _ -> viewModel.updateCurrentPageTemplate(template); showPagePaper = false },
        )
    }
    if (showNotebookPaper) {
        val notebook = state.notebook
        if (notebook != null) PaperTemplateDialog(
            initial = PaperTemplate(notebook.defaultPaperBackground, notebook.defaultPageWidth, notebook.defaultPageHeight,
                notebook.defaultSpacingMm, notebook.defaultPatternWeight, notebook.defaultBackgroundColor),
            autoMatchingPages = if (state.notebookType != com.ramst.notein.storage.NotebookType.STANDARD) null else notebook.autoMatchingPages,
            isInfinite = state.isInfinite,
            onDismiss = { showNotebookPaper = false },
            onSave = { template, automatic -> viewModel.updateNotebookTemplate(template, automatic ?: notebook.autoMatchingPages); showNotebookPaper = false },
        )
    }
}

@Composable
internal fun ToolStrokeSample(tool: InkTool, color: Int, thickness: Float) {
    val stroke = remember(tool, color, thickness) {
        val inputs = androidx.ink.strokes.MutableStrokeInputBatch()
        repeat(24) { index ->
            inputs.add(androidx.ink.brush.InputToolType.STYLUS, 2f + index * 2.5f,
                8f + kotlin.math.sin(index / 4f) * 3f, index * 8L, pressure = .3f + index / 23f * .5f)
        }
        androidx.ink.strokes.Stroke(brushFor(tool, color, thickness), inputs)
    }
    val renderer = remember { androidx.ink.rendering.android.canvas.CanvasStrokeRenderer.create() }
    Canvas(Modifier.size(64.dp, 24.dp).semantics { contentDescription = "${tool.label} stroke sample" }) {
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            val factor = size.width / 64f
            native.save()
            renderer.draw(native, stroke, android.graphics.Matrix().apply { setScale(factor, factor) })
            native.restore()
        }
    }
}

@Composable
private fun InkSurface(
    page: PageEntity,
    pageId: String,
    records: List<InkStrokeRecord>,
    currentPageStrokes: (String) -> List<InkStrokeRecord>,
    objects: List<EditableObjectEntity>,
    selectedIds: Set<String>,
    lassoEnabled: Boolean,
    onLasso: (List<PointF>) -> Unit,
    onTransform: (Float, Float, Float) -> Unit,
    onSelectObject: (String?) -> Unit,
    onEditText: (String) -> Unit,
    onContentError: (String) -> Unit,
    previews: List<PreviewStroke>,
    pageLayout: PageLayout,
    neighbors: List<PageNeighbor>,
    onPageNavigate: (Int) -> Unit,
    inputEnabled: Boolean,
    activeTool: InkTool,
    color: Int,
    thickness: Float,
    fingerWriting: Boolean,
    viewportX: Float,
    viewportY: Float,
    viewportScale: Float,
    isInfinite: Boolean,
    pdfSourcePath: String?,
    paperBackground: String,
    paperSpacingMm: Float,
    paperPatternWeight: String,
    paperBackgroundColor: Int,
    pageWidth: Float,
    pageHeight: Float,
    controller: InkEditorController,
    onStrokeCommitted: (InkStrokeRecord) -> Unit,
    onStrokeErased: (InkStrokeRecord) -> Unit,
    onViewportChanged: (Float, Float, Float) -> Unit,
    viewportLocked: Boolean,
    onViewportResized: (Float, Float) -> Unit,
    onPendingInkChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var surfaceSize by remember { mutableStateOf(Size.Zero) }
    val workspaceColor = MaterialTheme.colorScheme.surfaceContainerHigh.toArgb()
    LaunchedEffect(surfaceSize, pageId, inputEnabled) {
        if (inputEnabled && surfaceSize.width > 0f && surfaceSize.height > 0f) onViewportResized(surfaceSize.width, surfaceSize.height)
    }
    Box(modifier.fillMaxWidth().clipToBounds().background(MaterialTheme.colorScheme.surface).onSizeChanged { surfaceSize = Size(it.width.toFloat(), it.height.toFloat()) }) {
        AndroidView(
            factory = { InkCanvasView(context).also { controller.attach(it) } },
            modifier = Modifier.fillMaxSize().clipToBounds(),
            update = { canvas ->
                controller.attach(canvas)
                canvas.inputEnabled = inputEnabled
                canvas.onStrokeCommitted = onStrokeCommitted
                canvas.onStrokeErased = onStrokeErased
                canvas.onViewportChanged = onViewportChanged
                canvas.setViewportLocked(viewportLocked)
                canvas.onPendingInkChanged = onPendingInkChanged
                canvas.currentPageStrokes = currentPageStrokes
                canvas.setPageId(pageId)
                canvas.setContentObjects(objects, selectedIds, lassoEnabled, onLasso, onTransform, onSelectObject, onEditText, onContentError)
                canvas.setStoredStrokes(records)
                canvas.setDocument(isInfinite, paperBackground, pageWidth, pageHeight, paperSpacingMm, paperPatternWeight, paperBackgroundColor)
                if (!isInfinite && (canvas.background as? android.graphics.drawable.ColorDrawable)?.color != workspaceColor)
                    canvas.setBackgroundColor(workspaceColor)
                canvas.setPdfBackground(pdfSourcePath, page, onContentError)
                canvas.setEditorState(activeTool, color, thickness, fingerWriting, viewportX, viewportY, viewportScale)
                canvas.setPageNavigation(pageLayout, neighbors, onPageNavigate)
            },
        )
        if (isInfinite) InfiniteOverview(page.paperTemplate(), previews, viewportX, viewportY, viewportScale, surfaceSize,
            { point -> controller.centerOn(point.x, point.y) }, Modifier.align(Alignment.BottomEnd).padding(12.dp))
    }
}

@Composable
internal fun MixedPagePreview(page: PageEntity, previews: List<PreviewStroke>, path: String?) {
    if (page.pdfPageIndex == null) PagePreview(page, previews)
    else Box(Modifier.size(24.dp, 32.dp)) {
        AndroidView(factory = { com.ramst.notein.pdf.PdfPageRenderer(it) }, modifier = Modifier.fillMaxSize(), update = { renderer ->
            val density = renderer.resources.displayMetrics.density
            renderer.setPdf(path, page, 0f, 0f, minOf(24f * density / page.pageWidth, 32f * density / page.pageHeight))
        })
        Canvas(Modifier.fillMaxSize()) { drawPreview(previews, minOf(size.width / page.pageWidth, size.height / page.pageHeight)) }
    }
}

@Composable
private fun PagePreview(page: com.ramst.notein.storage.PageEntity, previews: List<PreviewStroke>) {
    Canvas(Modifier.size(width = 24.dp, height = 32.dp)) {
        drawPaperPreview(page.paperTemplate())
        val factor = minOf(size.width / page.pageWidth, size.height / page.pageHeight)
        translate((size.width - page.pageWidth * factor) / 2f,
            (size.height - page.pageHeight * factor) / 2f) { clipRect { drawPreview(previews, factor) } }
    }
}

@Composable
private fun InfiniteOverview(template: PaperTemplate, previews: List<PreviewStroke>, viewportX: Float, viewportY: Float,
    scale: Float, surface: Size, onNavigate: (Offset) -> Unit, modifier: Modifier = Modifier) {
    var previewSize by remember { mutableStateOf(Size.Zero) }
    val geometry = remember(previews, viewportX, viewportY, scale, surface, previewSize) {
        overviewGeometry(previews, viewportX, viewportY, scale, surface, previewSize)
    }
    Canvas(modifier.size(128.dp, 96.dp).background(Color(0xEEFFFFFF))
        .semantics { contentDescription = "Canvas overview" }
        .onSizeChanged { previewSize = Size(it.width.toFloat(), it.height.toFloat()) }
        .pointerInput(geometry) {
            detectTapGestures { point -> if (geometry.factor > 0f) onNavigate(geometry.toWorld(point)) }
        }) {
        if (geometry.factor <= 0f) return@Canvas
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.save()
            native.scale(geometry.factor, geometry.factor)
            native.translate(-geometry.world.left, -geometry.world.top)
            PaperRenderer.draw(native, template, geometry.world.left, geometry.world.top, geometry.world.right, geometry.world.bottom)
            native.restore()
        }
        drawRect(Color(0xFF6E7781), style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
        drawPreview(previews, geometry.factor, Offset(geometry.world.left, geometry.world.top))
        val visible = geometry.viewport
        drawRect(Color(0xFF244C9A), topLeft = geometry.toPreview(Offset(visible.left, visible.top)),
            size = Size((visible.right - visible.left) * geometry.factor, (visible.bottom - visible.top) * geometry.factor),
            style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
    }
}
