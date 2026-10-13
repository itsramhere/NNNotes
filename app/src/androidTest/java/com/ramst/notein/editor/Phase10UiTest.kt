package com.ramst.notein.editor

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ramst.notein.MainActivity
import com.ramst.notein.ui.theme.NoteinTheme
import com.ramst.notein.storage.*
import android.graphics.PointF
import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class Phase10UiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var f: Phase10Fixture
    private lateinit var id: String
    private lateinit var model: EditorViewModel
    private val store = ViewModelStore()
    private fun open() {
        compose.activityRule.scenario.onActivity { activity ->
            store.clear(); model = EditorViewModel(id, f.editor, f.pdf); store.put("editor", model)
            activity.setContent { NoteinTheme { EditorScreen(model) {} } }
        }
        compose.waitUntil(15000) { !model.uiState.value.isLoading && !model.uiState.value.pageBusy }
        compose.waitForIdle()
    }
    @Before fun setup() = runBlocking { f = Phase10Fixture(); id = f.notebook(NotebookType.INFINITE); open() }
    @After fun cleanup() { compose.activityRule.scenario.onActivity { store.clear() }; f.close() }
    private fun saved() { compose.waitUntil(15000) { !model.uiState.value.hasUnsavedChanges && !model.uiState.value.pageBusy }; compose.waitForIdle() }
    private fun screenshot(name: String) {
        val dir = File(compose.activity.filesDir, "phase10-evidence").apply { mkdirs() }
        File(dir, name).outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun firstAndNewPaperPagesOpenFittedInTheFrameCenter() = runBlocking {
        id = f.notebook(); open(); saved()
        fun assertCentered() {
            val page = model.uiState.value.page!!
            assertTrue(page.viewportWidth > 0f && page.viewportHeight > 0f)
            assertEquals(page.viewportWidth / 2f, page.viewportX + page.pageWidth * page.viewportScale / 2f, .01f)
            assertEquals(page.viewportHeight / 2f, page.viewportY + page.pageHeight * page.viewportScale / 2f, .01f)
            assertTrue(page.pageWidth * page.viewportScale <= page.viewportWidth + .01f)
            assertTrue(page.pageHeight * page.viewportScale <= page.viewportHeight + .01f)
        }
        assertCentered()
        compose.onNodeWithText("Use one finger to scroll vertically").performScrollTo().assertIsDisplayed()
        screenshot("paper-centered.png")
        val firstId = model.uiState.value.page!!.id
        compose.onNodeWithContentDescription("Settings").performScrollTo().performClick()
        compose.onNodeWithText("Add page").performScrollTo().performClick()
        compose.waitUntil(15000) { model.uiState.value.page!!.id != firstId && !model.uiState.value.pageBusy && model.uiState.value.page!!.viewportWidth > 0f }
        saved(); assertCentered(); screenshot("paper-next-centered.png")
    }
    @Test fun layoutChoicesSwipeBetweenActualPagesWithOneNotebookZoomAndVisibleNeighbors() = runBlocking {
        id = f.notebook(); open(); saved()
        val first = model.uiState.value.page!!.id
        compose.activityRule.scenario.onActivity { model.createPage() }
        compose.waitUntil(15000) { model.uiState.value.page!!.id != first && !model.uiState.value.pageBusy }
        saved()
        val second = model.uiState.value.page!!.id
        compose.activityRule.scenario.onActivity { model.selectPage(first) }
        compose.waitUntil(15000) { model.uiState.value.page!!.id == first && !model.uiState.value.pageBusy }; saved()
        compose.activityRule.scenario.onActivity { model.updateViewport(0f, 0f, 1.5f) }; saved()
        compose.onNodeWithContentDescription("Lock zoom").performClick()
        compose.waitUntil(15000) { model.uiState.value.notebook!!.viewportLocked && !model.uiState.value.pageBusy }; saved()
        compose.onNodeWithText("Side by side").assertIsSelected()
        compose.onNodeWithText("Move ←").assertDoesNotExist(); compose.onNodeWithText("Move →").assertDoesNotExist()
        fun findCanvas(view: android.view.View): InkCanvasView? {
            if (view is InkCanvasView) return view
            if (view is android.view.ViewGroup) for (index in 0 until view.childCount) findCanvas(view.getChildAt(index))?.let { return it }
            return null
        }
        fun drag(dy: Float, dx: Float, finish: Boolean) {
            compose.activityRule.scenario.onActivity { activity ->
                val canvas = checkNotNull(findCanvas(activity.window.decorView))
                fun finger(x: Float, y: Float) = Phase4Pointer(x, y, 0, android.view.MotionEvent.TOOL_TYPE_FINGER)
                if (!finish) {
                    phase4Event(canvas, android.view.MotionEvent.ACTION_DOWN, finger(500f, 300f))
                    phase4Event(canvas, android.view.MotionEvent.ACTION_MOVE, finger(500f + dx, 300f + dy))
                } else phase4Event(canvas, android.view.MotionEvent.ACTION_UP, finger(500f + dx, 300f + dy))
            }
            compose.waitForIdle()
        }
        drag(0f, -400f, false); screenshot("pages-horizontal-drag.png"); drag(0f, -400f, true)
        compose.waitUntil(15000) { model.uiState.value.page!!.id == second && !model.uiState.value.pageBusy }; saved()
        assertEquals(1.5f, model.uiState.value.page!!.viewportScale, 0f)
        compose.onNodeWithText("One below another").performClick()
        compose.waitUntil(15000) { model.uiState.value.notebook!!.pageLayout == "vertical" }; saved()
        compose.onNodeWithText("One below another").assertIsSelected()
        compose.activityRule.scenario.onActivity { model.selectPage(first) }
        compose.waitUntil(15000) { model.uiState.value.page!!.id == first && !model.uiState.value.pageBusy }; saved()
        fun toolbarPixel(): Int {
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            return try { bitmap.getPixel(bitmap.width / 2, bitmap.height / 10) } finally { bitmap.recycle() }
        }
        val toolbarBeforeDrag = toolbarPixel()
        drag(-1200f, 0f, false)
        assertEquals("Scrolled sheets must stay below the toolbar", toolbarBeforeDrag, toolbarPixel())
        screenshot("pages-vertical-drag.png"); drag(-1200f, 0f, true)
        compose.waitUntil(15000) { model.uiState.value.page!!.id == second && !model.uiState.value.pageBusy }; saved()
        assertEquals(1.5f, model.uiState.value.page!!.viewportScale, 0f)
        open(); saved()
        compose.onNodeWithText("One below another").assertIsSelected()
        assertEquals(1.5f, model.uiState.value.page!!.viewportScale, 0f)
        screenshot("pages-vertical-reopened.png")
    }
    @Test fun toolbarLabelsLockIndicatorSubsetImportAndRecreationAreUsable() {
        for (tool in listOf("Pencil", "Ball pen", "Ink pen", "Highlighter")) {
            compose.onNodeWithText(tool, useUnmergedTree = true).performClick(); saved()
        }
        compose.onNodeWithText("Pencil", useUnmergedTree = true).performClick(); saved()
        compose.onNodeWithContentDescription("Pencil thickness").performSemanticsAction(SemanticsActions.SetProgress) { it(.375f) }
        saved()
        compose.waitUntil(15000) { runBlocking { f.editor.toolPreferences().any { it.tool == "pencil" && it.thickness == .375f } } }
        compose.onNodeWithContentDescription("Lock zoom").assertIsOff().performClick()
        compose.waitUntil(15000) { model.uiState.value.notebook!!.viewportLocked }
        compose.onNodeWithContentDescription("Lock zoom").assertIsOn()
        compose.onNodeWithText("Zoom locked · Use one finger to navigate").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Use one finger to navigate").assertDoesNotExist()
        compose.onNodeWithText("Fit content").performScrollTo().assertIsNotEnabled()
        compose.activityRule.scenario.onActivity { model.preparePdf(Uri.fromFile(f.source()), PointF(120f, 40f)) }
        compose.waitUntil(15000) { model.uiState.value.preparedPdf != null }; compose.waitForIdle()
        compose.onNodeWithText("Clear").performClick()
        compose.onNodeWithText("Import 0 pages").assertIsNotEnabled()
        compose.onAllNodes(isToggleable()).filter(hasAnyAncestor(isDialog())).onFirst().performClick()
        compose.onNodeWithText("Import 1 pages").performClick()
        compose.waitUntil(15000) { model.uiState.value.preparedPdf == null && !model.uiState.value.pageBusy }
        saved(); assertEquals(1, runBlocking { f.editor.pageObjects(model.uiState.value.page!!.id).size })
        screenshot("editor-locked.png")
        val before = model.uiState.value.page!!
        compose.activityRule.scenario.recreate(); open(); saved()
        assertTrue(model.uiState.value.notebook!!.viewportLocked)
        assertEquals(.375f, model.toolThickness(InkTool.Pencil), .00001f)
        assertEquals(before.viewportScale, model.uiState.value.page!!.viewportScale, 0f)
        compose.onNodeWithContentDescription("Lock zoom").assertIsOn()
        screenshot("editor-recreated.png")
        compose.onNodeWithContentDescription("Lock zoom").performClick()
        compose.waitUntil(15000) { !model.uiState.value.notebook!!.viewportLocked }
        compose.onNodeWithText("Fit content").assertIsEnabled()
    }
}
