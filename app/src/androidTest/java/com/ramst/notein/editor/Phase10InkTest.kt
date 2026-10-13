@file:OptIn(androidx.ink.brush.ExperimentalInkCustomBrushApi::class)
package com.ramst.notein.editor

import android.graphics.*
import androidx.ink.brush.StockBrushes
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ramst.notein.storage.PaperColor
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Phase10InkTest {
    @Test fun defaultPenWidthsStayDistinctInPdfExportAcrossAllPaperColors() = kotlinx.coroutines.runBlocking {
        val f = Phase10Fixture()
        try {
            val id = f.notebooks.createNotebook("Pen export", null, autoMatchingPages = false)
            for ((index, paper) in PaperColor.entries.withIndex()) {
                val pageId = if (index == 0) f.editor.firstPage(id)!!.id else f.editor.createPage(id, com.ramst.notein.storage.NotebookType.STANDARD)
                f.editor.updatePageTemplate(pageId, com.ramst.notein.storage.PaperTemplate(backgroundColor = paper.argb))
                val page = f.editor.pages(id).first { it.id == pageId }
                val objects = listOf(InkTool.Pencil, InkTool.BallPen, InkTool.Pen).mapIndexed { row, tool ->
                    val input = MutableStrokeInputBatch().add(InputToolType.STYLUS, 100f, 100f + row * 60f, 0, pressure = .5f)
                        .add(InputToolType.STYLUS, 300f, 100f + row * 60f, 200, pressure = .5f)
                    strokeRecordFrom(java.util.UUID.randomUUID().toString(), pageId,
                        Stroke(brushFor(tool, Color.BLACK, tool.defaultSize), input), tool, Color.BLACK, tool.defaultSize).toEntity()
                }
                f.editor.saveContentSnapshot(objects, page)
            }
            val evidence = java.io.File(f.base.filesDir, "phase10-evidence").apply { mkdirs() }
            val output = java.io.File(evidence, "pen-colors.pdf")
            com.ramst.notein.pdf.PdfExporter(f.context, f.editor, f.pdf,
                { java.io.File(it.path!!).outputStream() }, { java.io.File(it.path!!).delete(); Unit })
                .exportNotebook(id, android.net.Uri.fromFile(output)) {}
            PaperColor.entries.forEachIndexed { index, paper ->
                val bitmap = com.ramst.notein.pdf.renderPdfBitmap(output.path, index, 1190, 1684)
                try {
                    val background = bitmap.getPixel(400, 140)
                    val widths = (0..2).map { row ->
                        val y = 200 + row * 120
                        (y - 25..y + 25).count { kotlin.math.abs(Color.red(bitmap.getPixel(400, it)) - Color.red(background)) > 10 }
                    }
                    assertTrue("Exported widths on $paper: $widths", widths[0] > 0 && widths[0] < widths[1] && widths[1] < widths[2])
                } finally { bitmap.recycle() }
            }
        } finally { f.close() }
    }
    @Test fun defaultsFractionalCodecAndLegacyBrushesPreserveRecordedWidthAndPressure() {
        assertEquals(.5f, InkTool.Pencil.defaultSize, 0f); assertEquals(2f, InkTool.BallPen.defaultSize, 0f)
        for (tool in listOf(InkTool.Pencil, InkTool.BallPen, InkTool.Pen, InkTool.Highlighter)) {
            val width = if (tool == InkTool.Pencil) .375f else tool.defaultSize
            val decoded = record(tool = tool, size = width).decode()!!.stroke
            assertEquals(width, decoded.brush.size, .00001f)
            assertEquals(.5f, decoded.inputs[0].pressure, .00001f)
        }
        val saved = record(size = 3.75f)
        val json = JSONObject(saved.payload).put("tool", "pen").also { it.remove("brushVersion") }
        assertEquals(StockBrushes.pressurePen(), saved.copy(payload = json.toString()).decode()!!.stroke.brush.family)
        val highlighter = record(tool = InkTool.Highlighter, size = 15f)
        val legacy = JSONObject(highlighter.payload).also { it.remove("brushVersion") }
        assertEquals(StockBrushes.highlighter(), highlighter.copy(payload = legacy.toString()).decode()!!.stroke.brush.family)
        val custom = JSONObject(saved.payload).put("brushVersion", 2)
        assertEquals(brushFor(InkTool.Pen, custom.getInt("color"), 3.75f, brushVersion = 2),
            saved.copy(payload = custom.toString()).decode()!!.stroke.brush)
        assertEquals(3, JSONObject(saved.payload).getInt("brushVersion"))
    }
    private fun stroke(tool: InkTool, pressure: Float): Stroke = Stroke(brushFor(tool, Color.BLACK, tool.defaultSize),
        MutableStrokeInputBatch().add(InputToolType.STYLUS, 10f, 50f, 0, pressure = pressure)
            .add(InputToolType.STYLUS, 190f, 50f, 200, pressure = pressure))
    private fun thickness(stroke: Stroke): Float {
        val b = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        try {
            val c = Canvas(b); c.drawColor(Color.WHITE); c.scale(2f, 2f)
            CanvasStrokeRenderer.create().draw(c, stroke, Matrix().apply { setScale(2f, 2f) })
            return (0 until b.height).count { Color.red(b.getPixel(200, it)) < 250 }.toFloat()
        } finally { b.recycle() }
    }
    @Test fun defaultWidthsRenderInOrderAndInkRespondsToPressureWhileBallPenStaysConstant() {
        val pencil = thickness(stroke(InkTool.Pencil, .5f)); val ball = thickness(stroke(InkTool.BallPen, .5f)); val ink = thickness(stroke(InkTool.Pen, .5f))
        assertTrue("pencil=$pencil ball=$ball ink=$ink", pencil > 0 && pencil < ball && ball < ink)
        assertTrue(thickness(stroke(InkTool.Pen, .9f)) > thickness(stroke(InkTool.Pen, .1f)))
        assertEquals(thickness(stroke(InkTool.BallPen, .1f)), thickness(stroke(InkTool.BallPen, .9f)), 0f)
        for (paper in PaperColor.entries) {
            val b = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
            try {
                val c = Canvas(b); c.drawColor(paper.argb); c.scale(2f, 2f)
                CanvasStrokeRenderer.create().draw(c, stroke(InkTool.Pencil, .5f), Matrix().apply { setScale(2f, 2f) })
                assertTrue("Pencil absent on $paper", (80..120).any { b.getPixel(200, it) != paper.argb })
            } finally { b.recycle() }
        }
    }
}
