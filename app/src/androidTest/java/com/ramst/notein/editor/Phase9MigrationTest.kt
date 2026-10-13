package com.ramst.notein.editor

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ramst.notein.storage.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class Phase9MigrationTest {
    @Test fun populatedVersionFivePreservesPaperPixelsInkSelectionTrashAndPdfSource() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "phase9-migration-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val schema = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets
            .open("com.ramst.notein.storage.NoteinDatabase/5.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        val old = SQLiteDatabase.openOrCreateDatabase(file, null)
        val patterns = listOf("blank", "lined", "grid")
        try {
            val entities = schema.getJSONArray("entities")
            repeat(entities.length()) { i ->
                val entity = entities.getJSONObject(i); val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace('$' + "{TABLE_NAME}", table))
                val indices = entity.getJSONArray("indices")
                repeat(indices.length()) { old.execSQL(indices.getJSONObject(it).getString("createSql").replace('$' + "{TABLE_NAME}", table)) }
            }
            val setup = schema.getJSONArray("setupQueries"); repeat(setup.length()) { old.execSQL(setup.getString(it)) }
            old.execSQL("INSERT INTO folders VALUES ('folder','Retained folder',1,2)")
            patterns.forEach { pattern ->
                old.execSQL("INSERT INTO notebooks VALUES (?,?, 'folder',1,2,0,NULL,'standard',?)", arrayOf(pattern, pattern, "page-$pattern"))
                old.execSQL("INSERT INTO pages VALUES (?, ?,0,1,2,-300,100,1.25,'pen',-15066082,6,0,?,842,595,NULL)", arrayOf("page-$pattern",pattern,pattern))
                val ink = record(pageId = "page-$pattern").toEntity()
                old.execSQL("INSERT INTO editable_objects VALUES (?,?,?,?,?,?)", arrayOf<Any>(ink.id,ink.pageId,ink.type,ink.payload,ink.createdAt,ink.updatedAt))
            }
            old.execSQL("INSERT INTO notebooks VALUES ('trash','Trash',NULL,1,2,1,2,'standard','trash-page')")
            old.execSQL("INSERT INTO pages VALUES ('trash-page','trash',0,1,2,0,0,1,'pen',-15066082,6,0,'grid',595,842,NULL)")
            old.execSQL("INSERT INTO notebooks VALUES ('pdf','PDF',NULL,1,2,0,NULL,'pdf','pdf-page')")
            old.execSQL("INSERT INTO pages VALUES ('pdf-page','pdf',0,1,2,0,0,1,'pen',-15066082,6,0,'blank',612,792,0)")
            old.execSQL("INSERT INTO pdf_documents VALUES ('pdf','retained-source.pdf',1,1)")
            old.execSQL("INSERT INTO notebooks VALUES ('infinite','Canvas',NULL,1,2,0,NULL,'infinite','canvas')")
            old.execSQL("INSERT INTO pages VALUES ('canvas','infinite',0,1,2,-900,500,.25,'pen',-15066082,6,0,'grid',0,0,NULL)")
            old.version = 5
        } finally { old.close() }
        val db = Room.databaseBuilder(context, NoteinDatabase::class.java, name).addMigrations(NoteinDatabase.MIGRATION_5_6, NoteinDatabase.MIGRATION_6_7, NoteinDatabase.MIGRATION_7_8).build()
        try {
            patterns.forEach { pattern ->
                val page = db.editorDao().firstPage(pattern)!!
                assertEquals(842f,page.pageWidth,0f); assertEquals(595f,page.pageHeight,0f)
                assertEquals(-300f,page.viewportX,0f); assertEquals(6f,page.brushSize,0f)
                assertEquals("page-$pattern",db.editorDao().notebook(pattern)!!.selectedPageId)
                assertEquals(1,db.editorDao().pageObjects(page.id).size)
                val expected = Bitmap.createBitmap(842,595,Bitmap.Config.ARGB_8888)
                val actual = Bitmap.createBitmap(842,595,Bitmap.Config.ARGB_8888)
                try {
                    val canvas=Canvas(expected); canvas.drawColor(Color.WHITE)
                    val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.rgb(218,226,235); strokeWidth=1f }
                    val spacing=if (pattern=="grid") 28f else 32f
                    if(pattern!="blank") { var y=0f; while(y<=595f) { canvas.drawLine(0f,y,842f,y,paint);y+=spacing } }
                    if(pattern=="grid") { var x=0f; while(x<=842f) { canvas.drawLine(x,0f,x,595f,paint);x+=spacing } }
                    PaperRenderer.draw(Canvas(actual),page.paperTemplate())
                    // Float mm conversion can shift antialiased edges by one channel value.
                    val a=IntArray(842*595); val b=IntArray(a.size)
                    expected.getPixels(a,0,842,0,0,842,595);actual.getPixels(b,0,842,0,0,842,595)
                    assertTrue("Legacy $pattern paper appearance changed",a.indices.all { kotlin.math.abs(Color.red(a[it])-Color.red(b[it]))<=1 })
                } finally { expected.recycle();actual.recycle() }
            }
            assertEquals("retained-source.pdf",db.pdfDao().document("pdf")!!.localPath)
            assertFalse(db.editorDao().notebook("pdf")!!.autoMatchingPages)
            assertFalse(db.editorDao().notebook("infinite")!!.autoMatchingPages)
            assertEquals(0f,db.editorDao().firstPage("infinite")!!.pageWidth,0f)
            db.openHelper.readableDatabase.query("SELECT is_trashed,trashed_at,folder_id FROM notebooks WHERE id='trash'").use {
                assertTrue(it.moveToFirst());assertEquals(1,it.getInt(0));assertEquals(2L,it.getLong(1))
            }
        } finally { db.close();context.deleteDatabase(name) }
    }
}
