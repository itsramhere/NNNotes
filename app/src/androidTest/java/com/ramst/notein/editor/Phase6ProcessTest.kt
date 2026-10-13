package com.ramst.notein.editor

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ramst.notein.pdf.*
import com.ramst.notein.storage.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class Phase6ProcessTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("phase6-process", Context.MODE_PRIVATE)
    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    @Test fun prepare() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("class")?.endsWith("#prepare") == true)
        assertFalse("Verify the previous fixture first", prefs.contains("id"))
        val db = NoteinDatabase.create(context)
        val source = File(context.cacheDir, "phase6-process-${UUID.randomUUID()}.pdf")
        try {
            InstrumentationRegistry.getInstrumentation().context.assets.open("phase6/mixed.pdf").use { input -> source.outputStream().use { input.copyTo(it) } }
            val pdf = PdfRepository(context, db.pdfDao()); val editor = EditorRepository(db.editorDao())
            val id = pdf.import(Uri.fromFile(source), "Phase6 restart ${UUID.randomUUID()}").notebookId
            val local = File(pdf.document(id)!!.localPath)
            val page = editor.pages(id)[1]
            val objects = listOf(record(pageId = page.id, pressure = .8f).toEntity(), phase4Text(page.id, text = "Offline annotation Ω"))
            editor.saveContentSnapshot(objects, page.copy(viewportX = -40f, viewportY = 80f, viewportScale = 1.75f))
            editor.selectPage(id, page.id)
            val expected = JSONObject(); objects.forEach { expected.put(it.id, it.payload) }
            assertTrue(prefs.edit().putString("id", id).putString("page", page.id).putString("objects", expected.toString()).putString("hash", hash(local)).commit())
            println("PHASE6_PROCESS_PREPARED copied PDF, selected page, editable annotations and viewport; external source deleted")
        } finally { source.delete(); db.close() }
    }
    @Test fun verify() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("class")?.endsWith("#verify") == true)
        val db = NoteinDatabase.create(context)
        try {
            val pdf = PdfRepository(context, db.pdfDao()); val editor = EditorRepository(db.editorDao())
            val id = prefs.getString("id", null) ?: error("Prepare first")
            val pageId = prefs.getString("page", null)!!
            val notebook = editor.notebook(id)!!; assertEquals(NotebookType.PDF.storageValue, notebook.notebookType)
            assertEquals(pageId, notebook.selectedPageId); assertEquals(5, editor.pages(id).size)
            val page = db.editorDao().page(pageId)!!
            assertEquals(842f, page.pageWidth, 0f); assertEquals(595f, page.pageHeight, 0f)
            assertEquals(1, page.pdfPageIndex); assertEquals(-40f, page.viewportX, 0f); assertEquals(80f, page.viewportY, 0f); assertEquals(1.75f, page.viewportScale, 0f)
            val local = File(pdf.document(id)!!.localPath); assertEquals(prefs.getString("hash", null), hash(local))
            val expected = JSONObject(prefs.getString("objects", null)!!)
            val actual = editor.pageObjects(pageId); assertEquals(2, actual.size)
            actual.forEach { assertEquals(expected.getString(it.id), it.payload); assertNotNull(it.toContentObject()) }
            val text = actual.first { it.type == TEXT_OBJECT_TYPE }
            val changed = text.copy(payload = JSONObject(text.payload).put("text", "Edited after restart").toString())
            editor.saveContentSnapshot(actual.map { if (it.id == text.id) changed else it }, page)
            assertEquals("Edited after restart", editor.pageObjects(pageId).first { it.id == text.id }.toContentObject()!!.text)
            val file = File(context.filesDir, "phase6-evidence/restarted.pdf"); file.parentFile!!.mkdirs()
            PdfExporter(context, editor, pdf, openDestination = { file.outputStream() }, deleteDestination = { file.delete(); Unit })
                .exportSelectedPage(id, pageId, Uri.fromFile(file)) {}
            val notebooks = NotebookRepository(db.notebookDao(), db.folderDao()); notebooks.trash(id); notebooks.permanentlyDelete(id)
            local.delete(); assertTrue(prefs.edit().clear().commit())
            println("PHASE6_PROCESS_VERIFIED exact source hash, page order/dimensions/selection, viewport, annotation payloads, post-restart editing and export")
        } finally { db.close() }
    }
}
