package com.ramst.notein.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ramst.notein.MainActivity
import com.ramst.notein.pdf.PdfRepository
import com.ramst.notein.storage.*
import com.ramst.notein.ui.theme.NoteinTheme
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class Phase6UiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val name = "phase6-ui-${UUID.randomUUID()}.db"
    private lateinit var db: NoteinDatabase
    private lateinit var editor: EditorRepository
    private lateinit var pdf: PdfRepository
    private lateinit var model: EditorViewModel
    private val store = ViewModelStore()
    private lateinit var id: String
    private lateinit var asset: String
    private fun show() {
        compose.activityRule.scenario.onActivity { activity ->
            model = EditorViewModel(id, editor, pdf); store.put("editor", model)
            activity.setContent { NoteinTheme { EditorScreen(model) {} } }
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
        }
        saved()
    }
    private fun saved() = compose.waitUntil(15000) { !model.uiState.value.isLoading && !model.uiState.value.pageBusy && !model.uiState.value.hasUnsavedChanges }
    @Before fun setup() = runBlocking {
        val context = compose.activity.applicationContext
        db = Room.databaseBuilder(context, NoteinDatabase::class.java, name).build()
        editor = EditorRepository(db.editorDao()); pdf = PdfRepository(context, db.pdfDao())
        val source = File(context.cacheDir, "phase6-ui-${UUID.randomUUID()}.pdf")
        instrumentation.context.assets.open("phase6/mixed.pdf").use { input -> source.outputStream().use { input.copyTo(it) } }
        try { id = pdf.import(Uri.fromFile(source)).notebookId } finally { source.delete() }
        val imageSource = File(context.cacheDir, "phase6-ui-${UUID.randomUUID()}.png")
        val bitmap = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        try { imageSource.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; asset = ImageAssets(context).import(Uri.fromFile(imageSource)).assetId }
        finally { bitmap.recycle(); imageSource.delete() }
        val page = editor.firstPage(id)!!
        editor.saveContentSnapshot(listOf(record(130f, 170f, 260f, 170f, pageId = page.id).toEntity(),
            phase4Text(page.id, x = 130.0, y = 210.0, width = 250f, text = "PDF annotation"),
            phase4Image(page.id, asset, x = 300.0, y = 130.0)), page)
        show()
    }
    @After fun cleanup() {
        compose.activityRule.scenario.onActivity { store.clear() }
        runBlocking { pdf.document(id)?.let { File(it.localPath).delete() } }
        val images = ImageAssets(compose.activity); images.original(asset).delete(); images.preview(asset).delete()
        db.close(); compose.activity.deleteDatabase(name)
    }
    private fun assertAlignment(scale: Float) {
        val location = IntArray(2)
        compose.activityRule.scenario.onActivity { model.updateViewport(20f, 20f, scale) }
        saved(); compose.waitForIdle()
        compose.activityRule.scenario.onActivity { findInkCanvas(it.window.decorView)!!.getLocationOnScreen(location) }
        fun color(bitmap: Bitmap, x: Float, y: Float) = bitmap.getPixel(location[0] + (20f + x * scale).toInt(), location[1] + (20f + y * scale).toInt())
        compose.waitUntil(15000) {
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            try { val blue = color(screenshot, 60f, 50f); val green = color(screenshot, 340f, 170f)
                Color.blue(blue) > Color.red(blue) + 70 && Color.green(green) > 200 && Color.red(green) < 60
            } finally { screenshot.recycle() }
        }
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        try {
            assertTrue("Ink shifted relative to PDF at zoom $scale", Color.red(color(screenshot, 190f, 170f)) < 150)
            val directory = File(compose.activity.filesDir, "phase6-evidence").apply { mkdirs() }
            File(directory, "alignment-$scale.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { screenshot.recycle() }
    }
    @Test fun annotationsAndBackgroundStayAlignedThroughZoomNavigationAndRecreation() {
        val firstId = model.uiState.value.page!!.id
        val original = runBlocking { editor.pageObjects(firstId) }.associate { it.id to it.payload }
        for (scale in listOf(0.25f, 1f, 2f)) assertAlignment(scale)
        compose.onNodeWithContentDescription("PDF thumbnail 1").assertExists()
        compose.onNodeWithContentDescription("Page navigation").performScrollToNode(hasText("2", substring = false))
        compose.onNodeWithText("2", substring = false).performClick(); saved()
        assertEquals(1, model.uiState.value.page!!.pdfPageIndex)
        compose.activityRule.scenario.onActivity { model.selectPage(firstId) }; saved(); assertAlignment(1f)
        compose.activityRule.scenario.onActivity { store.clear() }
        compose.activityRule.scenario.recreate(); show(); assertAlignment(1f)
        assertEquals(original, model.uiState.value.objects.associate { it.id to it.payload })
        compose.activityRule.scenario.onActivity { model.selectObject(original.keys.first()); model.copySelection() }; saved()
        assertEquals(4, runBlocking { editor.pageObjects(firstId) }.size)
        compose.activityRule.scenario.onActivity { model.undo(InkEditorController()) }; saved()
        assertEquals(original, runBlocking { editor.pageObjects(firstId) }.associate { it.id to it.payload })
    }
    @Test fun exportCannotOmitPendingInkAndDestinationPickerCancellationKeepsNotebook() {
        val original = model.uiState.value.objects
        compose.activityRule.scenario.onActivity { model.onPendingInkChanged(1) }
        compose.onNodeWithText("Export").assertIsNotEnabled()
        compose.activityRule.scenario.onActivity { model.onPendingInkChanged(0) }
        compose.onNodeWithText("Export").performClick()
        compose.waitUntil(15000) { instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()?.endsWith(".documentsui") == true }
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitForIdle(); assertEquals(original, model.uiState.value.objects)
        assertNull(model.uiState.value.exportProgress)
    }
    @Test fun canvasPreviewUpdatesPageBoundariesAndChosenStartingPosition() {
        var selected: com.ramst.notein.pdf.CanvasExportRegion? = null
        val previews = model.uiState.value.previews.values.flatten()
        compose.activityRule.scenario.onActivity { activity -> activity.setContent { NoteinTheme {
            com.ramst.notein.pdf.CanvasExportDialog({}, { selected = it }, previews)
        } } }
        compose.onNodeWithText("Left").performTextReplacement("100")
        compose.onNodeWithText("Top").performTextReplacement("20")
        compose.onNodeWithText("Width").performTextReplacement("720")
        compose.onNodeWithText("Height").performTextReplacement("1000")
        compose.activityRule.scenario.onActivity { activity ->
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
        }
        compose.onNodeWithText("Preview: 4 A4 pages.").assertExists()
        compose.onNodeWithContentDescription("A4 pagination preview").assertExists()
        compose.onNodeWithText("Choose destination").performClick()
        assertEquals(com.ramst.notein.pdf.CanvasExportRegion(100f, 20f, 720f, 1000f), selected)
    }
    @Test fun nativePenAndHighlighterFinishOnPdfPagesAndRemainEditable() {
        val pageId = model.uiState.value.page!!.id
        for ((tool, point) in listOf("Highlighter" to (80f to 330f), "Pen" to (250f to 500f))) {
            compose.onNodeWithText(tool).performClick(); saved(); compose.waitForIdle()
            val before = model.uiState.value.objects.size
            compose.activityRule.scenario.onActivity { activity ->
                drawSyntheticStroke(findInkCanvas(activity.window.decorView)!!, point.first, point.second)
            }
            compose.waitUntil(15000) { model.uiState.value.objects.size == before + 1 && !model.uiState.value.hasUnsavedChanges && model.uiState.value.pendingInk == 0 }
        }
        val objects = runBlocking { editor.pageObjects(pageId) }
        assertEquals(5, objects.size)
        compose.activityRule.scenario.onActivity { model.undo(InkEditorController()) }; saved()
        assertEquals(4, runBlocking { editor.pageObjects(pageId) }.size)
    }
}
