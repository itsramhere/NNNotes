package com.ramst.notein.editor

import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ramst.notein.MainActivity
import com.ramst.notein.ui.theme.NoteinTheme
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class InsertUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var fixture: Phase10Fixture
    private lateinit var model: EditorViewModel
    private val store = ViewModelStore()
    @Before fun setup() = runBlocking {
        fixture = Phase10Fixture()
        val id = fixture.notebook()
        model = EditorViewModel(id, fixture.editor, fixture.pdf)
        store.put("insert-ui", model)
        compose.activityRule.scenario.onActivity { activity -> activity.setContent { NoteinTheme { EditorScreen(model) {} } } }
        compose.waitUntil(15000) { model.uiState.value.page != null && !model.uiState.value.pageBusy }
        saved()
    }
    @After fun cleanup() { compose.activityRule.scenario.onActivity { store.clear() }; fixture.close() }
    private fun saved() {
        compose.waitUntil(15000) { !model.uiState.value.hasUnsavedChanges && !model.uiState.value.pageBusy }
        compose.waitForIdle()
    }
    private fun click(text: String) {
        val node = compose.onNodeWithText(text)
        if (text !in setOf("Insert", "Save", "Cancel", "Delete table")) node.performScrollTo()
        node.performClick()
    }
    private fun tap(description: String) { compose.onNodeWithContentDescription(description).performScrollTo().performClick(); saved() }
    private fun insert(kind: String) {
        tap("Settings")
        compose.onNodeWithContentDescription("Insert tab").performClick()
        click(kind)
    }
    private fun input(label: String, value: String) = compose.onNodeWithText(label).performScrollTo().performTextReplacement(value)
    private fun screenshot(name: String, popup: Boolean = false) {
        val dir = File(compose.activity.filesDir, "insert-evidence").apply { mkdirs() }
        val node = if (popup) compose.onNode(isPopup()) else if (compose.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty()) compose.onNode(isDialog()) else compose.onRoot()
        File(dir, name).outputStream().use { node.captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun insertTabContainsAllFourFeaturesAndShapesRemainEditable() {
        tap("Settings")
        compose.onNodeWithContentDescription("Insert tab").performClick()
        listOf("Shape", "Graph", "Heading", "Table").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        screenshot("insert-menu.png", popup = true)
        click("Shape")
        click("Circle")
        input("Width", "0")
        compose.onNodeWithText("Insert").assertIsNotEnabled()
        input("Width", "180")
        screenshot("shape-dialog.png")
        click("Insert"); saved()
        val shape = model.uiState.value.objects.single().toContentObject()!!.shape!!
        assertEquals(ShapeKind.CIRCLE, shape.kind); assertEquals(180f, shape.height, 0f)
        tap("Edit shape")
        click("Triangle"); input("Height", "100")
        click("Save"); saved()
        assertEquals(ShapeKind.TRIANGLE, model.uiState.value.objects.single().toContentObject()!!.shape!!.kind)
        tap("Undo")
        assertEquals(ShapeKind.CIRCLE, model.uiState.value.objects.single().toContentObject()!!.shape!!.kind)
        screenshot("shape-on-page.png")
    }

    @Test fun graphRangesRejectInvalidInputAndSaveFractionalGridSteps() {
        insert("Graph")
        input("X grid step", "0")
        compose.onNodeWithText("Insert").assertIsNotEnabled()
        input("X grid step", "0.5")
        input("Y minimum", "-4")
        input("Y maximum", "8")
        screenshot("graph-dialog.png")
        click("Insert"); saved()
        assertEquals(.5f, model.uiState.value.objects.single().toContentObject()!!.graph!!.xStep, 0f)
        tap("Edit graph")
        input("X minimum", "6")
        compose.onNodeWithText("Save").assertIsNotEnabled()
        click("Cancel")
        screenshot("graph-on-page.png")
        assertEquals(-5f, model.uiState.value.objects.single().toContentObject()!!.graph!!.xMin, 0f)
    }

    @Test fun headingLevelIsRegisteredInTheOutlineAndCanBeEdited() {
        insert("Heading")
        compose.onNodeWithText("Insert").assertIsNotEnabled()
        click("H2"); input("Heading text", "Quadratic functions")
        screenshot("heading-dialog.png")
        click("Insert"); saved()
        assertEquals(2, model.uiState.value.objects.single().toContentObject()!!.headingLevel)
        tap("Settings"); compose.onNodeWithContentDescription("Insert tab").performClick()
        compose.onNodeWithText("H2  Quadratic functions").performScrollTo().assertIsDisplayed().performClick()
        tap("Edit heading")
        click("H3"); input("Heading text", "Worked examples")
        click("Save"); saved()
        assertEquals(3, model.uiState.value.objects.single().toContentObject()!!.headingLevel)
        assertEquals("Worked examples", model.uiState.value.objects.single().toContentObject()!!.text)
    }

    @Test fun tableCountsAreTypedValidatedAndSavedWithTheRequestedGrid() {
        insert("Table")
        input("Rows", "")
        compose.onNodeWithText("Insert").assertIsNotEnabled()
        input("Rows", "0")
        compose.onNodeWithText("Insert").assertIsNotEnabled()
        input("Rows", "51")
        compose.onNodeWithText("Insert").assertIsNotEnabled()
        input("Rows", "5")
        input("Columns", "2.5")
        compose.onNodeWithText("Insert").assertIsNotEnabled()
        input("Columns", "31")
        compose.onNodeWithText("Insert").assertIsNotEnabled()
        input("Columns", "4")
        screenshot("table-size-inputs.png")
        input("Cell text", "First cell")
        input("Rows", "6"); input("Columns", "5")
        click("Insert"); saved()
        val table = model.uiState.value.objects.single().toContentObject()!!.table!!
        assertEquals(6, table.rows.size); assertEquals(5, table.columns.size)
        assertEquals(6, table.cells.size); assertTrue(table.cells.all { it.size == 5 })
        assertEquals("First cell", table.cells[0][0])
        tap("Edit table"); click("Delete row"); click("Delete column"); click("Save"); saved()
        val edited = model.uiState.value.objects.single().toContentObject()!!.table!!
        assertEquals(5, edited.rows.size); assertEquals(4, edited.columns.size)
    }

    @Test fun tableCellsRowsColumnsAndWholeTableCanBeEditedOrDeleted() {
        insert("Table")
        input("Cell text", "Topic")
        click("Insert"); saved()
        tap("Edit table")
        compose.onNodeWithContentDescription("Choose Row").performScrollTo().performClick()
        compose.onAllNodesWithText("Row 2").filter(hasAnyAncestor(isPopup())).onFirst().performClick()
        compose.onNodeWithContentDescription("Choose Column").performScrollTo().performClick()
        compose.onAllNodesWithText("Column 2").filter(hasAnyAncestor(isPopup())).onFirst().performClick()
        input("Row height", "60")
        input("Column width", "140")
        click("Add row"); input("Cell text", "New row")
        click("Add column"); input("Cell text", "New corner")
        screenshot("table-editor.png")
        click("Delete row"); click("Delete column")
        input("Table width", "680")
        input("Table height", "280")
        click("Save"); saved()
        val table = model.uiState.value.objects.single().toContentObject()!!.table!!
        assertEquals(3, table.rows.size); assertEquals(3, table.columns.size)
        assertEquals("Topic", table.cells[0][0]); assertEquals(680f, table.width, .01f); assertEquals(280f, table.height, .01f)
        assertEquals(listOf(80f, 120f, 80f), table.rows)
        assertEquals(listOf(200f, 280f, 200f), table.columns)
        tap("Edit table")
        input("Cell text", "Discarded edit")
        click("Cancel")
        assertEquals("Topic", model.uiState.value.objects.single().toContentObject()!!.table!!.cells[0][0])
        tap("Edit table"); click("Delete table"); saved()
        assertTrue(model.uiState.value.objects.isEmpty())
        tap("Undo")
        assertEquals(table, model.uiState.value.objects.single().toContentObject()!!.table)
        screenshot("table-on-page.png")
    }
}
