package com.ramst.notein.editor

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ramst.notein.storage.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class Phase3ProcessTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("phase3-process", Context.MODE_PRIVATE)
    @Test fun prepare() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("class")?.endsWith("#prepare") == true)
        assertFalse(prefs.contains("STANDARD"))
        val db = NoteinDatabase.create(context)
        try {
            val notebooks = NotebookRepository(db.notebookDao(), db.folderDao())
            val editor = EditorRepository(db.editorDao())
            for (type in listOf(NotebookType.STANDARD, NotebookType.INFINITE)) {
                val id = notebooks.createNotebook("Phase3 restart ${UUID.randomUUID()}", null, type)
                val pageId = if (type == NotebookType.STANDARD) editor.createPage(id, type) else editor.firstPage(id)!!.id
                val page = db.editorDao().page(pageId)!!
                val ink = record(-10000f, 20000f, -9800f, 20100f, pageId = pageId)
                editor.saveSnapshot(listOf(ink.toEntity()), page.copy(viewportX = 15000f, viewportY = -12000f, viewportScale = 0.07f))
                if (type == NotebookType.STANDARD) editor.updateBackground(pageId, PaperBackground.GRID)
                editor.selectPage(id, pageId)
                assertTrue(prefs.edit().putString(type.name,id).putString("${type.name}-page",pageId).putString("${type.name}-ink",ink.payload).commit())
            }
            println("PHASE3_PROCESS_PREPARED both notebook types")
        } finally { db.close() }
    }
    @Test fun verify() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("class")?.endsWith("#verify") == true)
        val db = NoteinDatabase.create(context)
        try {
            val editor = EditorRepository(db.editorDao())
            val notebooks = NotebookRepository(db.notebookDao(),db.folderDao())
            for (type in listOf(NotebookType.STANDARD, NotebookType.INFINITE)) {
                val id = prefs.getString(type.name,null) ?: error("Prepare first")
                val notebook = editor.notebook(id)!!
                assertEquals(type.storageValue, notebook.notebookType)
                assertEquals(prefs.getString("${type.name}-page",null), notebook.selectedPageId)
                val page = db.editorDao().page(notebook.selectedPageId!!)!!
                assertEquals(15000f,page.viewportX,0f); assertEquals(-12000f,page.viewportY,0f); assertEquals(0.07f,page.viewportScale,0f)
                assertEquals(prefs.getString("${type.name}-ink",null),db.editorDao().pageObjects(page.id).single().payload)
                if (type == NotebookType.STANDARD) { assertEquals("grid",page.paperBackground); assertEquals(2, db.editorDao().pageCount(id)) }
                notebooks.trash(id); notebooks.permanentlyDelete(id)
            }
            assertTrue(prefs.edit().clear().commit())
            println("PHASE3_PROCESS_VERIFIED both notebook types")
        } finally { db.close() }
    }
}
