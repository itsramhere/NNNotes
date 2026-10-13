package com.ramst.notein

import android.app.Application
import com.ramst.notein.storage.NoteinDatabase
import com.ramst.notein.storage.EditorRepository
import com.ramst.notein.storage.NotebookRepository
import com.ramst.notein.pdf.PdfRepository

class NoteinApplication : Application() {
    val container: AppContainer by lazy {
        val database = NoteinDatabase.create(this)
        AppContainer(
            notebookRepository = NotebookRepository(database.notebookDao(), database.folderDao()),
            editorRepository = EditorRepository(database.editorDao()),
            pdfRepository = PdfRepository(this, database.pdfDao()),
        )
    }
}

class AppContainer(
    val notebookRepository: NotebookRepository,
    val editorRepository: EditorRepository,
    val pdfRepository: PdfRepository,
)
