package com.ramst.notein

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ramst.notein.storage.NoteinDatabase
import com.ramst.notein.storage.NotebookRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Run prepare, force-stop/relaunch the app via adb, then run verify separately. */
@RunWith(AndroidJUnit4::class)
class Phase1ProcessTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("phase1-process-test", Context.MODE_PRIVATE)

    @Test fun prepare() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("class")?.endsWith("#prepare") == true)
        assertFalse("Complete the previous fixture before preparing another", prefs.contains("id"))
        val db = NoteinDatabase.create(context)
        try {
            val repository = NotebookRepository(db.notebookDao(), db.folderDao())
            val marker = "Restart-${UUID.randomUUID()}"
            repository.createFolder(marker)
            val folder = repository.observeFolders().first().single { it.name == marker }
            val id = repository.createNotebook("Draft $marker", null)
            val sibling = repository.createNotebook("Renamed $marker", null)
            repository.rename(id, "Renamed $marker")
            repository.move(id, folder.id)
            repository.trash(sibling)
            val page = db.openHelper.readableDatabase.query("SELECT id FROM pages WHERE notebook_id = ?", arrayOf(id)).use {
                assertTrue(it.moveToFirst()); it.getString(0)
            }
            assertTrue(prefs.edit().putString("id", id).putString("sibling", sibling)
                .putString("folder", folder.id).putString("page", page).putString("marker", marker).commit())
            println("PHASE1_PROCESS_PREPARED id=$id page=$page folder=${folder.id}")
        } finally { db.close() }
    }

    @Test fun verify() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("class")?.endsWith("#verify") == true)
        val id = prefs.getString("id", null) ?: error("Run prepare first")
        val sibling = prefs.getString("sibling", null)!!
        val folder = prefs.getString("folder", null)!!
        val marker = prefs.getString("marker", null)!!
        val db = NoteinDatabase.create(context)
        try {
            val repository = NotebookRepository(db.notebookDao(), db.folderDao())
            val notebook = repository.observeNotebooks(false, folder, marker).first().single()
            assertEquals(id, notebook.id)
            assertEquals("Renamed $marker", notebook.title)
            assertEquals(marker, notebook.folderName)
            val page = db.openHelper.readableDatabase.query("SELECT id FROM pages WHERE notebook_id = ?", arrayOf(id)).use {
                assertTrue(it.moveToFirst()); it.getString(0)
            }
            assertEquals(prefs.getString("page", null), page)
            assertEquals(sibling, repository.observeNotebooks(true, null, marker).first().single().id)
            repository.restore(sibling)
            assertEquals(setOf(id, sibling), repository.observeNotebooks(false, null, marker).first().map { it.id }.toSet())
            // Delete only these fixture records, retaining any unrelated app data.
            for (fixture in listOf(id, sibling)) { repository.trash(fixture); repository.permanentlyDelete(fixture) }
            db.openHelper.writableDatabase.execSQL("DELETE FROM folders WHERE id = ?", arrayOf(folder))
            assertTrue(prefs.edit().clear().commit())
            println("PHASE1_PROCESS_VERIFIED id=$id page=$page folder=$folder")
        } finally { db.close() }
    }
}
