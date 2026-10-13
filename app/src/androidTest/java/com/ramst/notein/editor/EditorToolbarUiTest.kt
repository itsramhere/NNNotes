package com.ramst.notein.editor

import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ramst.notein.MainActivity
import com.ramst.notein.storage.PaperBackground
import com.ramst.notein.ui.theme.NoteinTheme
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class EditorToolbarUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var fixture: Phase10Fixture
    private lateinit var model: EditorViewModel
    private val store = ViewModelStore()

    @Before fun setup() = runBlocking {
        fixture = Phase10Fixture()
        val id = fixture.notebook()
        model = EditorViewModel(id, fixture.editor, fixture.pdf)
        store.put("toolbar", model)
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent { NoteinTheme { EditorScreen(model, {}) } }
        }
        compose.waitUntil(15000) { model.uiState.value.page != null && !model.uiState.value.pageBusy }
        saved()
    }

    @After fun cleanup() {
        compose.activityRule.scenario.onActivity { store.clear() }
        fixture.close()
    }

    private fun saved() {
        compose.waitUntil(15000) { !model.uiState.value.hasUnsavedChanges && !model.uiState.value.pageBusy }
        compose.waitForIdle()
    }

    private fun tap(description: String) {
        val node = compose.onNodeWithContentDescription(description)
        if (description != "Close ink options") node.performScrollTo()
        node.performClick()
        saved()
    }

    private fun settings() = tap("Settings")
    private fun menu(label: String) = compose.onNodeWithText(label).performScrollTo().performClick()
    private fun screenshot(name: String, popup: Boolean = false) {
        val root = File(compose.activity.filesDir, "toolbar-evidence").apply { mkdirs() }
        File(root, name).outputStream().use {
            (if (popup) compose.onNode(isPopup()) else compose.onRoot()).captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun compactToolsAppearInTheRequestedOrderAndRemainReachable() {
        val expected = listOf("Ink pen", "Ball pen", "Pencil", "Eraser", "Highlighter", "Lasso",
            "Lock zoom", "Undo", "Redo", "Import PDF", "Settings", "Export")
        val tools = compose.onNodeWithTag("Editor tools").fetchSemanticsNode()
        assertEquals(expected, tools.children.mapNotNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull() })
        val density = compose.activity.resources.displayMetrics.density
        assertTrue("The toolbar should be at most 64 dp tall", tools.boundsInRoot.height / density <= 64f)
        compose.onNodeWithTag("Ink options").assertDoesNotExist()
        expected.forEach { compose.onNodeWithContentDescription(it).performScrollTo().assertIsDisplayed() }
        tap("Ink pen"); tap("Close ink options")
        screenshot("compact-toolbar.png")
    }

    @Test fun pensOpenInkOptionsAndKeepTheirIndependentFractionalThicknesses() {
        for ((tool, width) in listOf(InkTool.Pen to 3.25f, InkTool.BallPen to 1.75f, InkTool.Pencil to .375f)) {
            tap(tool.label)
            compose.onNodeWithText("${tool.label} options").assertIsDisplayed()
            compose.onNodeWithContentDescription("${tool.label} thickness")
                .performSemanticsAction(SemanticsActions.SetProgress) { it(width) }
            tap("Ink color Blue")
            assertEquals(0xFF244C9A.toInt(), model.uiState.value.page!!.brushColor)
            saved()
            assertEquals(width, model.toolThickness(tool), .00001f)
        }
        screenshot("pencil-options.png")
        tap("Eraser")
        compose.onNodeWithTag("Ink options").assertDoesNotExist()
        tap("Highlighter")
        tap("Ink color Yellow")
        assertEquals(0xFFFFD54F.toInt(), model.uiState.value.page!!.brushColor)
        tap("Close ink options")
        compose.onNodeWithTag("Ink options").assertDoesNotExist()
        tap("Ball pen")
        assertEquals(1.75f, model.toolThickness(InkTool.BallPen), .00001f)
    }

    @Test fun settingsContainInsertAndPaperActionsAndApplyAllFourPageTypes() {
        settings()
        listOf("Add text", "Add photo", "Image file", "Add page", "Page type", "Page paper", "Notebook default")
            .forEach { compose.onNodeWithText(it).performScrollTo().assertIsDisplayed() }
        menu("Page type")
        screenshot("settings-page-types.png", popup = true)
        for (background in PaperBackground.entries) {
            if (background != PaperBackground.BLANK) { settings(); menu("Page type") }
            menu(background.name.lowercase().replaceFirstChar { it.titlecase() })
            compose.waitUntil(15000) { model.uiState.value.page!!.paperBackground == background.storageValue && !model.uiState.value.pageBusy }
            saved()
        }
        settings(); menu("Page paper")
        compose.onNodeWithText("Page paper").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        settings(); menu("Notebook default")
        compose.onNodeWithText("Notebook default paper").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        val previous = model.uiState.value.page!!.id
        settings(); menu("Add page")
        compose.waitUntil(15000) { model.uiState.value.page!!.id != previous && !model.uiState.value.pageBusy }
        saved()
        assertEquals(2, model.uiState.value.pages.size)
    }

    @Test fun settingsTextInsertionUndoRedoLockAndExportWorkThroughTheNewBar() {
        settings(); menu("Add text")
        compose.onNodeWithText("Add text box").assertIsDisplayed()
        compose.onNodeWithText("Text").performTextInput("Toolbar note")
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(15000) { model.uiState.value.objects.size == 1 }
        saved()
        tap("Undo")
        compose.waitUntil(15000) { model.uiState.value.objects.isEmpty() }
        tap("Redo")
        compose.waitUntil(15000) { model.uiState.value.objects.size == 1 }
        tap("Lock zoom")
        compose.onNodeWithContentDescription("Lock zoom").assertIsOn()
        assertTrue(model.uiState.value.notebook!!.viewportLocked)
        if (model.uiState.value.lassoEnabled) {
            tap("Lasso")
            compose.onNodeWithContentDescription("Lasso").assertIsNotSelected()
        }
        tap("Lasso")
        compose.onNodeWithContentDescription("Lasso").assertIsSelected()
        tap("Ink pen")
        compose.onNodeWithContentDescription("Lasso").assertIsNotSelected()
        tap("Export")
        compose.onNodeWithText("Export PDF").assertIsDisplayed()
        compose.onNodeWithText("Whole notebook").assertIsDisplayed()
        compose.onNodeWithText("Current page").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
    }

    @Test fun photoImageAndPdfIconsOpenTheirPickersAndCancelWithoutChangingThePage() {
        val pageId = model.uiState.value.page!!.id
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (action in listOf("Add photo", "Image file", "Import PDF")) {
            if (action == "Import PDF") tap(action) else { settings(); menu(action) }
            compose.waitUntil(15000) {
                val app = instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()
                app != null && app != "com.ramst.notein"
            }
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            compose.waitUntil(15000) {
                instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == "com.ramst.notein"
            }
            saved()
            assertEquals(pageId, model.uiState.value.page!!.id)
            assertTrue(model.uiState.value.objects.isEmpty())
        }
    }
}
