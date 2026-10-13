package com.ramst.notein.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ramst.notein.storage.FolderEntity
import com.ramst.notein.storage.NotebookListItem
import com.ramst.notein.storage.NotebookRepository
import com.ramst.notein.storage.NotebookType
import com.ramst.notein.storage.PaperTemplate
import com.ramst.notein.pdf.PdfRepository
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LibraryUiState(
    val folders: List<FolderEntity> = emptyList(),
    val notebooks: List<NotebookListItem> = emptyList(),
    val searchQuery: String = "",
    val selectedFolderId: String? = null,
    val inTrash: Boolean = false,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val storageErrorMessage: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    private val repository: NotebookRepository,
    private val pdfRepository: PdfRepository,
) : ViewModel() {
    private val inTrash = MutableStateFlow(false)
    private val searchQuery = MutableStateFlow("")
    private val selectedFolderId = MutableStateFlow<String?>(null)
    private val errorMessage = MutableStateFlow<String?>(null)
    private val readAttempts = MutableStateFlow(0)

    private data class ReadResult<T>(
        val items: List<T> = emptyList(),
        val isLoading: Boolean = true,
        val error: String? = null,
    )

    private fun <T> Flow<List<T>>.asReadResult(): Flow<ReadResult<T>> =
        map { ReadResult(items = it, isLoading = false) }
            .onStart { emit(ReadResult()) }
            .catch {
                emit(ReadResult(isLoading = false, error = "Could not open local storage. Please try again."))
            }

    private data class Filter(val inTrash: Boolean, val folderId: String?, val query: String)

    private val filters = combine(inTrash, selectedFolderId, searchQuery) { trash, folderId, query ->
        Filter(trash, folderId, query)
    }

    private val notebooks = combine(filters, readAttempts) { filter, _ -> filter }.flatMapLatest { filter ->
        repository.observeNotebooks(filter.inTrash, filter.folderId, filter.query).asReadResult()
    }

    val uiState: StateFlow<LibraryUiState> = combine(
        readAttempts.flatMapLatest {
            repository.observeFolders().asReadResult()
        },
        notebooks,
        filters,
        errorMessage,
    ) { folders, notebookList, filter, error ->
        LibraryUiState(
            folders = folders.items,
            notebooks = notebookList.items,
            searchQuery = filter.query,
            selectedFolderId = filter.folderId,
            inTrash = filter.inTrash,
            isLoading = folders.isLoading || notebookList.isLoading,
            errorMessage = error,
            storageErrorMessage = folders.error ?: notebookList.error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    fun showTrash(show: Boolean) {
        inTrash.value = show
        if (show) selectedFolderId.value = null
    }

    fun updateSearch(value: String) {
        searchQuery.value = value
    }

    fun selectFolder(folderId: String?) {
        selectedFolderId.value = folderId
    }

    fun createNotebook(title: String, folderId: String?, type: NotebookType, template: PaperTemplate, autoMatchingPages: Boolean, onCreated: (String) -> Unit) = runWrite {
        onCreated(repository.createNotebook(title, folderId, type, template, autoMatchingPages))
    }

    fun createFolder(name: String) = runWrite { repository.createFolder(name) }

    fun importPdf(uri: Uri, onImported: (String) -> Unit) = runWrite {
        onImported(pdfRepository.import(uri).notebookId)
    }

    fun rename(notebookId: String, title: String) = runWrite { repository.rename(notebookId, title) }

    fun move(notebookId: String, folderId: String?) = runWrite { repository.move(notebookId, folderId) }

    fun trash(notebookId: String) = runWrite { repository.trash(notebookId) }

    fun restore(notebookId: String) = runWrite { repository.restore(notebookId) }

    fun permanentlyDelete(notebookId: String) = runWrite {
        val sources = pdfRepository.documents(notebookId)
        repository.permanentlyDelete(notebookId)
        pdfRepository.removeUnreferencedFiles(sources)
    }

    fun clearError() {
        errorMessage.value = null
    }

    fun retryStorage() {
        readAttempts.value += 1
    }

    private fun runWrite(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                errorMessage.value = "Could not save your change. ${exception.message ?: "Please try again."}"
            }
        }
    }
}
