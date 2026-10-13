package com.ramst.notein.editor

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ramst.notein.storage.NoteinDatabase
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class Phase6MigrationTest {
    @Test fun phase4MigrationKeepsNotebookSelectionMixedObjectsAndTrash() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "phase6-migration-${UUID.randomUUID()}.db"; val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        val schema = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets
            .open("com.ramst.notein.storage.NoteinDatabase/4.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        val old = SQLiteDatabase.openOrCreateDatabase(file, null)
        val objects = listOf(record(pageId = "page").toEntity(), phase4Text("page"), phase4Image("page"))
        try {
            val entities = schema.getJSONArray("entities")
            repeat(entities.length()) { i ->
                val entity = entities.getJSONObject(i); val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace('$' + "{TABLE_NAME}", table))
                val indices = entity.getJSONArray("indices")
                repeat(indices.length()) { old.execSQL(indices.getJSONObject(it).getString("createSql").replace('$' + "{TABLE_NAME}", table)) }
            }
            val setup = schema.getJSONArray("setupQueries"); repeat(setup.length()) { old.execSQL(setup.getString(it)) }
            old.execSQL("INSERT INTO notebooks VALUES ('notebook','Kept notebook',NULL,1,2,1,2,'standard','page')")
            old.execSQL("INSERT INTO pages VALUES ('page','notebook',0,1,2,-300,100,1.25,'pen',-15066082,6,0,'grid',595,842)")
            objects.forEach { old.execSQL("INSERT INTO editable_objects VALUES (?,?,?,?,?,?)", arrayOf<Any>(it.id, it.pageId, it.type, it.payload, it.createdAt, it.updatedAt)) }
            old.version = 4
        } finally { old.close() }
        val db = Room.databaseBuilder(context, NoteinDatabase::class.java, name).addMigrations(NoteinDatabase.MIGRATION_4_5, NoteinDatabase.MIGRATION_5_6, NoteinDatabase.MIGRATION_6_7, NoteinDatabase.MIGRATION_7_8).build()
        try {
            val page = db.editorDao().firstPage("notebook")!!
            assertNull(page.pdfPageIndex); assertEquals("grid", page.paperBackground); assertEquals(-300f, page.viewportX, 0f)
            assertEquals(objects.associate { it.id to it.payload }, db.editorDao().pageObjects("page").associate { it.id to it.payload })
            db.openHelper.readableDatabase.query("SELECT selected_page_id,is_trashed,trashed_at FROM notebooks").use {
                assertTrue(it.moveToFirst()); assertEquals("page", it.getString(0)); assertEquals(1, it.getInt(1)); assertEquals(2, it.getInt(2))
            }
            assertNull(db.pdfDao().document("notebook"))
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
