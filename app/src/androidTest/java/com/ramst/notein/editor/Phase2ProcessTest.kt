package com.ramst.notein.editor

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ramst.notein.storage.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Independent invocations with a verified adb force-stop/new PID in between. */
@RunWith(AndroidJUnit4::class)
class Phase2ProcessTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val prefs get() = context.getSharedPreferences("phase2-process-test", Context.MODE_PRIVATE)

    @Test fun prepare() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("class")?.endsWith("#prepare") == true)
        assertFalse("Verify the previous fixture first", prefs.contains("id"))
        val db = NoteinDatabase.create(context)
        val store = ViewModelStore()
        try {
            val id = NotebookRepository(db.notebookDao(), db.folderDao()).createNotebook("Ink restart ${UUID.randomUUID()}", null)
            val page = db.editorDao().firstPage(id)!!
            var model: EditorViewModel? = null
            instrumentation.runOnMainSync {
                model = EditorViewModel(id, EditorRepository(db.editorDao()), com.ramst.notein.pdf.PdfRepository(context, db.pdfDao()))
                store.put("editor", model!!)
            }
            await { model!!.uiState.value.page != null }
            val kept = record(pageId = page.id, pressure = 0.8f)
            val erased = record(pageId = page.id, y = 200f, endY = 200f)
            instrumentation.runOnMainSync {
                model!!.onStrokeCommitted(kept)
                model!!.onStrokeCommitted(erased)
                model!!.onStrokeErased(erased)
                model!!.undo(InkEditorController())
                model!!.redo(InkEditorController())
                model!!.updateViewport(55f, -25f)
                model!!.selectThickness(10f)
                model!!.setFingerWriting(true)
                model!!.selectTool(InkTool.Highlighter)
            }
            await { model!!.uiState.value.saveMessage == "Saved" }
            assertEquals(kept.id, db.editorDao().pageObjects(page.id).single().id)
            assertTrue(prefs.edit().putString("id", id).putString("page", page.id)
                .putString("stroke", kept.id).putString("payload", kept.payload).commit())
            println("PHASE2_PROCESS_PREPARED id=$id page=${page.id} stroke=${kept.id}")
        } finally { instrumentation.runOnMainSync { store.clear() }; db.close() }
    }

    @Test fun verify() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("class")?.endsWith("#verify") == true)
        val id = prefs.getString("id", null) ?: error("Run prepare first")
        val db = NoteinDatabase.create(context)
        try {
            val page = db.editorDao().firstPage(id)!!
            assertEquals(prefs.getString("page", null), page.id)
            assertEquals(55f, page.viewportX, 0.01f)
            assertEquals(-25f, page.viewportY, 0.01f)
            assertEquals(10f, page.brushSize, 0.01f)
            assertEquals("highlighter", page.activeTool)
            assertTrue(page.fingerWriting)
            val stroke = db.editorDao().pageObjects(page.id).single()
            assertEquals(prefs.getString("stroke", null), stroke.id)
            assertEquals(prefs.getString("payload", null), stroke.payload)
            val decoded = stroke.toInkStrokeRecord()!!.decode()!!.stroke
            assertEquals(0.8f, decoded.inputs[0].pressure, 0.01f)
            assertTrue(decoded.shape.computeBoundingBox()!!.width > 0f)
            println("PHASE2_PROCESS_VERIFIED id=$id page=${page.id} stroke=${stroke.id}")
            val notebooks = NotebookRepository(db.notebookDao(), db.folderDao())
            notebooks.trash(id); notebooks.permanentlyDelete(id) // This unique fixture only.
            assertTrue(prefs.edit().clear().commit())
        } finally { db.close() }
    }

    private fun await(predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!predicate() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(20)
        assertTrue(predicate())
    }
}
