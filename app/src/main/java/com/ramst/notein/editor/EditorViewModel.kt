package com.ramst.notein.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ramst.notein.storage.EditableObjectEntity
import com.ramst.notein.storage.EditorRepository
import com.ramst.notein.storage.NotebookEntity
import com.ramst.notein.storage.NotebookType
import com.ramst.notein.storage.PageEntity
import com.ramst.notein.storage.PaperBackground
import com.ramst.notein.storage.PageLayout
import com.ramst.notein.storage.PdfDocumentEntity
import com.ramst.notein.pdf.PdfRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import android.graphics.PointF
import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.util.UUID
import com.ramst.notein.pdf.PdfExporter
import com.ramst.notein.pdf.CanvasExportRegion
import com.ramst.notein.pdf.ExportProgress

internal data class EditorUiState(
    val notebook: NotebookEntity? = null,
    val pages: List<PageEntity> = emptyList(),
    val page: PageEntity? = null,
    val strokes: List<InkStrokeRecord> = emptyList(),
    val objects: List<EditableObjectEntity> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val lassoEnabled: Boolean = false,
    val hasSelectionClipboard: Boolean = false,
    val pdfDocument: PdfDocumentEntity? = null,
    val pdfSources: Map<String, PdfDocumentEntity> = emptyMap(),
    val toolThicknesses: Map<String, Float> = emptyMap(),
    val preparedPdf: com.ramst.notein.pdf.PreparedPdf? = null,
    val pdfImportProgress: String? = null,
    val exportProgress: ExportProgress? = null,
    val isLoading: Boolean = true,
    val saveMessage: String = "Saved",
    val errorMessage: String? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val canRetry: Boolean = false,
    val hasUnsavedChanges: Boolean = false,
    val pendingInk: Int = 0,
    val navigationInProgress: Boolean = false,
    val pageBusy: Boolean = true,
    val previews: Map<String, List<PreviewStroke>> = emptyMap(),
    val pageObjects: Map<String, List<EditableObjectEntity>> = emptyMap(),
) {
    val hasPendingInput: Boolean get() = pendingInk > 0 || navigationInProgress
    val notebookType: NotebookType get() = NotebookType.fromStorage(notebook?.notebookType.orEmpty())
    val isInfinite: Boolean get() = notebookType == NotebookType.INFINITE
    val pageNumber: Int get() = (pages.indexOfFirst { it.id == page?.id } + 1).coerceAtLeast(1)
}

