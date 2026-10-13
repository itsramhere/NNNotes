package com.ramst.notein.editor

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ramst.notein.storage.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PageLayoutMigrationTest {
    @Test fun versionSevenRetainsNotesAndUsesTheSelectedLockedPageZoomForTheNotebook() = runBlocking {
        val f = Phase10Fixture(); f.db.close()
        val schema = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets
            .open("com.ramst.notein.storage.NoteinDatabase/7.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        val entities = schema.getJSONArray("entities")
        val old = SQLiteDatabase.openOrCreateDatabase(f.base.getDatabasePath(f.name), null)
        val ink = record(pageId = "locked-second").toEntity()
        fun insert(table: String, values: Map<String, Any?>) {
            val entity = (0 until entities.length()).map { entities.getJSONObject(it) }.first { it.getString("tableName") == table }
            val fields = entity.getJSONArray("fields"); val row = ContentValues()
            repeat(fields.length()) { index ->
                val field = fields.getJSONObject(index); val column = field.getString("columnName")
                val value = if (column in values) values[column] else if (field.optBoolean("notNull")) {
                    if (field.getString("affinity") == "TEXT") "" else 0
                } else null
                when (value) {
                    null -> row.putNull(column)
                    is String -> row.put(column, value)
                    is Float -> row.put(column, value)
                    is Long -> row.put(column, value)
                    is Int -> row.put(column, value)
                    else -> error("unsupported fixture")
                }
            }
            old.insertOrThrow(table, null, row)
        }
        try {
            repeat(entities.length()) { index ->
                val entity = entities.getJSONObject(index); val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace('$' + "{TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                repeat(indices.length()) { old.execSQL(indices.getJSONObject(it).getString("createSql").replace('$' + "{TABLE_NAME}", table)) }
            }
            val setup = schema.getJSONArray("setupQueries"); repeat(setup.length()) { old.execSQL(setup.getString(it)) }
            insert("notebooks", mapOf("id" to "locked", "title" to "Existing notebook", "notebook_type" to "standard", "viewport_locked" to 1, "selected_page_id" to "locked-second"))
            insert("notebooks", mapOf("id" to "unlocked", "title" to "Other notebook", "notebook_type" to "standard"))
            listOf("locked-first" to 1f, "locked-second" to 2.25f).forEachIndexed { index, (id, scale) ->
                insert("pages", mapOf("id" to id, "notebook_id" to "locked", "sort_index" to index,
                    "page_width" to 420f, "page_height" to 595f, "viewport_scale" to scale, "viewport_y" to -100f))
            }
            insert("pages", mapOf("id" to "other", "notebook_id" to "unlocked", "page_width" to 420f, "page_height" to 595f, "viewport_scale" to 1.25f))
            insert("editable_objects", mapOf("id" to ink.id, "page_id" to ink.pageId, "type" to ink.type,
                "payload" to ink.payload, "created_at" to ink.createdAt, "updated_at" to ink.updatedAt))
            old.version = 7
        } finally { old.close() }
        val db = Room.databaseBuilder(f.context, NoteinDatabase::class.java, f.name).addMigrations(NoteinDatabase.MIGRATION_7_8).build()
        try {
            val editor = EditorRepository(db.editorDao())
            assertEquals("locked-second", editor.notebook("locked")!!.selectedPageId)
            assertEquals("horizontal", editor.notebook("locked")!!.pageLayout)
            assertEquals(2.25f, editor.notebook("locked")!!.lockedZoomScale!!, 0f)
            assertTrue(editor.pages("locked").all { it.viewportScale == 2.25f && it.viewportY == -100f })
            assertEquals(ink, editor.pageObjects("locked-second").single())
            assertNull(editor.notebook("unlocked")!!.lockedZoomScale)
            assertEquals(1.25f, editor.firstPage("unlocked")!!.viewportScale, 0f)
        } finally { db.close(); f.close() }
    }
}
