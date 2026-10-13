package com.ramst.notein.storage

import kotlinx.coroutines.flow.Flow
import java.util.UUID

class NotebookRepository(
    private val notebookDao: NotebookDao,
    private val folderDao: FolderDao,
) {
    fun observeFolders(): Flow<List<FolderEntity>> = folderDao.observeAll()

    fun observeNotebooks(
        inTrash: Boolean,
        folderId: String?,
        searchQuery: String,
    ): Flow<List<NotebookListItem>> = notebookDao.observeNotebookList(
        inTrash,
        folderId,
        escapeLike(searchQuery.trim()),
    )

    suspend fun createNotebook(
        rawTitle: String,
        folderId: String?,
        type: NotebookType = NotebookType.STANDARD,
        template: PaperTemplate = PaperTemplate(),
        autoMatchingPages: Boolean = true,
    ): String {
        require(type != NotebookType.PDF) { "Use PDF import to create a PDF notebook." }
        val now = System.currentTimeMillis()
        val notebookId = UUID.randomUUID().toString()
        val pageId = UUID.randomUUID().toString()
        val title = rawTitle.trim().ifBlank { DEFAULT_NOTEBOOK_TITLE }
        notebookDao.insertNotebookWithBlankPage(
            NotebookEntity(
                notebookId, title, folderId, now, now, notebookType = type.storageValue,
                defaultPaperBackground = template.background,
                defaultPageWidth = if (type == NotebookType.INFINITE) 0f else template.width,
                defaultPageHeight = if (type == NotebookType.INFINITE) 0f else template.height,
                defaultSpacingMm = template.spacingMm,
                defaultPatternWeight = template.patternWeight,
                defaultBackgroundColor = template.backgroundColor,
                autoMatchingPages = type == NotebookType.STANDARD && autoMatchingPages,
            ),
            if (type == NotebookType.INFINITE) PageEntity(pageId, notebookId, 0, now, now, pageWidth = 0f, pageHeight = 0f,
                paperBackground = template.background, paperSpacingMm = template.spacingMm,
                paperPatternWeight = template.patternWeight, paperBackgroundColor = template.backgroundColor)
            else template.page(pageId, notebookId, 0, now),
        )
        return notebookId
    }

    suspend fun createFolder(rawName: String) {
        val name = rawName.trim()
        require(name.isNotEmpty()) { "Folder name cannot be empty." }
        val now = System.currentTimeMillis()
        folderDao.insert(FolderEntity(UUID.randomUUID().toString(), name, now, now))
    }

    suspend fun rename(notebookId: String, rawTitle: String) {
        val title = rawTitle.trim()
        require(title.isNotEmpty()) { "Notebook title cannot be empty." }
        requireNotebookUpdated(notebookDao.rename(notebookId, title, System.currentTimeMillis()))
    }

    suspend fun move(notebookId: String, folderId: String?) =
        requireNotebookUpdated(notebookDao.move(notebookId, folderId, System.currentTimeMillis()))

    suspend fun trash(notebookId: String) =
        requireNotebookUpdated(notebookDao.trash(notebookId, System.currentTimeMillis()))

    suspend fun restore(notebookId: String) =
        requireNotebookUpdated(notebookDao.restore(notebookId, System.currentTimeMillis()))

    suspend fun permanentlyDelete(notebookId: String) =
        requireNotebookUpdated(notebookDao.permanentlyDelete(notebookId))

    private fun requireNotebookUpdated(rowsChanged: Int) {
        check(rowsChanged == 1) { "The notebook no longer exists or has already changed. Refresh and try again." }
    }

    private fun escapeLike(value: String): String = value
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")

    private companion object {
        const val DEFAULT_NOTEBOOK_TITLE = "Untitled notebook"
    }
}