/** Owns one page's ink session and the notebook's durable page navigator. */
internal class EditorViewModel(
    private val notebookId: String,
    private val repository: EditorRepository,
    private val pdfRepository: PdfRepository,
    private val imageImporter: suspend (Context, Uri) -> ImportedImage = { context, uri -> ImageAssets(context).import(uri) },
) : ViewModel() {
    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState = _uiState.asStateFlow()
    private val saveRequests = Channel<Unit>(Channel.CONFLATED)
    private val saveMutex = Mutex()
    private var objectsJob: Job? = null
    private var notebookJob: Job? = null
    private var selectedPageId: String? = null
    private var revision = 0L
    private var exportJob: Job? = null
    private var importingImage = false
    private var pdfImportJob: Job? = null
    private var pdfImportTarget: PageEntity? = null
    private var pdfImportCenter = PointF()
    private var confirmingPdf = false
    private var importingPdf = false
    private var pageTransitionBusy = false
    private val pendingToolThicknesses = mutableMapOf<String, Float>()
    private var selectionClipboard: List<EditableObjectEntity> = emptyList()

    fun preparePdf(uri: Uri, center: PointF) {
        val state = _uiState.value
        if (state.pageBusy || state.hasUnsavedChanges || state.hasPendingInput || pdfImportJob?.isActive == true || state.preparedPdf != null) return
        pdfImportTarget = state.page ?: return
        pdfImportCenter = center
        importingPdf = true
        _uiState.update { it.copy(pageBusy = true, pdfImportProgress = "Copying PDF…") }
        pdfImportJob = viewModelScope.launch {
            try {
                val prepared = pdfRepository.prepare(uri) { progress -> _uiState.update { it.copy(pdfImportProgress = progress) } }
                _uiState.update { it.copy(preparedPdf = prepared, pdfImportProgress = null) }
            } catch (exception: CancellationException) { throw exception }
            catch (exception: Exception) { showError(exception.message ?: "Could not import this PDF.") }
            finally {
                importingPdf = false
                _uiState.update { it.copy(pageBusy = importingImage, pdfImportProgress = null) }
            }
        }
    }

    fun cancelPdfImport() {
        if (confirmingPdf) return
        pdfImportJob?.cancel()
        val prepared = _uiState.value.preparedPdf
        _uiState.update { it.copy(preparedPdf = null, pageBusy = importingPdf || importingImage, pdfImportProgress = null) }
        if (prepared != null) viewModelScope.launch { pdfRepository.discard(prepared) }
    }

    override fun onCleared() {
        val prepared = _uiState.value.preparedPdf
        if (prepared != null) kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO).launch {
            pdfRepository.discard(prepared)
        }
        super.onCleared()
    }

    fun confirmPdfImport(selected: Set<Int>) {
        val prepared = _uiState.value.preparedPdf ?: return
        val target = pdfImportTarget ?: return
        if (confirmingPdf || selected.isEmpty() || _uiState.value.hasUnsavedChanges || _uiState.value.hasPendingInput) return
        confirmingPdf = true
        _uiState.update { it.copy(pageBusy = true, pdfImportProgress = "Inserting PDF pages…") }
        pdfImportJob = viewModelScope.launch {
            try {
                pdfRepository.insert(prepared, notebookId, target, selected, _uiState.value.isInfinite, pdfImportCenter.x, pdfImportCenter.y)
                val sources = pdfRepository.documents(notebookId).associateBy { it.sourceId }
                _uiState.update { it.copy(preparedPdf = null, pdfSources = sources) }
            } catch (exception: CancellationException) { throw exception }
            catch (exception: Exception) { showError(exception.message ?: "Could not insert PDF. Your existing content is retained.") }
            finally {
                confirmingPdf = false
                _uiState.update { it.copy(pageBusy = false, pdfImportProgress = null) }
            }
        }
    }
    private val undoStack = ArrayDeque<SessionAction>()
    private val redoStack = ArrayDeque<SessionAction>()
    private var matchingPageRetryId: String? = null

    init {
        loadNotebook()
        viewModelScope.launch {
            repository.observeToolPreferences().collect { preferences ->
                val saved = preferences.associate { preference -> preference.tool to preference.thickness }
                pendingToolThicknesses.entries.removeAll { (tool, value) -> saved[tool] == value }
                _uiState.update { it.copy(toolThicknesses = saved + pendingToolThicknesses) }
            }
        }
        viewModelScope.launch { for (ignored in saveRequests) saveCurrentPageUntilClean() }
        viewModelScope.launch {
            var previewCache = emptyMap<String, Pair<EditableObjectEntity, PreviewStroke?>>()
            repository.observeNotebookObjects(notebookId).distinctUntilChanged().collectLatest { objects ->
                val updatedCache = withContext(Dispatchers.Default) {
                    objects.associate { entity ->
                        entity.id to (previewCache[entity.id]?.takeIf { it.first == entity }
                            ?: (entity to entity.toContentPreview()))
                    }
                }
                previewCache = updatedCache
                val byPage = objects.groupBy { it.pageId }
                val previews = byPage.mapValues { (_, entities) -> entities.mapNotNull { updatedCache[it.id]?.second } }
                _uiState.update { it.copy(previews = previews, pageObjects = byPage) }
            }
        }
    }

    private fun loadNotebook() {
        _uiState.update { it.copy(isLoading = true, canRetry = false, errorMessage = null) }
        notebookJob?.cancel()
        notebookJob = viewModelScope.launch {
            try {
                val notebook = requireNotNull(repository.notebook(notebookId)) { "The notebook no longer exists." }
                pdfRepository.cleanupUnusedSources(notebookId)
                val preferences = repository.toolPreferences().associate { it.tool to it.thickness }
                selectedPageId = selectedPageId ?: notebook.selectedPageId
                repository.observePages(notebookId).collect { pages ->
                    val currentNotebook = repository.notebook(notebookId) ?: notebook
                    val selected = pages.firstOrNull { it.id == (selectedPageId ?: _uiState.value.page?.id) } ?: pages.firstOrNull()
                    val sources = pdfRepository.documents(notebookId).associateBy { it.sourceId }
                    val pdfDocument = selected?.pdfSourceId?.let { sources[it] }
                    val pageChanged = selected?.id != _uiState.value.page?.id
                    selectedPageId = selected?.id
                    _uiState.update { state -> state.copy(
                        notebook = currentNotebook,
                        pdfDocument = pdfDocument,
                        pdfSources = sources,
                        toolThicknesses = if (state.toolThicknesses.isEmpty()) preferences else state.toolThicknesses,
                        pages = pages,
                        page = (if (pageChanged) selected else state.page)?.let { notebookZoom(it, currentNotebook) },
                        isLoading = false,
                    ) }
                    if (selected != null && (pageChanged || objectsJob == null)) {
                        _uiState.update { it.copy(pageBusy = true) }
                        observePageObjects(selected.id)
                    }
                }
            } catch (exception: CancellationException) { throw exception }
            catch (exception: Exception) {
                _uiState.update { it.copy(isLoading = false, saveMessage = "Storage unavailable", canRetry = true,
                    errorMessage = "Could not open this notebook. ${exception.message ?: "Please try again."}") }
            }
        }
    }

    private fun observePageObjects(pageId: String, initialObjects: List<EditableObjectEntity> = emptyList()) {
        objectsJob?.cancel()
        undoStack.clear(); redoStack.clear(); publishHistory()
        _uiState.update { it.copy(strokes = initialObjects.mapNotNull { entity -> entity.toInkStrokeRecord() },
            objects = initialObjects, selectedIds = emptySet(), pendingInk = 0, navigationInProgress = false) }
        objectsJob = viewModelScope.launch {
            try {
                var loaded = false
                repository.observeObjects(pageId).distinctUntilChanged().collect { emission ->
                    val state = _uiState.value
                    val supported = emission.filter { it.type in EDITABLE_CONTENT_TYPES }
                    if (loaded && (state.hasUnsavedChanges || supported == state.objects)) return@collect
                    val loadingRevision = revision
                    // A Room emission can be queued behind several newer saves. Read the latest
                    // committed snapshot after the writer finishes instead of replaying old ink.
                    val objects = if (!loaded) supported else saveMutex.withLock {
                        repository.pageObjects(pageId).filter { it.type in EDITABLE_CONTENT_TYPES }
                    }
                    val records = objects.mapNotNull(EditableObjectEntity::toInkStrokeRecord)
                    withContext(Dispatchers.Default) {
                        check(records.all { it.decode() != null }) { "Saved ink could not be decoded; no data was changed." }
                        check(objects.all { it.toContentObject() != null }) {
                            "Saved content could not be decoded; no data was changed."
                        }
                    }
                    if (_uiState.value.page?.id == pageId && revision == loadingRevision && !_uiState.value.hasUnsavedChanges) {
                        loaded = true
                        _uiState.update { it.copy(strokes = records, objects = objects,
                            pageBusy = importingImage || importingPdf || confirmingPdf || pageTransitionBusy) }
                    }
                }
            } catch (exception: CancellationException) { throw exception }
            catch (exception: Exception) { _uiState.update { it.copy(canRetry = true, pageBusy = true, errorMessage = "Could not load this page. Please try again.") } }
        }
    }

    fun retrySave() {
        if (_uiState.value.notebook == null && !_uiState.value.isLoading) loadNotebook()
        else if (_uiState.value.hasUnsavedChanges) saveRequests.trySend(Unit)
        else if (matchingPageRetryId != null) matchingPageRetryId?.let { pageId ->
            viewModelScope.launch {
                try {
                    repository.retryMatchingPageAppend(pageId)
                    matchingPageRetryId = null
                    _uiState.update { it.copy(canRetry = false, errorMessage = null, saveMessage = "Saved") }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Exception) {
                    _uiState.update { it.copy(canRetry = true,
                        errorMessage = "Your changes are saved, but the next matching page could not be added. Tap Retry save to try again.") }
                }
            }
        }
        else if (_uiState.value.pageBusy) _uiState.value.page?.let { observePageObjects(it.id) }
    }

    fun selectPage(pageId: String) {
        val target = _uiState.value.pages.firstOrNull { it.id == pageId } ?: return
        if (_uiState.value.hasUnsavedChanges || _uiState.value.hasPendingInput || _uiState.value.pageBusy) {
            _uiState.update { it.copy(errorMessage = "Saving the current page. Select another page once saving finishes.") }
            return
        }
        if (target.id == _uiState.value.page?.id) return
        pageTransitionBusy = true
        _uiState.update { it.copy(pageBusy = true) }
        viewModelScope.launch {
            try {
                repository.selectPage(notebookId, target.id)
                selectedPageId = target.id
                _uiState.update { it.copy(page = notebookZoom(target, it.notebook), strokes = emptyList(), canRetry = false) }
                pageTransitionBusy = false
                observePageObjects(target.id)
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                pageTransitionBusy = false
                _uiState.update { it.copy(pageBusy = false, errorMessage = "Could not save the page selection. Please try again.") }
            }
        }
    }

    fun previousPage() = movePage(-1)
    fun nextPage() = movePage(1)
    private fun movePage(offset: Int) {
        val state = _uiState.value
        selectPage(state.pages.getOrNull(state.pages.indexOfFirst { it.id == state.page?.id } + offset)?.id ?: return)
    }

    /** A completed page gesture first commits the outgoing scroll and edits. */
    fun navigateByGesture(offset: Int) {
        val state = _uiState.value
        if (state.isInfinite || state.pageBusy || state.hasPendingInput || offset !in listOf(-1, 1)) return
        val target = state.pages.getOrNull(state.pageNumber - 1 + offset) ?: return
        pageTransitionBusy = true
        _uiState.update { it.copy(pageBusy = true) }
        viewModelScope.launch {
            try {
                saveCurrentPageUntilClean()
                if (_uiState.value.hasUnsavedChanges) { pageTransitionBusy = false; _uiState.update { it.copy(pageBusy = false) }; return@launch }
                repository.selectPage(notebookId, target.id)
                selectedPageId = target.id
                var destination = notebookZoom(target, _uiState.value.notebook)
                val frame = state.page
                if (frame != null && frame.viewportWidth > 0f && frame.viewportHeight > 0f) {
                    destination = if (destination.viewportWidth == 0f && destination.viewportHeight == 0f &&
                        _uiState.value.notebook?.viewportLocked != true && destination.viewportScale == 1f)
                        destination.fitNewPaper(frame.viewportWidth, frame.viewportHeight)
                    else destination.copy(viewportWidth = frame.viewportWidth, viewportHeight = frame.viewportHeight)
                    destination = destination.copy(viewportY = if (offset > 0) 0f else frame.viewportHeight - destination.pageHeight * destination.viewportScale).boundedOnPaper()
                    repository.updateEditorState(destination)
                }
                _uiState.update { it.copy(page = destination, strokes = emptyList(), canRetry = false) }
                pageTransitionBusy = false
                observePageObjects(target.id)
            } catch (exception: CancellationException) { throw exception }
            catch (_: Exception) { pageTransitionBusy = false; _uiState.update { it.copy(pageBusy = false, errorMessage = "Could not change pages. Your current page is retained.") } }
        }
    }

    /** Rebase a continuous strip on the visible page, preserving its exact screen position. */
    fun navigateByScroll(position: PageScrollPosition) {
        val state = _uiState.value
        if (state.isInfinite || state.pageBusy || state.hasPendingInput ||
            PageLayout.fromStorage(state.notebook?.pageLayout.orEmpty()) != PageLayout.VERTICAL ||
            !position.viewportY.isFinite() || !position.horizontalOffset.isFinite() || !position.scale.isFinite() || position.scale !in MIN_ZOOM..MAX_ZOOM) return
        val target = state.pages.firstOrNull { it.id == position.pageId } ?: return
        val outgoing = state.page ?: return
        if (target.id == outgoing.id) return
        pageTransitionBusy = true
        _uiState.update { it.copy(pageBusy = true) }
        viewModelScope.launch {
            try {
                saveCurrentPageUntilClean()
                if (_uiState.value.hasUnsavedChanges) {
                    pageTransitionBusy = false
                    _uiState.update { it.copy(pageBusy = false) }
                    return@launch
                }
                val objects = repository.pageObjects(target.id).filter { it.type in EDITABLE_CONTENT_TYPES }
                withContext(Dispatchers.Default) {
                    check(objects.all { it.toContentObject() != null }) { "Saved content could not be decoded." }
                }
                val scale = state.notebook?.lockedZoomScale?.takeIf { state.notebook.viewportLocked } ?: position.scale
                val destination = target.copy(viewportX = (outgoing.viewportWidth - target.pageWidth * scale) / 2f + position.horizontalOffset,
                    viewportY = position.viewportY, viewportScale = scale,
                    viewportWidth = outgoing.viewportWidth, viewportHeight = outgoing.viewportHeight)
                repository.updateEditorState(destination)
                repository.selectPage(notebookId, target.id)
                selectedPageId = target.id
                _uiState.update { it.copy(page = destination, strokes = objects.mapNotNull { entity -> entity.toInkStrokeRecord() },
                    objects = objects, selectedIds = emptySet(), canRetry = false,
                    pageObjects = it.pageObjects + (outgoing.id to state.objects)) }
                pageTransitionBusy = false
                observePageObjects(target.id, objects)
            } catch (exception: CancellationException) { throw exception }
            catch (_: Exception) {
                pageTransitionBusy = false
                _uiState.update { it.copy(pageBusy = false, errorMessage = "Could not change pages. Your current page is retained.") }
            }
        }
    }

    private fun paperViewport(page: PageEntity, notebook: NotebookEntity? = _uiState.value.notebook): PageEntity {
        if (page.viewportWidth <= 0f || page.viewportHeight <= 0f) return page
        return if (PageLayout.fromStorage(notebook?.pageLayout.orEmpty()) == PageLayout.VERTICAL) {
            val stripWidth = maxOf(page.pageWidth, _uiState.value.pages.maxOfOrNull { it.pageWidth } ?: page.pageWidth)
            page.copy(viewportX = boundedPaperX(page.viewportX, page.viewportScale, page.viewportWidth, page.pageWidth, stripWidth))
        } else page.boundedOnPaper()
    }

    private fun notebookZoom(page: PageEntity, notebook: NotebookEntity?): PageEntity {
        val scale = notebook?.lockedZoomScale?.takeIf { notebook.viewportLocked } ?: return page
        val updated = page.copy(viewportScale = scale)
        return if (notebook.notebookType == NotebookType.INFINITE.storageValue) updated else paperViewport(updated, notebook)
    }

    fun setPageLayout(layout: PageLayout) {
        if (_uiState.value.pageBusy || _uiState.value.hasPendingInput) return
        viewModelScope.launch {
            try {
                repository.setPageLayout(notebookId, layout)
                _uiState.update { it.copy(notebook = it.notebook?.copy(pageLayout = layout.storageValue)) }
            } catch (exception: CancellationException) { throw exception }
            catch (_: Exception) { showError("Could not save the page layout. Please try again.") }
        }
    }

    fun createPage() = runPageChange { type -> selectedPageId = repository.createPage(notebookId, type) }
    fun duplicateCurrentPage() {
        val sourceId = _uiState.value.page?.id ?: return
        runPageChange { selectedPageId = repository.duplicatePage(sourceId) }
    }
    fun deleteCurrentPage() {
        val state = _uiState.value
        val current = state.page ?: return
        if (state.pages.size <= 1) { _uiState.update { it.copy(errorMessage = "A notebook must keep at least one page.") }; return }
        val fallback = state.pages.firstOrNull { it.id != current.id }?.id
        runPageChange { repository.deletePage(current.id, notebookId); selectedPageId = fallback }
    }
    fun moveCurrentPage(direction: Int) {
        val state = _uiState.value
        val index = state.pages.indexOfFirst { it.id == state.page?.id }
        if (index < 0 || index + direction !in state.pages.indices) return
        val reordered = state.pages.toMutableList().also { it.add(index + direction, it.removeAt(index)) }
        runPageChange { repository.reorderPages(notebookId, reordered.map(PageEntity::id)) }
    }
    fun selectBackground(background: PaperBackground) {
        val page = _uiState.value.page ?: return
        if (_uiState.value.isInfinite || _uiState.value.pageBusy || _uiState.value.hasPendingInput) return
        _uiState.update { it.copy(pageBusy = true) }
        viewModelScope.launch {
            try {
                repository.updateBackground(page.id, background)
                _uiState.update { state -> state.copy(pageBusy = false, page = state.page?.takeIf { it.id == page.id }?.copy(paperBackground = background.storageValue) ?: state.page) }
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                _uiState.update { it.copy(pageBusy = false, errorMessage = "Could not change the paper background. Please try again.") }
            }
        }
    }
    fun updateCurrentPageTemplate(template: com.ramst.notein.storage.PaperTemplate) {
        val page = _uiState.value.page ?: return
        if (page.pdfPageIndex != null || _uiState.value.pageBusy || _uiState.value.hasPendingInput) return
        viewModelScope.launch {
            try {
                repository.updatePageTemplate(page.id, template)
                _uiState.update { state -> state.copy(page = state.page?.copy(paperBackground = template.background,
                    pageWidth = template.width, pageHeight = template.height, paperSpacingMm = template.spacingMm,
                    paperPatternWeight = template.patternWeight, paperBackgroundColor = template.backgroundColor)) }
            } catch (exception: Exception) { if (exception is CancellationException) throw exception; showError("Could not update this page's paper. Please try again.") }
        }
    }
    fun updateNotebookTemplate(template: com.ramst.notein.storage.PaperTemplate, autoMatchingPages: Boolean) {
        if (_uiState.value.isInfinite) return
        viewModelScope.launch {
            try {
                repository.updateNotebookTemplate(notebookId, template, autoMatchingPages)
                _uiState.update { state -> state.copy(notebook = state.notebook?.copy(defaultPaperBackground = template.background,
                    defaultPageWidth = template.width, defaultPageHeight = template.height, defaultSpacingMm = template.spacingMm,
                    defaultPatternWeight = template.patternWeight, defaultBackgroundColor = template.backgroundColor, autoMatchingPages = autoMatchingPages)) }
            } catch (exception: Exception) { if (exception is CancellationException) throw exception; showError("Could not update the notebook default paper. Please try again.") }
        }
    }

    fun selectTool(tool: InkTool) {
        if (_uiState.value.hasPendingInput || _uiState.value.pageBusy) return
        _uiState.update { it.copy(lassoEnabled = false, selectedIds = emptySet()) }
        updatePageState { it.copy(activeTool = tool.storageValue, brushSize = toolThickness(tool)) }
    }
    fun toolThickness(tool: InkTool): Float = if (tool == InkTool.Eraser) tool.defaultSize
        else _uiState.value.toolThicknesses[tool.storageValue] ?: tool.defaultSize

    fun setViewportLocked(locked: Boolean) {
        if (_uiState.value.pageBusy || _uiState.value.hasPendingInput) return
        pageTransitionBusy = true
        _uiState.update { it.copy(pageBusy = true) }
        viewModelScope.launch {
            try {
                saveCurrentPageUntilClean()
                if (_uiState.value.hasUnsavedChanges) { pageTransitionBusy = false; _uiState.update { it.copy(pageBusy = false) }; return@launch }
                val scale = _uiState.value.page?.viewportScale ?: 1f
                repository.setViewportLocked(notebookId, locked, scale)
                pageTransitionBusy = false
                _uiState.update {
                    val notebook = it.notebook?.copy(viewportLocked = locked, lockedZoomScale = scale.takeIf { locked })
                    it.copy(notebook = notebook, page = it.page?.let { page -> notebookZoom(page, notebook) }, pageBusy = false)
                }
            } catch (exception: CancellationException) { throw exception }
            catch (_: Exception) { pageTransitionBusy = false; _uiState.update { it.copy(pageBusy = false) }; showError("Could not save the zoom lock. Please try again.") }
        }
    }
    fun setLassoEnabled(enabled: Boolean) {
        if (_uiState.value.pageBusy || _uiState.value.hasPendingInput) return
        _uiState.update { it.copy(lassoEnabled = enabled, selectedIds = emptySet()) }
    }
    fun selectLasso(polygon: List<PointF>) {
        if (!canEditContent()) return
        val selected = _uiState.value.objects.filter { entity ->
            contentIntersectsLasso(entity, polygon)
        }.map { it.id }.toSet()
        _uiState.update { it.copy(selectedIds = selected) }
    }
    fun selectObject(id: String?) { _uiState.update { it.copy(selectedIds = id?.let { value -> setOf(value) } ?: emptySet()) } }

    fun transformSelection(dx: Float, dy: Float, factor: Float) {
        if (!canEditContent()) return
        val state = _uiState.value
        val bounds = selectionBounds(state.objects, state.selectedIds) ?: return
        val before = state.objects.filter { it.id in state.selectedIds }
        try {
            val after = before.map { transformContent(it, bounds.left, bounds.top, dx, dy, factor) }
            commitChange(before, after)
        } catch (exception: Exception) { showError("The selection could not be resized. Try a smaller change.") }
    }
    fun deleteSelection() {
        if (!canEditContent()) return
        commitChange(_uiState.value.objects.filter { it.id in _uiState.value.selectedIds }, emptyList())
        _uiState.update { it.copy(selectedIds = emptySet()) }
    }
    fun copySelection() {
        if (!canEditContent()) return
        val originals = _uiState.value.objects.filter { it.id in _uiState.value.selectedIds }
        if (originals.isEmpty()) return
        selectionClipboard = originals
        _uiState.update { it.copy(hasSelectionClipboard = true) }
        // Keep the existing one-tap copy behavior, and make the copy available to Paste.
        pasteSelection()
    }
    fun cutSelection() {
        if (!canEditContent()) return
        val originals = _uiState.value.objects.filter { it.id in _uiState.value.selectedIds }
        if (originals.isEmpty()) return
        selectionClipboard = originals
        _uiState.update { it.copy(hasSelectionClipboard = true) }
        deleteSelection()
    }
    fun pasteSelection() {
        if (!canEditContent() || selectionClipboard.isEmpty()) return
        val pageId = _uiState.value.page?.id ?: return
        val now = nextInsertionTime()
        val copies = selectionClipboard.mapIndexed { index, entity ->
            transformContent(entity, 0f, 0f, 24f, 24f, 1f).copy(id = UUID.randomUUID().toString(), pageId = pageId, createdAt = now + index)
        }
        commitChange(emptyList(), copies)
        _uiState.update { it.copy(lassoEnabled = true, selectedIds = copies.map { objectEntity -> objectEntity.id }.toSet()) }
    }

    fun saveText(existingId: String?, text: String, fontSize: Float, color: Int, bold: Boolean, italic: Boolean) {
        if (!canEditContent()) return
        val state = _uiState.value
        val page = state.page ?: return
        val existing = state.objects.firstOrNull { it.id == existingId && it.type == TEXT_OBJECT_TYPE }
        val json = existing?.let { JSONObject(it.payload) } ?: ObjectTransform(
            ((48f - page.viewportX) / page.viewportScale).toDouble(),
            ((48f - page.viewportY) / page.viewportScale).toDouble(),
        ).write(JSONObject().put("width", 300f).put("height", 160f))
        json.put("text", text).put("fontSize", fontSize.coerceIn(8f, 144f)).put("color", color).put("bold", bold).put("italic", italic)
        val now = nextInsertionTime()
        var entity = EditableObjectEntity(existing?.id ?: UUID.randomUUID().toString(), page.id, TEXT_OBJECT_TYPE,
            json.toString(), existing?.createdAt ?: now, System.currentTimeMillis())
        val measured = textLayout(requireNotNull(entity.toContentObject()))
        json.put("height", maxOf(48, measured.height + 16))
        entity = entity.copy(payload = json.toString())
        commitChange(listOfNotNull(existing), listOf(entity))
        _uiState.update { it.copy(lassoEnabled = true, selectedIds = setOf(entity.id)) }
    }

    fun saveShape(existingId: String?, shape: ShapeSpec) = saveStructured(existingId, SHAPE_OBJECT_TYPE, shape.json())
    fun saveGraph(existingId: String?, graph: GraphSpec) = saveStructured(existingId, GRAPH_OBJECT_TYPE, graph.json())
    fun saveTable(existingId: String?, table: TableSpec) = saveStructured(existingId, TABLE_OBJECT_TYPE, table.json())
    fun saveHeading(existingId: String?, text: String, level: Int, color: Int) {
        if (text.isBlank() || level !in 1..6) return
        val old = _uiState.value.objects.firstOrNull { it.id == existingId && it.type == HEADING_OBJECT_TYPE }
        val width = old?.let { JSONObject(it.payload).getDouble("width").toFloat() } ?: 320f
        val json = JSONObject().put("width", width).put("height", 100f).put("text", text.trim())
            .put("headingLevel", level).put("fontSize", headingSize(level)).put("color", color).put("bold", true)
        val sample = EditableObjectEntity("heading-preview", "preview", HEADING_OBJECT_TYPE, json.toString(), 0L, 0L)
        json.put("height", maxOf(48, textLayout(requireNotNull(sample.toContentObject())).height + 16))
        saveStructured(existingId, HEADING_OBJECT_TYPE, json)
    }

    private fun saveStructured(existingId: String?, type: String, json: JSONObject) {
        if (!canEditContent()) return
        val state = _uiState.value
        val page = state.page ?: return
        val existing = state.objects.firstOrNull { it.id == existingId && it.type == type }
        // If a dialog outlives its object, never silently recreate it or overwrite another type.
        if (existingId != null && existing == null) return
        val width = json.getDouble("width").toFloat()
        val height = json.getDouble("height").toFloat()
        val transform = existing?.let { ObjectTransform.read(JSONObject(it.payload)) } ?: run {
            val centerX = (page.viewportWidth / 2f - page.viewportX) / page.viewportScale
            val centerY = (page.viewportHeight / 2f - page.viewportY) / page.viewportScale
            val x = centerX - width / 2f
            val y = centerY - height / 2f
            ObjectTransform(if (state.isInfinite) x.toDouble() else x.coerceIn(0f, (page.pageWidth - width).coerceAtLeast(0f)).toDouble(),
                if (state.isInfinite) y.toDouble() else y.coerceIn(0f, (page.pageHeight - height).coerceAtLeast(0f)).toDouble())
        }
        val now = nextInsertionTime()
        val entity = EditableObjectEntity(existing?.id ?: UUID.randomUUID().toString(), page.id, type,
            transform.write(json).toString(), existing?.createdAt ?: now, System.currentTimeMillis())
        require(entity.toContentObject() != null) { "The inserted object has invalid settings." }
        commitChange(listOfNotNull(existing), listOf(entity))
        _uiState.update { it.copy(lassoEnabled = true, selectedIds = setOf(entity.id)) }
    }

    fun deleteInsertedObject(id: String) {
        if (!canEditContent()) return
        val existing = _uiState.value.objects.firstOrNull { it.id == id && it.type in STRUCTURED_CONTENT_TYPES } ?: return
        commitChange(listOf(existing), emptyList())
        _uiState.update { it.copy(selectedIds = it.selectedIds - id) }
    }

    fun importImage(context: Context, uri: Uri) {
        if (!canEditContent()) return
        val page = _uiState.value.page ?: return
        importingImage = true
        _uiState.update { it.copy(pageBusy = true, saveMessage = "Importing image…") }
        viewModelScope.launch {
            try {
                val imported = imageImporter(context, uri)
                check(_uiState.value.page?.id == page.id) { "The destination page changed while importing." }
                val width = minOf(360f, imported.width.toFloat())
                val height = width * imported.height / imported.width
                val json = ObjectTransform(((48f - page.viewportX) / page.viewportScale).toDouble(),
                    ((48f - page.viewportY) / page.viewportScale).toDouble()).write(JSONObject()
                    .put("width", width).put("height", height).put("assetId", imported.assetId))
                val entity = EditableObjectEntity(UUID.randomUUID().toString(), page.id, IMAGE_OBJECT_TYPE,
                    json.toString(), nextInsertionTime(), System.currentTimeMillis())
                importingImage = false
                _uiState.update { it.copy(pageBusy = false) }
                commitChange(emptyList(), listOf(entity))
                _uiState.update { it.copy(lassoEnabled = true, selectedIds = setOf(entity.id)) }
            } catch (exception: CancellationException) { throw exception }
            catch (exception: Exception) {
                _uiState.update { it.copy(pageBusy = false, saveMessage = if (it.hasUnsavedChanges) "Saving…" else "Saved",
                    errorMessage = "Could not import this image. ${exception.message ?: "Please choose another file."}") }
            } finally {
                importingImage = false
                _uiState.update { it.copy(pageBusy = false) }
            }
        }
    }
    private fun nextInsertionTime() = maxOf(System.currentTimeMillis(), (_uiState.value.objects.maxOfOrNull { it.createdAt } ?: 0L) + 1)
    private fun canEditContent() = !_uiState.value.pageBusy && !_uiState.value.hasPendingInput && _uiState.value.page != null
    fun showError(message: String) { _uiState.update { it.copy(errorMessage = message) } }

    fun exportSelectedPage(context: Context, destination: Uri) {
        val state = _uiState.value; val page = state.page ?: return
        startExport(context, destination) { exporter, progress -> exporter.exportSelectedPage(notebookId, page.id, destination, progress) }
    }
    fun exportNotebook(context: Context, destination: Uri) {
        startExport(context, destination) { exporter, progress -> exporter.exportNotebook(notebookId, destination, progress) }
    }
    fun exportCanvasRegion(context: Context, region: CanvasExportRegion, destination: Uri) {
        val state = _uiState.value; val page = state.page ?: return
        startExport(context, destination) { exporter, progress -> exporter.exportCanvasRegion(notebookId, page.id, region, destination, progress) }
    }
    fun cancelExport() { exportJob?.cancel() }
    private fun startExport(context: Context, destination: Uri, work: suspend (PdfExporter, (ExportProgress) -> Unit) -> Unit) {
        if (exportJob?.isActive == true) return
        val state = _uiState.value
        if (state.hasUnsavedChanges || state.hasPendingInput || state.pageBusy) {
            showError("Wait for the current edits to finish saving before exporting.")
            runCatching { com.ramst.notein.pdf.discardPdfDestination(context, destination) }
            return
        }
        exportJob = viewModelScope.launch {
            try {
                _uiState.update { it.copy(exportProgress = ExportProgress(0, 1), errorMessage = null) }
                work(PdfExporter(context.applicationContext, repository, pdfRepository)) { progress -> _uiState.update { it.copy(exportProgress = progress) } }
                _uiState.update { it.copy(exportProgress = null, saveMessage = "Exported") }
            } catch (exception: CancellationException) {
                _uiState.update { it.copy(exportProgress = null, saveMessage = "Export cancelled") }
            } catch (exception: Exception) {
                runCatching { com.ramst.notein.pdf.discardPdfDestination(context, destination) }
                _uiState.update { it.copy(exportProgress = null, errorMessage = "Could not export PDF. ${exception.message ?: "Please try again."}") }
            }
        }
    }
    fun selectColor(color: Int) = updatePageState { it.copy(brushColor = color) }
    fun selectThickness(thickness: Float) {
        val tool = InkTool.fromStorage(_uiState.value.page?.activeTool.orEmpty())
        if (tool == InkTool.Eraser || !thickness.isFinite()) return
        val value = thickness.coerceIn(tool.range)
        pendingToolThicknesses[tool.storageValue] = value
        _uiState.update { it.copy(toolThicknesses = it.toolThicknesses + (tool.storageValue to value)) }
        updatePageState { it.copy(brushSize = value) }
        viewModelScope.launch {
            try { repository.setToolThickness(tool.storageValue, value) }
            catch (exception: CancellationException) { throw exception }
            catch (_: Exception) { showError("Could not save this tool's thickness. Please try again.") }
        }
    }
    fun setFingerWriting(enabled: Boolean) = updatePageState { it.copy(fingerWriting = enabled) }
    fun updateViewport(x: Float, y: Float, scale: Float = _uiState.value.page?.viewportScale ?: 1f) {
        if (x.isFinite() && y.isFinite() && scale.isFinite()) updatePageState { page ->
            val updated = page.copy(viewportX = x, viewportY = y,
                viewportScale = if (_uiState.value.notebook?.viewportLocked == true) _uiState.value.notebook?.lockedZoomScale ?: page.viewportScale
                    else scale.coerceIn(MIN_ZOOM, MAX_ZOOM))
            if (_uiState.value.isInfinite) updated else paperViewport(updated)
        }
    }

    fun resizeViewport(width: Float, height: Float) {
        if (width <= 0 || height <= 0 || !width.isFinite() || !height.isFinite()) return
        updatePageState { page ->
            val lockedScale = _uiState.value.notebook?.lockedZoomScale?.takeIf { _uiState.value.notebook?.viewportLocked == true }
            if (!_uiState.value.isInfinite && lockedScale != null && page.viewportWidth == 0f && page.viewportHeight == 0f) {
                paperViewport(page.copy(viewportWidth = width, viewportHeight = height, viewportScale = lockedScale,
                    viewportX = (width - page.pageWidth * lockedScale) / 2f))
            } else if (!_uiState.value.isInfinite && page.pageWidth > 0f && page.pageHeight > 0f &&
                page.viewportWidth == 0f && page.viewportHeight == 0f &&
                page.viewportX == 0f && page.viewportY == 0f && page.viewportScale == 1f) {
                page.fitNewPaper(width, height)
            } else {
                val resized = page.copy(viewportX = page.viewportX + if (page.viewportWidth > 0f) (width - page.viewportWidth) / 2f else 0f,
                viewportY = page.viewportY + if (page.viewportHeight > 0f) (height - page.viewportHeight) / 2f else 0f,
                viewportWidth = width, viewportHeight = height)
                if (_uiState.value.isInfinite) resized else paperViewport(resized)
            }
        }
    }

    fun onNavigationChanged(active: Boolean) {
        _uiState.update { it.copy(navigationInProgress = active) }
    }
    fun onPendingInkChanged(count: Int) { _uiState.update { it.copy(pendingInk = count, saveMessage = when {
        it.canRetry -> it.saveMessage; it.hasUnsavedChanges -> "Saving…"; count > 0 -> "Finishing ink…"; else -> "Saved" }) }
    }
    fun onStrokeCommitted(record: InkStrokeRecord) {
        if (record.pageId == _uiState.value.page?.id) commitChange(emptyList(), listOf(record.toEntity()))
    }
    fun onStrokeErased(record: InkStrokeRecord) {
        val objectEntity = _uiState.value.objects.firstOrNull { it.id == record.id } ?: return
        commitChange(listOf(objectEntity), emptyList())
    }
    fun undo(controller: InkEditorController) {
        if (!canEditContent()) return
        val action = undoStack.removeLastOrNull() ?: return
        applyAction(action, false); redoStack.addLast(action); publishHistory()
    }
    fun redo(controller: InkEditorController) {
        if (!canEditContent()) return
        val action = redoStack.removeLastOrNull() ?: return
        applyAction(action, true); undoStack.addLast(action); publishHistory()
    }

    private suspend fun saveCurrentPageUntilClean() = saveMutex.withLock {
        while (_uiState.value.hasUnsavedChanges) {
            val snapshot = _uiState.value; val page = snapshot.page ?: break; val savingRevision = revision
            try {
                val result = repository.saveContentSnapshot(snapshot.objects, page)
                if (result.matchingPageAppendAttempted) matchingPageRetryId = if (result.matchingPageAppendFailed) page.id else null
                val appendPending = matchingPageRetryId != null
                _uiState.update { state -> state.copy(hasUnsavedChanges = revision != savingRevision,
                    canRetry = appendPending,
                    errorMessage = if (appendPending) {
                        "Your changes are saved, but the next matching page could not be added. Tap Retry save to try again."
                    } else null,
                    saveMessage = if (revision != savingRevision) "Saving…" else if (state.pendingInk > 0) "Finishing ink…" else "Saved") }
            } catch (exception: CancellationException) { throw exception }
            catch (exception: Exception) { _uiState.update { it.copy(saveMessage = "Changes kept in memory", canRetry = true,
                errorMessage = "Could not save your latest changes. Keep this editor open and tap Retry save.") }; break }
        }
    }
    private fun applyAction(action: SessionAction, forward: Boolean) {
        val replacement = if (forward) action.after else action.before
        val affected = (action.before + action.after).map { it.id }.toSet()
        _uiState.update { state ->
            val objects = (state.objects.filterNot { it.id in affected } + replacement)
                .sortedWith(compareBy<EditableObjectEntity> { it.createdAt }.thenBy { it.id })
            state.copy(objects = objects, strokes = objects.mapNotNull { it.toInkStrokeRecord() },
                selectedIds = state.selectedIds.intersect(objects.map { it.id }.toSet()))
        }
        requestSave()
    }
    private fun commitChange(before: List<EditableObjectEntity>, after: List<EditableObjectEntity>) {
        if (before == after) return
        val action = SessionAction(before, after)
        recordAction(action); applyAction(action, true)
    }
    private fun updatePageState(transform: (PageEntity) -> PageEntity) { if (_uiState.value.pageBusy) return; val updated = _uiState.value.page?.let(transform) ?: return; if (updated != _uiState.value.page) { _uiState.update { it.copy(page = updated) }; requestSave() } }

    private fun requestSave() { revision++; _uiState.update { it.copy(hasUnsavedChanges = true, saveMessage = "Saving…", errorMessage = null) }; saveRequests.trySend(Unit) }
    private fun runPageChange(change: suspend (NotebookType) -> Unit) {
        if (_uiState.value.isInfinite) return
        if (_uiState.value.hasUnsavedChanges || _uiState.value.hasPendingInput || _uiState.value.pageBusy) { _uiState.update { it.copy(errorMessage = "Wait for the current page to finish saving before changing pages.") }; return }
        pageTransitionBusy = true
        _uiState.update { it.copy(pageBusy = true) }
        viewModelScope.launch {
            try {
                change(_uiState.value.notebookType)
                val pages = repository.observePages(notebookId).first()
                val target = pages.firstOrNull { it.id == selectedPageId } ?: pages.first()
                repository.selectPage(notebookId, target.id)
                selectedPageId = target.id
                _uiState.update { it.copy(pages = pages, page = notebookZoom(target, it.notebook)) }
                pageTransitionBusy = false
                observePageObjects(target.id)
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                pageTransitionBusy = false
                _uiState.update { it.copy(pageBusy = false, errorMessage = exception.message ?: "Could not update pages. Please try again.") }
            }
        }
    }
    private fun recordAction(action: SessionAction) { undoStack.addLast(action); redoStack.clear(); publishHistory() }
    private fun publishHistory() { _uiState.update { it.copy(canUndo = undoStack.isNotEmpty(), canRedo = redoStack.isNotEmpty()) } }
    private data class SessionAction(val before: List<EditableObjectEntity>, val after: List<EditableObjectEntity>)
    private companion object { const val MIN_ZOOM = 0.000001f; const val MAX_ZOOM = 6f }
}
