package com.ramst.notein.editor

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ramst.notein.pdf.PdfExporter
import com.ramst.notein.pdf.PdfRepository
import com.ramst.notein.storage.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class Phase9PaperTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @Test fun denseAndFarOriginPaperRenderWithoutUnboundedLoopsOrChangingTemplate() {
        val bitmap=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888)
        try {
            for(pattern in listOf("lined","grid","dots")) {
                val template=PaperTemplate(pattern,0f,0f,2f,"bold",PaperColor.DARK_GRAY.argb)
                val canvas=Canvas(bitmap)
                canvas.save();canvas.scale(.000001f,.000001f)
                PaperRenderer.draw(canvas,template,0f,0f,100_000_000f,100_000_000f)
                canvas.restore()
                canvas.save();canvas.translate(-10_000_000_000f,0f)
                PaperRenderer.draw(canvas,template,10_000_000_000f,0f,10_000_005_000f,5000f)
                canvas.restore()
                assertEquals(2f,template.spacingMm,0f)
            }
        } finally {bitmap.recycle()}
    }
    @Test fun importedPdfKeepsOriginalPaperAndNeverAppendsOrAcceptsTemplateReplacement() = runBlocking {
        val name="phase9-pdf-${UUID.randomUUID()}.db"
        val db=Room.databaseBuilder(context,NoteinDatabase::class.java,name).build()
        val source=File(context.cacheDir,"phase9-import-${UUID.randomUUID()}.pdf")
        var local:File?=null
        try {
            InstrumentationRegistry.getInstrumentation().context.assets.open("phase6/mixed.pdf").use {input->source.outputStream().use {input.copyTo(it)}}
            val pdf=PdfRepository(context,db.pdfDao());val id=pdf.import(Uri.fromFile(source)).notebookId
            local=File(pdf.document(id)!!.localPath)
            val before=local!!.readBytes();val repo=EditorRepository(db.editorDao());val pages=repo.pages(id)
            val last=pages.last()
            repo.saveContentSnapshot(listOf(record(pageId=last.id).toEntity(),phase4Text(last.id)),last)
            assertEquals(pages.size,repo.pages(id).size)
            try {repo.updatePageTemplate(last.id,PaperTemplate("dots",420f,595f,2f,"bold",Color.RED));fail("PDF paper must be protected")}
            catch (_:IllegalStateException) {}
            assertEquals(pages.map {it.paperTemplate()},repo.pages(id).map {it.paperTemplate()})
            assertArrayEquals(before,local!!.readBytes());assertEquals(pages.size,repo.defaultExportSnapshot(id).size)
        } finally {local?.delete();source.delete();db.close();context.deleteDatabase(name)}
    }
    @Test fun defaultPdfOmitsOnlyUntouchedTrailingAutomaticPageAndExplicitExportIncludesIt() = runBlocking {
        val name="phase9-export-${UUID.randomUUID()}.db"
        val db=Room.databaseBuilder(context,NoteinDatabase::class.java,name).build()
        val output=File(context.cacheDir,"phase9-export-${UUID.randomUUID()}.pdf")
        try {
            val repo=EditorRepository(db.editorDao())
            val id=NotebookRepository(db.notebookDao(),db.folderDao()).createNotebook("Export fixture",null)
            val page=repo.firstPage(id)!!
            repo.saveContentSnapshot(listOf(record(pageId=page.id).toEntity()),page)
            val automatic=repo.pages(id).last()
            val exporter=PdfExporter(context,repo,PdfRepository(context,db.pdfDao()),{File(it.path!!).outputStream()},{File(it.path!!).delete();Unit})
            fun pageCount()=ParcelFileDescriptor.open(output,ParcelFileDescriptor.MODE_READ_ONLY).use {fd->PdfRenderer(fd).use {it.pageCount}}
            exporter.exportNotebook(id,Uri.fromFile(output)) {};assertEquals(1,pageCount())
            exporter.exportSelectedPage(id,automatic.id,Uri.fromFile(output)) {};assertEquals(1,pageCount())
            val manual=repo.createPage(id,NotebookType.STANDARD)
            exporter.exportNotebook(id,Uri.fromFile(output)) {};assertEquals(3,pageCount())
            repo.deletePage(manual,id)
            repo.saveContentSnapshot(listOf(phase4Text(automatic.id)),automatic)
            repo.saveContentSnapshot(emptyList(),automatic) // Written-then-undone automatic page remains in export.
            exporter.exportNotebook(id,Uri.fromFile(output)) {};assertEquals(2,pageCount())
            repo.deletePage(repo.pages(id).last().id,id)
            repo.deletePage(page.id,id)
            exporter.exportNotebook(id,Uri.fromFile(output)) {};assertEquals(1,pageCount())
        } finally {output.delete();db.close();context.deleteDatabase(name)}
    }
    @Test fun everyPatternSizeOrientationWeightColorAndSpacingRendersInDocumentCoordinates() {
        val bitmap=Bitmap.createBitmap(200,200,Bitmap.Config.ARGB_8888)
        try {
            for (pattern in PaperBackground.entries) for (size in PaperSize.entries) for (landscape in listOf(false,true))
                for (weight in PaperPatternWeight.entries) for (color in PaperColor.entries.map {it.argb} + 0xFF123ABC.toInt()) {
                    val spacings=if(pattern==PaperBackground.LINED) listOf(4f,7f,12f) else listOf(2f,5f,10f)
                    spacings.forEach { spacing ->
                        val template=PaperTemplate(pattern.storageValue,if(landscape)size.height else size.width,
                            if(landscape)size.width else size.height,spacing,weight.storageValue,color)
                        val canvas=Canvas(bitmap); PaperRenderer.draw(canvas,template,0f,0f,200f,200f)
                        assertEquals(color,bitmap.getPixel(3,3))
                        if(pattern!=PaperBackground.BLANK) {
                            val position=(spacing*PaperRenderer.POINTS_PER_MM).toInt()
                            assertTrue("Missing $pattern at $spacing mm", bitmap.getPixel(position,position)!=color)
                        }
                    }
                }
        } finally {bitmap.recycle()}
        assertEquals(PaperBackground.DOTS,PaperBackground.fromStorage("dots"))
        assertNull(parsePaperColor("-1"));assertNull(parsePaperColor("ABC"));assertEquals(0xFF123ABC.toInt(),parsePaperColor("#123ABC"))
    }

    @Test fun editorAndPdfMatchPaperDimensionsColorsAndSpacingAcrossAllLayoutsAndSizes() = runBlocking {
        val name="phase9-paper-${UUID.randomUUID()}.db"
        val db=Room.databaseBuilder(context,NoteinDatabase::class.java,name).build()
        val repo=EditorRepository(db.editorDao()); val notebooks=NotebookRepository(db.notebookDao(),db.folderDao())
        val pdf=PdfRepository(context,db.pdfDao()); val output=File(context.filesDir,"phase9-evidence/templates.pdf").apply {parentFile!!.mkdirs()}
        try {
            val id=notebooks.createNotebook("Paper matrix",null,autoMatchingPages=false)
            val templates=buildList {
                for(pattern in PaperBackground.entries) for(size in PaperSize.entries) for(landscape in listOf(false,true)) {
                    val index=this.size
                    add(PaperTemplate(pattern.storageValue,if(landscape)size.height else size.width,if(landscape)size.width else size.height,
                        if(pattern==PaperBackground.LINED) listOf(4f,7f,12f)[index%3] else listOf(2f,5f,10f)[index%3],
                        PaperPatternWeight.entries[index%3].storageValue,PaperColor.entries[index%6].argb))
                }
                add(PaperTemplate("dots",792f,612f,3.5f,"bold",0xFF123ABC.toInt()))
            }
            templates.forEachIndexed {index,template->
                val pageId=if(index==0) repo.firstPage(id)!!.id else repo.createPage(id,NotebookType.STANDARD)
                repo.updatePageTemplate(pageId,template)
            }
            val pages=repo.pages(id)
            PdfExporter(context,repo,pdf,{ File(it.path!!).outputStream() },{File(it.path!!).delete();Unit})
                .exportPages(id,pages.map {it.id},Uri.fromFile(output)) {}
            ParcelFileDescriptor.open(output,ParcelFileDescriptor.MODE_READ_ONLY).use {fd->PdfRenderer(fd).use {renderer->
                assertEquals(25,renderer.pageCount)
                pages.forEachIndexed {index,page->renderer.openPage(index).use {pdfPage->
                    assertEquals(page.pageWidth.toInt(),pdfPage.width);assertEquals(page.pageHeight.toInt(),pdfPage.height)
                    val actual=Bitmap.createBitmap(pdfPage.width,pdfPage.height,Bitmap.Config.ARGB_8888)
                    val expected=Bitmap.createBitmap(pdfPage.width,pdfPage.height,Bitmap.Config.ARGB_8888)
                    try {
                        pdfPage.render(actual,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        PaperRenderer.draw(Canvas(expected),page.paperTemplate())
                        // Export uses JPEG, so compare stable paper/intersection samples with compression tolerance.
                        fun close(a:Int,b:Int)=kotlin.math.abs(Color.red(a)-Color.red(b))<40 && kotlin.math.abs(Color.green(a)-Color.green(b))<40 && kotlin.math.abs(Color.blue(a)-Color.blue(b))<40
                        assertTrue("PDF color mismatch at $index",close(expected.getPixel(3,3),actual.getPixel(3,3)))
                        val step=page.paperSpacingMm*PaperRenderer.POINTS_PER_MM
                        if(page.paperBackground!="blank") for(m in 1..4) {
                            val pos=(m*step).toInt()
                            assertTrue("PDF ruling mismatch at $index/$pos",close(expected.getPixel(pos,pos),actual.getPixel(pos,pos)))
                        }
                        for(scale in listOf(.25f,1f,2f)) {
                            val viewBitmap=Bitmap.createBitmap(240,240,Bitmap.Config.ARGB_8888)
                            try {
                                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                                    val view=InkCanvasView(context)
                                    view.setDocument(false,page.paperBackground,page.pageWidth,page.pageHeight,page.paperSpacingMm,page.paperPatternWeight,page.paperBackgroundColor)
                                    view.setEditorState(InkTool.Pen,Color.BLACK,6f,false,0f,0f,scale)
                                    val exact=android.view.View.MeasureSpec.makeMeasureSpec(240,android.view.View.MeasureSpec.EXACTLY)
                                    view.measure(exact,exact)
                                    view.layout(0,0,240,240);view.draw(Canvas(viewBitmap))
                                }
                                val sample=(3f*scale).toInt().coerceAtLeast(1)
                                assertTrue("Editor background mismatch at $index/$scale", close(page.paperBackgroundColor,viewBitmap.getPixel(sample,sample)))
                            } finally {viewBitmap.recycle()}
                        }
                    } finally {actual.recycle();expected.recycle()}
                } }
            } }
        } finally {db.close();context.deleteDatabase(name)}
    }
}
