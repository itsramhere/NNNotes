package com.ramst.notein.editor

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ramst.notein.storage.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Run prepare and verify as separate instrumentation processes with an intervening force-stop. */
@RunWith(AndroidJUnit4::class)
class Phase9ProcessTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val name="phase9-process-fixture.db"
    private val evidence get()=File(context.filesDir,"phase9-evidence/restart.json").apply {parentFile!!.mkdirs()}
    @Test fun prepare()=runBlocking {
        check(!context.getDatabasePath(name).exists()) {"An earlier Phase 9 process fixture still exists; inspect it before rerunning."}
        val db=Room.databaseBuilder(context,NoteinDatabase::class.java,name).build()
        try {
            val repo=EditorRepository(db.editorDao())
            val template=PaperTemplate("dots",792f,612f,3.5f,"fine",0xFF123ABC.toInt())
            val id=NotebookRepository(db.notebookDao(),db.folderDao()).createNotebook("Restart fixture",null,template=template)
            val page=repo.firstPage(id)!!;val ink=record(pageId=page.id).toEntity()
            repo.selectPage(id,page.id)
            repo.saveContentSnapshot(listOf(ink),page.copy(viewportX=-200f,viewportY=90f,viewportScale=1.5f))
            evidence.writeText(JSONObject().put("notebookId",id).put("pageId",page.id).put("inkPayload",ink.payload).toString())
            assertEquals(2,repo.pages(id).size)
        } finally {db.close()}
    }
    @Test fun verify()=runBlocking {
        val saved=JSONObject(evidence.readText());val db=Room.databaseBuilder(context,NoteinDatabase::class.java,name).build()
        try {
            val repo=EditorRepository(db.editorDao());val id=saved.getString("notebookId");val pages=repo.pages(id)
            assertEquals(2,pages.size);assertEquals(saved.getString("pageId"),repo.notebook(id)!!.selectedPageId)
            assertEquals(saved.getString("inkPayload"),repo.pageObjects(pages.first().id).single().payload)
            val expected=PaperTemplate("dots",792f,612f,3.5f,"fine",0xFF123ABC.toInt())
            assertEquals(expected,pages.first().paperTemplate());assertEquals(expected,pages.last().paperTemplate())
            assertEquals(-200f,pages.first().viewportX,0f);assertEquals(0f,pages.last().viewportX,0f)
            repo.saveContentSnapshot(repo.pageObjects(pages.first().id),pages.first())
            assertEquals(2,repo.pages(id).size)
            assertEquals(1,repo.defaultExportSnapshot(id).size)
        } finally {db.close();context.deleteDatabase(name)}
    }
}
