package com.ramst.notein.editor

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ramst.notein.storage.*
import com.ramst.notein.pdf.PdfRepository
import java.io.File
import java.util.UUID

/** Separate files as well as databases: abandoned-import recovery must never see user files. */
internal class Phase10Fixture(val token: String = UUID.randomUUID().toString()) {
    val base = ApplicationProvider.getApplicationContext<Context>()
    val root = File(base.cacheDir, "phase10-$token").apply { mkdirs() }
    val context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
    }
    val name = "phase10-$token.db"
    val db = Room.databaseBuilder(context, NoteinDatabase::class.java, name).build()
    val editor = EditorRepository(db.editorDao())
    val notebooks = NotebookRepository(db.notebookDao(), db.folderDao())
    val pdf = PdfRepository(context, db.pdfDao())
    suspend fun notebook(type: NotebookType = NotebookType.STANDARD) = notebooks.createNotebook(
        "Phase 10 fixture", null, type, PaperTemplate("grid", 420f, 595f, 5f, "bold", PaperColor.CREAM.argb))
    fun source(color: Int = Color.BLUE, count: Int = 3): File = File(root, "source-${UUID.randomUUID()}.pdf").also { file ->
        val document = PdfDocument()
        try {
            repeat(count) { index ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(200 + index * 20, 300 + index * 10, index + 1).create())
                page.canvas.drawColor(color)
                document.finishPage(page)
            }
            file.outputStream().use { document.writeTo(it) }
        } finally { document.close() }
    }
    suspend fun prepare(color: Int = Color.BLUE, count: Int = 3) = pdf.prepare(Uri.fromFile(source(color, count))) {}
    fun close() { db.close(); base.deleteDatabase(name); root.deleteRecursively() }
}
