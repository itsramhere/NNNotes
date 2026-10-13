package com.ramst.notein.editor

import android.content.Context
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ramst.notein.pdf.*
import com.ramst.notein.storage.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

/** The script runs each method in a different process, with a force-stop in between. */
@RunWith(AndroidJUnit4::class)
class Phase10ProcessTest {
    private val base = ApplicationProvider.getApplicationContext<Context>()
    private val evidence get() = File(base.filesDir, "phase10-evidence").apply { mkdirs() }
    private val manifest get() = File(evidence, "process.json")
    @Test fun prepare() = runBlocking {
        val f = Phase10Fixture()
        try {
            val id = f.notebook(NotebookType.INFINITE)
            val page = f.editor.firstPage(id)!!.copy(viewportX = -230f, viewportY = 120f, viewportScale = 1.75f,
                viewportWidth = 1000f, viewportHeight = 700f, activeTool = InkTool.Pencil.storageValue, brushSize = .375f)
            val ink = record(pageId = page.id, tool = InkTool.Pencil, size = .375f)
            f.editor.saveContentSnapshot(listOf(ink.toEntity()), page)
            f.editor.setViewportLocked(id, true)
            val widths = mapOf("pencil" to .375f, "ball_pen" to 1.25f, "ink_pen" to 8.5f, "highlighter" to 13.25f)
            widths.forEach { (tool, width) -> f.editor.setToolThickness(tool, width) }
            val blue = f.prepare(Color.BLUE, 1); val red = f.prepare(Color.RED, 1)
            f.pdf.insert(blue, id, page, setOf(0), true, 120f, 40f)
            f.pdf.insert(red, id, page, setOf(0), true, 340f, 40f)
            val abandoned = f.prepare()
            val pagedId = f.notebook()
            val paged = f.editor.firstPage(pagedId)!!.copy(viewportScale = 1.75f, viewportWidth = 1000f, viewportHeight = 700f,
                viewportX = 132.5f, viewportY = -120f)
            val pagedInk = record(pageId = paged.id).toEntity()
            f.editor.saveContentSnapshot(listOf(pagedInk), paged)
            val next = f.editor.pages(pagedId).last()
            f.editor.updateEditorState(next.copy(viewportScale = .5f))
            f.editor.setViewportLocked(pagedId, true, 1.75f)
            f.editor.setPageLayout(pagedId, PageLayout.VERTICAL)
            manifest.writeText(JSONObject().put("token", f.token).put("id", id).put("page", page.id)
                .put("ink", ink.payload).put("blue", blue.sourceId).put("red", red.sourceId)
                .put("pagedId", pagedId).put("pagedPage", paged.id).put("pagedInk", pagedInk.payload)
                .put("abandoned", abandoned.localPath).put("pid", android.os.Process.myPid()).toString())
            assertTrue(File(abandoned.localPath).isFile)
        } finally { f.db.close() }
    }
    @Test fun verify() = runBlocking {
        val data = JSONObject(manifest.readText()); val f = Phase10Fixture(data.getString("token"))
        try {
            assertNotEquals(data.getInt("pid"), android.os.Process.myPid())
            val id = data.getString("id"); val page = f.editor.firstPage(id)!!
            assertTrue(f.editor.notebook(id)!!.viewportLocked)
            assertEquals(1.75f, f.editor.notebook(id)!!.lockedZoomScale!!, 0f)
            val pagedId = data.getString("pagedId")
            assertEquals("vertical", f.editor.notebook(pagedId)!!.pageLayout)
            assertEquals(1.75f, f.editor.notebook(pagedId)!!.lockedZoomScale!!, 0f)
            assertEquals(2, f.editor.pages(pagedId).size)
            assertTrue(f.editor.pages(pagedId).all { it.viewportScale == 1.75f })
            assertEquals(data.getString("pagedInk"), f.editor.pageObjects(data.getString("pagedPage")).single().payload)
            assertEquals(-230f, page.viewportX, 0f); assertEquals(120f, page.viewportY, 0f)
            assertEquals(1.75f, page.viewportScale, 0f); assertEquals(1000f, page.viewportWidth, 0f); assertEquals(700f, page.viewportHeight, 0f)
            assertEquals(mapOf("pencil" to .375f, "ball_pen" to 1.25f, "ink_pen" to 8.5f, "highlighter" to 13.25f), f.editor.toolPreferences().associate { it.tool to it.thickness })
            val objects = f.editor.pageObjects(page.id)
            assertEquals(data.getString("ink"), objects.single { it.type == INK_STROKE_TYPE }.payload)
            assertEquals(setOf(data.getString("blue"), data.getString("red")), objects.filter { it.type == PDF_OBJECT_TYPE }.map { it.toContentObject()!!.pdfSourceId }.toSet())
            val fresh = f.prepare(); f.pdf.discard(fresh)
            assertFalse(File(data.getString("abandoned")).exists())
            assertTrue(f.pdf.documents(id).all { File(it.localPath).isFile })
            val output = File(evidence, "restart-canvas.pdf")
            PdfExporter(f.context, f.editor, f.pdf, { File(it.path!!).outputStream() }, { File(it.path!!).delete(); Unit })
                .exportCanvasRegion(id, page.id, CanvasExportRegion(0f, 0f, 500f, 400f), Uri.fromFile(output)) {}
            val b = renderPdfBitmap(output.path, 0, 595, 842)
            try { assertTrue(Color.blue(b.getPixel(60, 80)) > 200); assertTrue(Color.red(b.getPixel(280, 80)) > 200) }
            finally { b.recycle() }
            File(evidence, "restart-verified.json").writeText(data.put("verifiedPid", android.os.Process.myPid()).put("status", "passed").toString())
        } finally { f.close() }
    }
}
