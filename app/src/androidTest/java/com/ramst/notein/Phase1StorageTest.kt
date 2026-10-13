package com.ramst.notein

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelStore
import com.ramst.notein.library.LibraryViewModel
import com.ramst.notein.storage.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class Phase1StorageTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databaseName = "phase1-test-${UUID.randomUUID()}.db"
    private lateinit var database: NoteinDatabase
    private lateinit var repository: NotebookRepository

    @Before fun open() { reopen() }

    private fun reopen() {
        database = Room.databaseBuilder(context, NoteinDatabase::class.java, databaseName).build()
        repository = NotebookRepository(database.notebookDao(), database.folderDao())
    }

    @After fun close() {
        database.close()
        context.deleteDatabase(databaseName) // Only this test's uniquely named database.
    }

    private suspend fun list(trash: Boolean = false, folder: String? = null, query: String = "") =
        withTimeout(10_000) { repository.observeNotebooks(trash, folder, query).first() }

    private fun scalar(sql: String): String? = database.openHelper.readableDatabase.query(sql).use {
        if (it.moveToFirst()) it.getString(0) else null
    }

    @Test fun initializesEmptyAndCreatesExactlyOneBlankPage() = runBlocking {
        assertTrue(list().isEmpty())
        assertTrue(repository.observeFolders().first().isEmpty())
        val id = repository.createNotebook("   ", null)
        assertEquals("Untitled notebook", list().single().title)
        assertEquals("1", scalar("SELECT COUNT(*) FROM pages WHERE notebook_id = '$id'"))
        assertEquals("0", scalar("SELECT COUNT(*) FROM editable_objects"))
    }

    @Test fun renameAndMoveRetainNotebookAndPageIdsAfterDatabaseReopen() = runBlocking {
        repository.createFolder("Work")
        val folder = repository.observeFolders().first().single()
        val id = repository.createNotebook("Draft", null)
        val pageId = scalar("SELECT id FROM pages WHERE notebook_id = '$id'")
        repository.rename(id, " Renamed ")
        repository.move(id, folder.id)
        database.close()
        reopen()
        val notebook = list(folder = folder.id).single()
        assertEquals(id, notebook.id)
        assertEquals("Renamed", notebook.title)
        assertEquals("Work", notebook.folderName)
        assertEquals(pageId, scalar("SELECT id FROM pages WHERE notebook_id = '$id'"))
    }

    @Test fun duplicateTitlesTrashRestoreAndDeleteByIdWithoutDeletingSibling() = runBlocking {
        val first = repository.createNotebook("Duplicate", null)
        val second = repository.createNotebook("Duplicate", null)
        val page = scalar("SELECT id FROM pages WHERE notebook_id = '$first'")!!
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO editable_objects (id, page_id, type, payload, created_at, updated_at) VALUES ('object', '$page', 'test', '{}', 1, 1)",
        )
        repository.trash(first)
        assertEquals(second, list().single().id)
        assertEquals(first, list(trash = true).single().id)
        repository.restore(first)
        assertEquals(setOf(first, second), list().map { it.id }.toSet())
        assertNull(list().first { it.id == first }.trashedAt)
        repository.trash(first)
        repository.permanentlyDelete(first)
        assertTrue(list(trash = true).isEmpty())
        assertEquals(second, list().single().id)
        assertEquals("0", scalar("SELECT COUNT(*) FROM pages WHERE notebook_id = '$first'"))
        assertEquals("0", scalar("SELECT COUNT(*) FROM editable_objects"))
        assertEquals("1", scalar("SELECT COUNT(*) FROM pages WHERE notebook_id = '$second'"))
    }

    @Test fun titleSearchIsCaseInsensitiveAndTreatsSqlWildcardsLiterally() = runBlocking {
        val literal = repository.createNotebook("Budget 100%_\\2026", null)
        repository.createNotebook("Budget 100XYZ2026", null)
        assertEquals(2, list(query = "bUdGeT").size)
        for (query in listOf("%", "_", "\\", "100%_\\")) {
            assertEquals(listOf(literal), list(query = query).map { it.id })
        }
        repository.rename(literal, "Meeting")
        assertTrue(list(query = "%").isEmpty())
        repository.trash(literal)
        assertTrue(list(query = "meeting").isEmpty())
        repository.restore(literal)
        assertEquals(literal, list(query = "meeting").single().id)
    }

    @Test fun recentSortUpdatesAfterRenameMoveAndRestore() = runBlocking {
        val first = repository.createNotebook("First", null)
        val second = repository.createNotebook("Second", null)
        suspend fun markOld() {
            database.notebookDao().rename(first, "First", 1)
            database.notebookDao().rename(second, "Second", 2)
        }
        markOld()
        assertEquals(listOf(second, first), list().map { it.id })
        repository.rename(first, "Edited")
        assertEquals(first, list().first().id)
        markOld()
        repository.move(first, null)
        assertEquals(first, list().first().id)
        repository.trash(first)
        repository.restore(first)
        assertEquals(first, list().first().id)
    }

    @Test fun failedPageInsertionRollsBackNotebookTransaction() = runBlocking {
        val notebook = NotebookEntity("rollback", "Rollback", null, 1, 1)
        val invalidPage = PageEntity("page", "missing-parent", 0, 1, 1)
        try {
            database.notebookDao().insertNotebookWithBlankPage(notebook, invalidPage)
            fail("Expected a foreign-key failure")
        } catch (_: android.database.sqlite.SQLiteConstraintException) { }
        assertTrue(list().isEmpty())
        assertEquals("0", scalar("SELECT COUNT(*) FROM pages"))
        // The same database must remain usable after the aborted transaction.
        repository.createNotebook("Recovery", null)
        assertEquals("Recovery", list().single().title)
    }

    @Test fun invalidFolderMoveRollsBackAndActiveNotebookCannotBePermanentlyDeleted() = runBlocking {
        val id = repository.createNotebook("Safe", null)
        val original = list().single()
        try {
            repository.move(id, "missing-folder")
            fail("Expected invalid folder to fail")
        } catch (_: android.database.sqlite.SQLiteConstraintException) { }
        assertEquals(original, list().single())
        try {
            repository.permanentlyDelete(id)
            fail("Active notebook must first be trashed")
        } catch (_: IllegalStateException) { }
        assertEquals(id, list().single().id)
        try {
            repository.rename("missing-notebook", "New title")
            fail("Missing notebook must report a failure")
        } catch (_: IllegalStateException) { }
    }

    @Test fun rejectedWritesShowSaveErrorAndRetainExistingData() = runBlocking {
        val id = repository.createNotebook("Keep", null)
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_rename BEFORE UPDATE OF title ON notebooks BEGIN SELECT RAISE(ABORT, 'injected storage failure'); END",
        )
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var viewModel: LibraryViewModel
        val store = ViewModelStore()
        instrumentation.runOnMainSync {
            viewModel = LibraryViewModel(repository, com.ramst.notein.pdf.PdfRepository(context, database.pdfDao()))
            store.put("test", viewModel)
            viewModel.rename(id, "Must not persist")
        }
        val error = withTimeout(10_000) { viewModel.uiState.first { it.errorMessage != null } }
        assertTrue(error.errorMessage!!.startsWith("Could not save your change."))
        assertEquals("Keep", list().single().title)
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_rename")
        instrumentation.runOnMainSync { viewModel.clearError(); viewModel.rename(id, "Retry saved") }
        withTimeout(10_000) { repository.observeNotebooks(false, null, "").first { it.single().title == "Retry saved" } }
        instrumentation.runOnMainSync { store.clear() }
    }

    @Test fun initializationFailureShowsPersistentErrorRatherThanEmptyLibrary() = runBlocking {
        val blocker = UUID.randomUUID().toString()
        // A directory where the database file should be forces an open failure
        // without changing or corrupting any existing notebook database.
        val path = context.getDatabasePath("phase1-blocked-$blocker.db")
        assertTrue(path.mkdirs())
        val blocked = Room.databaseBuilder(context, NoteinDatabase::class.java, path.name).build()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var viewModel: LibraryViewModel
        val store = ViewModelStore()
        instrumentation.runOnMainSync {
            viewModel = LibraryViewModel(NotebookRepository(blocked.notebookDao(), blocked.folderDao()), com.ramst.notein.pdf.PdfRepository(context, blocked.pdfDao()))
            store.put("test", viewModel)
        }
        try {
            withTimeout(10_000) { viewModel.uiState.first { it.storageErrorMessage != null } }
            assertNotNull(viewModel.uiState.value.storageErrorMessage)
            withTimeout(10_000) { viewModel.uiState.first { !it.isLoading } }
            assertTrue(path.delete())
            instrumentation.runOnMainSync { viewModel.retryStorage() }
            withTimeout(10_000) { viewModel.uiState.first { !it.isLoading && it.storageErrorMessage == null } }
            val recovered = NotebookRepository(blocked.notebookDao(), blocked.folderDao())
            recovered.createNotebook("Recovered", null)
            assertEquals("Recovered", recovered.observeNotebooks(false, null, "").first().single().title)
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            blocked.close()
            if (path.isDirectory) path.delete() else context.deleteDatabase(path.name)
        }
    }
}
