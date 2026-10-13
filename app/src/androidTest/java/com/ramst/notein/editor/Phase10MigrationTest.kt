package com.ramst.notein.editor

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ramst.notein.storage.*
import com.ramst.notein.pdf.*
import android.graphics.Color
import android.net.Uri
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Phase10MigrationTest {
    @Test fun versionSixRetainsLegacyInkHighlighterSourceIdsPagesSelectionAndExport() = runBlocking {
        val f = Phase10Fixture()
        f.db.close()
        val schema = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets
            .open("com.ramst.notein.storage.NoteinDatabase/6.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        val entities = schema.getJSONArray("entities")
        val old = SQLiteDatabase.openOrCreateDatabase(f.base.getDatabasePath(f.name), null)
        val source = f.source(Color.BLUE, 1)
        val legacy = record(pageId = "pen", size = 3.75f).let {
            it.copy(payload = JSONObject(it.payload).put("tool", "pen").also { json -> json.remove("brushVersion") }.toString())
        }
        val highlight = record(pageId = "highlight", tool = InkTool.Highlighter, size = 14f).let {
            it.copy(payload = JSONObject(it.payload).also { json -> json.remove("brushVersion") }.toString())
        }
        fun insert(table: String, values: Map<String, Any?>) {
            val entity = (0 until entities.length()).map { entities.getJSONObject(it) }.first { it.getString("tableName") == table }
            val fields = entity.getJSONArray("fields"); val row = ContentValues()
            repeat(fields.length()) { i ->
                val field = fields.getJSONObject(i); val column = field.getString("columnName")
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
            repeat(entities.length()) { i ->
                val entity = entities.getJSONObject(i); val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace('$' + "{TABLE_NAME}", table))
                val indices = entity.getJSONArray("indices")
                repeat(indices.length()) { old.execSQL(indices.getJSONObject(it).getString("createSql").replace('$' + "{TABLE_NAME}", table)) }
            }
            val setup = schema.getJSONArray("setupQueries"); repeat(setup.length()) { old.execSQL(setup.getString(it)) }
            insert("notebooks", mapOf("id" to "legacy", "title" to "Legacy", "notebook_type" to "pdf", "selected_page_id" to "highlight"))
            for ((index, page) in listOf("pen", "highlight").withIndex()) {
                insert("pages", mapOf("id" to page, "notebook_id" to "legacy", "sort_index" to index,
                    "updated_at" to (10L + index), "active_tool" to if (index == 0) "pen" else "highlighter",
                    "brush_size" to if (index == 0) 3.75f else 14f, "page_width" to 200f, "page_height" to 300f,
                    "viewport_scale" to 1.5f, "viewport_x" to -40f, "viewport_y" to 60f, "pdf_page_index" to 0))
            }
            insert("pdf_documents", mapOf("notebook_id" to "legacy", "local_path" to source.path, "page_count" to 1))
            for (ink in listOf(legacy, highlight)) insert("editable_objects", mapOf("id" to ink.id,
                "page_id" to ink.pageId, "type" to INK_STROKE_TYPE, "payload" to ink.payload,
                "created_at" to ink.createdAt, "updated_at" to ink.updatedAt))
            old.version = 6
        } finally { old.close() }
        val db = Room.databaseBuilder(f.context, NoteinDatabase::class.java, f.name).addMigrations(NoteinDatabase.MIGRATION_6_7, NoteinDatabase.MIGRATION_7_8).build()
        try {
            val editor = EditorRepository(db.editorDao()); val pdf = PdfRepository(f.context, db.pdfDao())
            val pages = editor.pages("legacy")
            assertEquals(listOf("pen", "highlight"), pages.map { it.id })
            assertTrue(pages.all { it.pdfSourceId == "legacy" && it.pdfPageIndex == 0 })
            assertEquals("highlight", editor.notebook("legacy")!!.selectedPageId)
            assertFalse(editor.notebook("legacy")!!.viewportLocked)
            assertEquals(source.path, pdf.source("legacy")!!.localPath)
            assertEquals(mapOf("ink_pen" to 3.75f, "highlighter" to 14f), editor.toolPreferences().associate { it.tool to it.thickness })
            assertEquals(legacy.payload, editor.pageObjects("pen").single().payload)
            assertEquals(highlight.payload, editor.pageObjects("highlight").single().payload)
            editor.setToolThickness("ink_pen", 9f)
            assertEquals(legacy.decode()!!.stroke.brush, editor.pageObjects("pen").single().toInkStrokeRecord()!!.decode()!!.stroke.brush)
            val output = File(f.root, "migrated.pdf")
            PdfExporter(f.context, editor, pdf, { File(it.path!!).outputStream() }, { File(it.path!!).delete(); Unit })
                .exportNotebook("legacy", Uri.fromFile(output)) {}
            val bitmap = renderPdfBitmap(output.path, 1, 200, 300)
            try { assertTrue(Color.blue(bitmap.getPixel(20, 20)) > 200) } finally { bitmap.recycle() }
            db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally { db.close(); f.base.deleteDatabase(f.name); f.root.deleteRecursively() }
    }
}
