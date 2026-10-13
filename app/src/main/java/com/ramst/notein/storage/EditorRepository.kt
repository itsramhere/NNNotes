package com.ramst.notein.storage

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class EditorRepository(
    private val editorDao: EditorDao,
) {
    private val contentCommitMutex = Mutex()
    private val preferenceCommitMutex = Mutex()
    suspend fun toolPreferences() = editorDao.toolPreferences()
    fun observeToolPreferences() = editorDao.observeToolPreferences()
    suspend fun setToolThickness(tool: String, thickness: Float) = preferenceCommitMutex.withLock {
        editorDao.setToolPreference(ToolPreferenceEntity(tool, thickness))
    }
    suspend fun setViewportLocked(notebookId: String, locked: Boolean, scale: Float? = null) = contentCommitMutex.withLock {
        val notebook = requireNotNull(editorDao.notebook(notebookId)) { "The notebook no longer exists." }
        val selected = editorDao.pages(notebookId).let { pages -> pages.firstOrNull { it.id == notebook.selectedPageId } ?: pages.firstOrNull() }
        val lockedScale = if (locked) (scale ?: selected?.viewportScale ?: 1f).also { require(it.isFinite()) }.coerceIn(0.000001f, 6f) else null
        check(editorDao.setViewportLocked(notebookId, locked, lockedScale) == 1) { "The notebook no longer exists." }
    }
    suspend fun setPageLayout(notebookId: String, layout: PageLayout) {
        check(editorDao.setPageLayout(notebookId, layout.storageValue) == 1) { "The notebook no longer exists." }
    }
    data class ContentSaveResult(
        val matchingPageAppendFailed: Boolean = false,
        val matchingPageAppendAttempted: Boolean = false,
    )
    suspend fun firstPage(notebookId: String): PageEntity? = editorDao.firstPage(notebookId)

    suspend fun notebook(notebookId: String): NotebookEntity? = editorDao.notebook(notebookId)

    suspend fun selectPage(notebookId: String, pageId: String) {
        check(editorDao.selectPage(notebookId, pageId) == 1) { "The page no longer exists." }
    }

    suspend fun pageObjects(pageId: String): List<EditableObjectEntity> = editorDao.pageObjects(pageId)

    fun observeNotebookObjects(notebookId: String): Flow<List<EditableObjectEntity>> = editorDao.observeNotebookObjects(notebookId)

    fun observePages(notebookId: String): Flow<List<PageEntity>> = editorDao.observePages(notebookId)

    suspend fun pages(notebookId: String): List<PageEntity> = editorDao.pages(notebookId)

    suspend fun exportSnapshot(notebookId: String, pageIds: List<String>): List<Pair<PageEntity, List<EditableObjectEntity>>> =
        editorDao.exportSnapshot(notebookId, pageIds)

    suspend fun defaultExportSnapshot(notebookId: String): List<Pair<PageEntity, List<EditableObjectEntity>>> =
        editorDao.defaultExportSnapshot(notebookId)

    fun observeObjects(pageId: String): Flow<List<EditableObjectEntity>> = editorDao.observePageObjects(pageId)

    suspend fun saveSnapshot(strokes: List<EditableObjectEntity>, state: PageEntity) {
        editorDao.saveInkSnapshot(strokes, state, System.currentTimeMillis())
    }

    suspend fun saveContentSnapshot(objects: List<EditableObjectEntity>, state: PageEntity): ContentSaveResult = contentCommitMutex.withLock {
        val now = System.currentTimeMillis()
        val qualifies = editorDao.saveContentSnapshot(objects, state, now)
        if (!qualifies) return@withLock ContentSaveResult()
        try {
            editorDao.appendMatchingPageIfNeeded(state.id, now)
            ContentSaveResult(matchingPageAppendAttempted = true)
        } catch (exception: kotlinx.coroutines.CancellationException) {
            throw exception
        } catch (_: Exception) {
            // The edit is already committed. Keep the automatic-page retry separate from it.
            ContentSaveResult(matchingPageAppendFailed = true, matchingPageAppendAttempted = true)
        }
    }

    suspend fun retryMatchingPageAppend(pageId: String) = contentCommitMutex.withLock {
        editorDao.appendMatchingPageIfNeeded(pageId, System.currentTimeMillis())
    }

    suspend fun saveStroke(stroke: EditableObjectEntity, state: PageEntity) {
        editorDao.saveStrokeAndEditorState(stroke, state, System.currentTimeMillis())
    }

    suspend fun deleteStroke(pageId: String, objectId: String) {
        editorDao.deleteStrokeAndTouchNotebook(pageId, objectId, System.currentTimeMillis())
    }

    suspend fun updateEditorState(state: PageEntity) {
        editorDao.updateEditorStateAndTouchNotebook(state, System.currentTimeMillis())
    }

    suspend fun createPage(notebookId: String, type: NotebookType): String {
        val now = System.currentTimeMillis()
        val pageId = UUID.randomUUID().toString()
        val notebook = requireNotNull(editorDao.notebook(notebookId)) { "The notebook no longer exists." }
        val template = PaperTemplate(notebook.defaultPaperBackground, notebook.defaultPageWidth, notebook.defaultPageHeight,
            notebook.defaultSpacingMm, notebook.defaultPatternWeight, notebook.defaultBackgroundColor)
        editorDao.createPageAndTouchNotebook(
            if (type == NotebookType.INFINITE) template.page(pageId, notebookId, editorDao.nextPageSortIndex(notebookId), now)
                .copy(pageWidth = 0f, pageHeight = 0f)
            else template.page(pageId, notebookId, editorDao.nextPageSortIndex(notebookId), now)
                .copy(viewportScale = notebook.lockedZoomScale.takeIf { notebook.viewportLocked } ?: 1f),
            now,
        )
        return pageId
    }

    suspend fun duplicatePage(pageId: String): String {
        val now = System.currentTimeMillis()
        val duplicateId = UUID.randomUUID().toString()
        editorDao.duplicatePageAndTouchNotebook(pageId, duplicateId, now)
        return duplicateId
    }

    suspend fun deletePage(pageId: String, notebookId: String) {
        editorDao.deletePageAndTouchNotebook(pageId, notebookId, System.currentTimeMillis())
    }

    suspend fun reorderPages(notebookId: String, orderedPageIds: List<String>) {
        editorDao.reorderPagesAndTouchNotebook(notebookId, orderedPageIds, System.currentTimeMillis())
    }

    suspend fun updateBackground(pageId: String, background: PaperBackground) {
        editorDao.updatePageBackgroundAndTouchNotebook(pageId, background.storageValue, System.currentTimeMillis())
    }

    suspend fun updateNotebookTemplate(notebookId: String, template: PaperTemplate, autoMatchingPages: Boolean) {
        check(editorDao.updateNotebookTemplate(notebookId, template.background, template.width, template.height,
            template.spacingMm, template.patternWeight, template.backgroundColor, autoMatchingPages, System.currentTimeMillis()) == 1) {
            "The notebook no longer exists."
        }
    }

    suspend fun updatePageTemplate(pageId: String, template: PaperTemplate) {
        editorDao.updatePageTemplateAndTouchNotebook(pageId, template, System.currentTimeMillis())
    }
}
