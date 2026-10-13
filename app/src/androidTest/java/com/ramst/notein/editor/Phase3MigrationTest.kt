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
class Phase3MigrationTest {
    @Test fun migratesPhase2AndPhase3SchemasWithoutLosingInkOrViewport() = runBlocking {
        for (oldVersion in listOf(2, 3)) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "phase3-migration-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        val schema = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets
            .open("com.ramst.notein.storage.NoteinDatabase/$oldVersion.json").bufferedReader().use { it.readText() })
            .getJSONObject("database")
        val old = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            val entities = schema.getJSONArray("entities")
            repeat(entities.length()) { index ->
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace('$' + "{TABLE_NAME}", table))
                val indices = entity.getJSONArray("indices")
                repeat(indices.length()) { old.execSQL(indices.getJSONObject(it).getString("createSql").replace('$' + "{TABLE_NAME}", table)) }
            }
            val setup = schema.getJSONArray("setupQueries")
            repeat(setup.length()) { old.execSQL(setup.getString(it)) }
            old.execSQL("INSERT INTO folders VALUES ('folder', 'Kept folder', 1, 1)")
            if (oldVersion == 3) {
                old.execSQL("INSERT INTO notebooks VALUES ('notebook', 'Kept notebook', 'folder', 1, 2, 1, 2, 'standard')")
                old.execSQL("INSERT INTO pages VALUES ('page', 'notebook', 0, 1, 2, -30000, 10000, 0.08, 'pen', -15066082, 6, 0, 'blank', 595, 842)")
            } else {
                old.execSQL("INSERT INTO notebooks (id,title,folder_id,created_at,updated_at,is_trashed,trashed_at) VALUES ('notebook', 'Kept notebook', 'folder', 1, 2, 1, 2)")
                old.execSQL("INSERT INTO pages (id,notebook_id,sort_index,created_at,updated_at,viewport_x,viewport_y,viewport_scale,active_tool,brush_color,brush_size,finger_writing) VALUES ('page', 'notebook', 0, 1, 2, -30000, 10000, 0.08, 'pen', -15066082, 6, 0)")
            }
            old.execSQL("INSERT INTO editable_objects VALUES ('object', 'page', 'legacy', 'kept payload', 1, 2)")
            old.version = oldVersion
        } finally { old.close() }
        val migrated = Room.databaseBuilder(context, NoteinDatabase::class.java, name)
            .addMigrations(NoteinDatabase.MIGRATION_1_2, NoteinDatabase.MIGRATION_2_3, NoteinDatabase.MIGRATION_3_4, NoteinDatabase.MIGRATION_4_5, NoteinDatabase.MIGRATION_5_6, NoteinDatabase.MIGRATION_6_7, NoteinDatabase.MIGRATION_7_8).build()
        try {
            val page = migrated.editorDao().firstPage("notebook")!! // Room validates the migrated schema.
            assertEquals("page", page.id)
            assertEquals("pen", page.activeTool)
            assertEquals(6f, page.brushSize, 0.01f)
            assertEquals(-30000f, page.viewportX, 0.01f)
            assertEquals(0.08f, page.viewportScale, 0.001f)
            assertEquals("blank", page.paperBackground)
            assertEquals(595f, page.pageWidth, 0f)
            assertFalse(page.fingerWriting)
            assertEquals("kept payload", migrated.editorDao().pageObjects("page").single().payload)
            migrated.openHelper.readableDatabase.query("SELECT folder_id, is_trashed, trashed_at FROM notebooks WHERE id = 'notebook'").use {
                assertTrue(it.moveToFirst()); assertEquals("folder", it.getString(0))
                assertEquals(1, it.getInt(1)); assertEquals(2, it.getInt(2))
            }
        } finally { migrated.close(); context.deleteDatabase(name) }
        }
    }
}

