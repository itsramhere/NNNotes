package com.ramst.notein.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ramst.notein.pdf.PdfExporter
import com.ramst.notein.storage.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class InsertContentTest {
    private lateinit var fixture: Phase10Fixture
    private lateinit var model: EditorViewModel
    private lateinit var notebook: String
    private val store = ViewModelStore()
    private fun main(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
    private fun await(check: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 15000
        while (!check() && SystemClock.uptimeMillis() < until) SystemClock.sleep(20)
        assertTrue("Timed out waiting for inserted content: ${model.uiState.value}", check())
    }
    private fun saved() = await { !model.uiState.value.hasUnsavedChanges && !model.uiState.value.pageBusy }
    private fun open() {
        main { store.clear(); model = EditorViewModel(notebook, fixture.editor, fixture.pdf); store.put("insert", model) }
        await { !model.uiState.value.isLoading && !model.uiState.value.pageBusy }; saved()
    }
    @Before fun setup() = runBlocking { fixture = Phase10Fixture(); notebook = fixture.notebook(); open() }
    @After fun cleanup() { main { store.clear() }; fixture.close() }

    @Test fun everyShapeAndHeadingLevelRemainsEditableAfterSaveCopyUndoAndReopen() = runBlocking {
        ShapeKind.entries.forEach { kind ->
            main { model.saveShape(null, ShapeSpec(kind, 120f, if (kind in setOf(ShapeKind.CIRCLE, ShapeKind.SQUARE)) 120f else 80f)) }; saved()
        }
        (1..6).forEach { level -> main { model.saveHeading(null, "Level $level", level, Color.BLUE) }; saved() }
        val page = model.uiState.value.page!!.id
        val original = fixture.editor.pageObjects(page)
        assertEquals(15, original.size)
        assertEquals((1..6).toList(), original.mapNotNull { it.toContentObject()?.headingLevel })
        assertEquals(ShapeKind.entries.toList(), original.mapNotNull { it.toContentObject()?.shape?.kind })
        main { model.copySelection() }; saved()
        assertEquals(16, model.uiState.value.objects.size)
        main { model.undo(InkEditorController()) }; saved()
        assertEquals(original, fixture.editor.pageObjects(page))
        main { model.redo(InkEditorController()) }; saved()
        open()
        assertEquals(16, model.uiState.value.objects.size)
        val heading = model.uiState.value.objects.last()
        assertEquals(6, heading.toContentObject()!!.headingLevel)
        main { model.saveHeading(heading.id, "Revised heading", 2, Color.RED) }; saved()
        val edited = model.uiState.value.objects.single { it.id == heading.id }.toContentObject()!!
        assertEquals("Revised heading", edited.text); assertEquals(2, edited.headingLevel)
        assertEquals(headingSize(2), edited.fontSize, 0f)
        assertTrue(edited.bold)
    }

    @Test fun tableRowsColumnsAndWholeTableSurviveEditsTransformsDeleteUndoAndReopen() = runBlocking {
        val original = TableSpec(cells = List(3) { r -> List(3) { c -> "$r:$c" } })
        main { model.saveTable(null, original) }; saved()
        val id = model.uiState.value.selectedIds.single()
        val resized = original.resizeRow(1, 64f).resizeColumn(2, 160f).deleteRow(0).deleteColumn(1)
            .addRow().addColumn().cell(2, 2, "New cell")
        main { model.saveTable(id, resized); model.transformSelection(80f, 60f, 1.5f) }; saved()
        open()
        val content = model.uiState.value.objects.single { it.id == id }.toContentObject()!!
        assertEquals(resized, content.table); assertEquals(1.5, content.transform.scale, 0.0)
        assertEquals("1:0", content.table!!.cells[0][0]); assertEquals("New cell", content.table.cells[2][2])
        main { model.deleteInsertedObject(id) }; saved()
        assertTrue(model.uiState.value.objects.isEmpty())
        main { model.undo(InkEditorController()) }; saved()
        assertEquals(content.entity, model.uiState.value.objects.single())
        main { model.redo(InkEditorController()) }; saved()
        assertTrue(model.uiState.value.objects.isEmpty())
    }

    @Test fun structuredObjectsCutAndPasteAcrossPagesWithoutLosingTheirTypesOrData() = runBlocking {
        main {
            model.saveShape(null, ShapeSpec(ShapeKind.CIRCLE, 120f, 120f, Color.RED, true))
            model.saveGraph(null, GraphSpec(-2f, 6f, -4f, 8f, .5f, 2f))
            model.saveHeading(null, "Coordinates", 3, Color.BLUE)
            model.saveTable(null, TableSpec().cell(0, 0, "X"))
            model.selectLasso(listOf(PointF(-10000f, -10000f), PointF(10000f, -10000f), PointF(10000f, 10000f), PointF(-10000f, 10000f)))
        }; saved()
        val original = model.uiState.value.objects
        assertEquals(4, model.uiState.value.selectedIds.size)
        val source = model.uiState.value.page!!.id
        main { model.cutSelection() }; saved()
        assertTrue(fixture.editor.pageObjects(source).isEmpty())
        main { model.nextPage() }; await { model.uiState.value.page!!.id != source && !model.uiState.value.pageBusy }
        main { model.pasteSelection() }; saved()
        val target = model.uiState.value.page!!.id
        open()
        val copied = fixture.editor.pageObjects(target)
        assertEquals(original.map { it.type }, copied.map { it.type })
        copied.zip(original).forEach { (new, old) ->
            assertNotEquals(old.id, new.id)
            val newJson = JSONObject(new.payload); val oldJson = JSONObject(old.payload)
            listOf("transformX", "transformY").forEach { newJson.remove(it); oldJson.remove(it) }
            assertEquals(oldJson.toString(), newJson.toString())
        }
    }

    @Test fun invalidGraphsAndTablesAreRejectedAndAllObjectsRender() {
        listOf(GraphSpec(xStep = 0f), GraphSpec(xMin = 2f), GraphSpec(xMax = Float.NaN), GraphSpec(xStep = .001f))
            .forEach { assertTrue(runCatching { it.json() }.isFailure) }
        assertTrue(runCatching { TableSpec(rows = emptyList()).json() }.isFailure)
        assertTrue(runCatching { TableSpec(columns = listOf(-2f)).json() }.isFailure)
        val samples = ShapeKind.entries.map { kind -> SHAPE_OBJECT_TYPE to ShapeSpec(kind, 120f, 120f, Color.RED, true).json() } +
            listOf(GRAPH_OBJECT_TYPE to GraphSpec().json(), TABLE_OBJECT_TYPE to TableSpec().cell(0, 0, "Sample").json())
        samples.forEach { (type, json) ->
            val objectContent = EditableObjectEntity(UUID.randomUUID().toString(), "page", type, json.toString(), 0, 0).toContentObject()!!
            val bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap); canvas.drawColor(Color.WHITE); drawStructuredContent(canvas, objectContent)
                var ink = 0
                for (y in 0 until 350) for (x in 0 until 350) if (bitmap.getPixel(x, y) != Color.WHITE) ink++
                assertTrue("$type must render visible content", ink > 30)
            } finally { bitmap.recycle() }
        }
    }

    @Test fun newObjectsExportToPdfAndUnknownFutureObjectsAreRetained() = runBlocking {
        val page = model.uiState.value.page!!
        val unknown = EditableObjectEntity(UUID.randomUUID().toString(), page.id, "future_object_v99", "{}", 1, 1)
        fixture.db.editorDao().insertObject(unknown)
        main {
            model.saveShape(null, ShapeSpec(ShapeKind.SQUARE, 80f, 80f, Color.RED, true))
            val b = model.uiState.value.objects.last().toContentObject()!!.bounds
            model.transformSelection(20f - b.left, 20f - b.top, 1f)
            model.saveGraph(null, GraphSpec(width = 180f, height = 180f))
            val g = model.uiState.value.objects.last().toContentObject()!!.bounds
            model.transformSelection(130f - g.left, 20f - g.top, 1f)
            model.saveHeading(null, "Exported heading", 1, Color.BLUE)
            val h = model.uiState.value.objects.last().toContentObject()!!.bounds
            model.transformSelection(20f - h.left, 220f - h.top, 1f)
            model.saveTable(null, TableSpec().cell(0, 0, "Saved table"))
            val t = model.uiState.value.objects.last().toContentObject()!!.bounds
            model.transformSelection(20f - t.left, 340f - t.top, 1f)
        }; saved()
        assertTrue(fixture.editor.pageObjects(page.id).any { it == unknown })
        val output = File(fixture.root, "structured.pdf")
        PdfExporter(fixture.context, fixture.editor, fixture.pdf, { output.outputStream() })
            .exportSelectedPage(notebook, page.id, Uri.fromFile(output)) {}
        ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                renderer.openPage(0).use { pdfPage ->
                    val bitmap = Bitmap.createBitmap(pdfPage.width * 2, pdfPage.height * 2, Bitmap.Config.ARGB_8888)
                    try {
                        pdfPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val red = bitmap.getPixel(60 * 2, 60 * 2)
                        assertTrue(Color.red(red) > 230 && Color.green(red) < 30 && Color.blue(red) < 30)
                        fun pixels(left: Int, top: Int, right: Int, bottom: Int, matches: (Int) -> Boolean): Int {
                            var count = 0
                            for (y in top * 2 until bottom * 2) for (x in left * 2 until right * 2) {
                                val color = bitmap.getPixel(x, y)
                                if (matches(color)) count++
                            }
                            return count
                        }
                        assertTrue("Graph axes are drawn", pixels(224, 50, 229, 180) {
                            Color.red(it) < 100 && Color.green(it) < 120 && Color.blue(it) < 130
                        } > 100)
                        assertTrue("Heading keeps its blue text", pixels(20, 220, 340, 290) {
                            Color.blue(it) > 150 && Color.red(it) < 80 && Color.green(it) < 100
                        } > 100)
                        val header = bitmap.getPixel(280 * 2, 350 * 2)
                        assertTrue("Table header is drawn", kotlin.math.abs(Color.red(header) - 228) < 12 &&
                            kotlin.math.abs(Color.green(header) - 236) < 12 && kotlin.math.abs(Color.blue(header) - 227) < 12)
                    } finally { bitmap.recycle() }
                }
            }
        }
    }
}
