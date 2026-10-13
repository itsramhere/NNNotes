package com.ramst.notein.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [FolderEntity::class, NotebookEntity::class, PageEntity::class, EditableObjectEntity::class, PdfDocumentEntity::class, ToolPreferenceEntity::class],
    version = 8,
    exportSchema = true,
)
abstract class NoteinDatabase : RoomDatabase() {
    abstract fun folderDao(): FolderDao
    abstract fun notebookDao(): NotebookDao
    abstract fun editorDao(): EditorDao
    abstract fun pdfDao(): PdfDao

    companion object {
        fun create(context: Context): NoteinDatabase = Room.databaseBuilder(
            context.applicationContext,
            NoteinDatabase::class.java,
            "notein.db",
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8).build()

        internal val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE notebooks ADD COLUMN locked_zoom_scale REAL")
                database.execSQL("ALTER TABLE notebooks ADD COLUMN page_layout TEXT NOT NULL DEFAULT 'horizontal'")
                database.execSQL("UPDATE notebooks SET locked_zoom_scale = COALESCE((SELECT viewport_scale FROM pages WHERE pages.id = notebooks.selected_page_id AND pages.notebook_id = notebooks.id), (SELECT viewport_scale FROM pages WHERE pages.notebook_id = notebooks.id ORDER BY sort_index LIMIT 1), 1) WHERE viewport_locked = 1")
                database.execSQL("UPDATE pages SET viewport_scale = (SELECT locked_zoom_scale FROM notebooks WHERE notebooks.id = pages.notebook_id) WHERE notebook_id IN (SELECT id FROM notebooks WHERE viewport_locked = 1)")
            }
        }

        internal val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE notebooks ADD COLUMN viewport_locked INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE pages ADD COLUMN pdf_source_id TEXT")
                database.execSQL("ALTER TABLE pages ADD COLUMN viewport_width REAL NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE pages ADD COLUMN viewport_height REAL NOT NULL DEFAULT 0")
                database.execSQL("CREATE TABLE pdf_sources_new (notebook_id TEXT NOT NULL, local_path TEXT NOT NULL, page_count INTEGER NOT NULL, created_at INTEGER NOT NULL, source_id TEXT NOT NULL PRIMARY KEY, FOREIGN KEY(notebook_id) REFERENCES notebooks(id) ON DELETE CASCADE)")
                database.execSQL("INSERT INTO pdf_sources_new SELECT notebook_id, local_path, page_count, created_at, notebook_id FROM pdf_documents")
                database.execSQL("UPDATE pages SET pdf_source_id = notebook_id WHERE pdf_page_index IS NOT NULL")
                database.execSQL("DROP TABLE pdf_documents")
                database.execSQL("ALTER TABLE pdf_sources_new RENAME TO pdf_documents")
                database.execSQL("CREATE INDEX index_pdf_documents_notebook_id ON pdf_documents(notebook_id)")
                database.execSQL("CREATE TABLE tool_preferences (tool TEXT NOT NULL PRIMARY KEY, thickness REAL NOT NULL)")
                database.execSQL("INSERT INTO tool_preferences SELECT 'ink_pen', brush_size FROM pages WHERE active_tool = 'pen' ORDER BY updated_at DESC LIMIT 1")
                database.execSQL("INSERT INTO tool_preferences SELECT 'highlighter', brush_size FROM pages WHERE active_tool = 'highlighter' ORDER BY updated_at DESC LIMIT 1")
            }
        }

        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE notebooks ADD COLUMN selected_page_id TEXT")
            }
        }

        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE pages ADD COLUMN pdf_page_index INTEGER")
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS pdf_documents (notebook_id TEXT NOT NULL, local_path TEXT NOT NULL, page_count INTEGER NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY(notebook_id), FOREIGN KEY(notebook_id) REFERENCES notebooks(id) ON DELETE CASCADE)",
                )
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_pdf_documents_notebook_id ON pdf_documents(notebook_id)")
            }
        }

        /** Infer the old 32-point ruling / 28-point grid without changing dimensions or ink. */
        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE notebooks ADD COLUMN default_paper_background TEXT NOT NULL DEFAULT 'blank'")
                database.execSQL("ALTER TABLE notebooks ADD COLUMN default_page_width REAL NOT NULL DEFAULT 595")
                database.execSQL("ALTER TABLE notebooks ADD COLUMN default_page_height REAL NOT NULL DEFAULT 842")
                database.execSQL("ALTER TABLE notebooks ADD COLUMN default_spacing_mm REAL NOT NULL DEFAULT 7")
                database.execSQL("ALTER TABLE notebooks ADD COLUMN default_pattern_weight TEXT NOT NULL DEFAULT 'normal'")
                database.execSQL("ALTER TABLE notebooks ADD COLUMN default_background_color INTEGER NOT NULL DEFAULT -1")
                database.execSQL("ALTER TABLE notebooks ADD COLUMN auto_matching_pages INTEGER NOT NULL DEFAULT 1")
                database.execSQL("ALTER TABLE pages ADD COLUMN paper_spacing_mm REAL NOT NULL DEFAULT 7")
                database.execSQL("ALTER TABLE pages ADD COLUMN paper_pattern_weight TEXT NOT NULL DEFAULT 'normal'")
                database.execSQL("ALTER TABLE pages ADD COLUMN paper_background_color INTEGER NOT NULL DEFAULT -1")
                database.execSQL("ALTER TABLE pages ADD COLUMN auto_generated INTEGER NOT NULL DEFAULT 0")
                database.execSQL("UPDATE pages SET paper_spacing_mm = CASE paper_background WHEN 'grid' THEN 28.0 * 25.4 / 72.0 WHEN 'lined' THEN 32.0 * 25.4 / 72.0 ELSE 7 END, paper_pattern_weight = 'legacy'")
                database.execSQL("UPDATE notebooks SET default_paper_background = (SELECT paper_background FROM pages WHERE pages.notebook_id = notebooks.id ORDER BY sort_index LIMIT 1), default_page_width = (SELECT page_width FROM pages WHERE pages.notebook_id = notebooks.id ORDER BY sort_index LIMIT 1), default_page_height = (SELECT page_height FROM pages WHERE pages.notebook_id = notebooks.id ORDER BY sort_index LIMIT 1), default_spacing_mm = (SELECT paper_spacing_mm FROM pages WHERE pages.notebook_id = notebooks.id ORDER BY sort_index LIMIT 1), default_pattern_weight = 'legacy' WHERE EXISTS (SELECT 1 FROM pages WHERE pages.notebook_id = notebooks.id)")
                database.execSQL("UPDATE notebooks SET auto_matching_pages = 0 WHERE notebook_type != 'standard'")
            }
        }

        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE pages ADD COLUMN viewport_x REAL NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE pages ADD COLUMN viewport_y REAL NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE pages ADD COLUMN viewport_scale REAL NOT NULL DEFAULT 1")
                database.execSQL("ALTER TABLE pages ADD COLUMN active_tool TEXT NOT NULL DEFAULT 'pen'")
                database.execSQL("ALTER TABLE pages ADD COLUMN brush_color INTEGER NOT NULL DEFAULT -15066082")
                database.execSQL("ALTER TABLE pages ADD COLUMN brush_size REAL NOT NULL DEFAULT 6")
                database.execSQL("ALTER TABLE pages ADD COLUMN finger_writing INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Existing notebooks remain standard A4 notebooks; no user content is rewritten. */
        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE notebooks ADD COLUMN notebook_type TEXT NOT NULL DEFAULT 'standard'")
                database.execSQL("ALTER TABLE pages ADD COLUMN paper_background TEXT NOT NULL DEFAULT 'blank'")
                database.execSQL("ALTER TABLE pages ADD COLUMN page_width REAL NOT NULL DEFAULT 595")
                database.execSQL("ALTER TABLE pages ADD COLUMN page_height REAL NOT NULL DEFAULT 842")
            }
        }
    }
}
