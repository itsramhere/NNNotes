package com.ramst.notein.library

import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ramst.notein.MainActivity
import com.ramst.notein.editor.Phase10Fixture
import com.ramst.notein.storage.NotebookType
import com.ramst.notein.storage.PaperTemplate
import com.ramst.notein.ui.theme.NoteinTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

/** Runs against an isolated database and files; never seeds or clears the user's library. */
@RunWith(AndroidJUnit4::class)
class LibraryRedesignUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var fixture: Phase10Fixture
    private lateinit var model: LibraryViewModel
    private val store = ViewModelStore()
    private val trash = mutableStateOf(false)
    private var opened: String? = null

    @Before fun setup() = runBlocking {
        fixture = Phase10Fixture()
        fixture.notebooks.createFolder("Personal")
        fixture.notebooks.createFolder("Studio")
        fixture.notebooks.createFolder("Learning")
        val folders = fixture.notebooks.observeFolders().first().associateBy { it.name }
        listOf("Reading journal", "Weekend plans", "Design studies", "A few good ideas", "Everyday notes").forEachIndexed { index, title ->
            fixture.notebooks.createNotebook(title, folders[if (index % 2 == 0) "Personal" else "Studio"]!!.id)
        }
        fixture.notebooks.createNotebook("Open canvas", null, NotebookType.INFINITE, PaperTemplate())
        model = LibraryViewModel(fixture.notebooks, fixture.pdf)
        store.put("library-redesign", model)
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                NoteinTheme {
                    LibraryScreen(model, trash.value, { opened = it }, { trash.value = true }, { trash.value = false })
                }
            }
        }
        ready()
    }

    @After fun cleanup() {
        compose.activityRule.scenario.onActivity { store.clear() }
        fixture.close()
    }

    private fun ready() {
        compose.waitUntil(15000) { !model.uiState.value.isLoading }
        compose.waitForIdle()
    }

    private fun waitFor(text: String) {
        compose.waitUntil(15000) {
            compose.onAllNodes(hasText(text) and !hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }

    private fun search(query: String) {
        compose.onNodeWithText("Search titles").performTextReplacement(query)
        compose.activityRule.scenario.onActivity { activity ->
            (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
        }
        compose.waitUntil(15000) { model.uiState.value.searchQuery == query && !model.uiState.value.isLoading }
        compose.waitForIdle()
    }

    private fun actions() = compose.onNodeWithContentDescription("Notebook actions").performClick()
    private fun screenshot(name: String) {
        val dir = File(compose.activity.filesDir, "ui-redesign-evidence").apply { mkdirs() }
        File(dir, name).outputStream().use {
            val node = if (compose.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty()) compose.onNode(isDialog()) else compose.onRoot()
            node.captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun notebookCoversAndCreationPreserveStandardAndInfiniteChoices() {
        waitFor("Everyday notes")
        screenshot("library.png")
        compose.onNodeWithText("Open canvas").performClick()
        assertNotNull(opened)
        compose.onNodeWithText("New notebook", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Notebook title").performTextInput("A fresh start")
        compose.onAllNodesWithText("Infinite canvas").filter(hasAnyAncestor(isDialog())).onFirst().performScrollTo().performClick()
        compose.onNodeWithText("Paper layout").performScrollTo().assertIsDisplayed()
        screenshot("create-notebook.png")
        compose.onNodeWithText("Create").performClick()
        compose.waitUntil(15000) {
            runBlocking { fixture.notebooks.observeNotebooks(false, null, "A fresh start").first().size == 1 }
        }
        val created = runBlocking { fixture.notebooks.observeNotebooks(false, null, "A fresh start").first().single() }
        assertEquals(NotebookType.INFINITE.storageValue, created.notebookType)
        assertEquals(created.id, opened)
    }

    @Test fun searchRenameMoveFolderSelectionAndNoResultsRemainFunctional() {
        search("Everyday notes"); waitFor("Everyday notes")
        actions(); compose.onNodeWithText("Rename").performClick()
        compose.onNodeWithText("Notebook title").performTextReplacement("Daily notes")
        compose.onNodeWithText("Save").performClick()
        search("Daily notes"); waitFor("Daily notes")
        actions(); compose.onNodeWithText("Move").performClick()
        compose.onAllNodesWithText("Studio").filter(hasAnyAncestor(isDialog())).onFirst().performClick()
        compose.waitUntil(15000) {
            runBlocking { fixture.notebooks.observeNotebooks(false, null, "Daily notes").first().single().folderName == "Studio" }
        }
        search("")
        compose.onNodeWithText("Studio").performScrollTo().performClick()
        compose.waitUntil(15000) { model.uiState.value.selectedFolderId != null && !model.uiState.value.isLoading }
        waitFor("Daily notes")
        search("no such notebook")
        waitFor("No matching notebooks")
        screenshot("search-empty.png")
        compose.onNodeWithText("Clear search").performScrollTo().performClick()
        waitFor("Daily notes")
    }

    @Test fun trashRestoreAndPermanentDeleteConfirmationRemainFunctional() {
        search("Everyday notes"); waitFor("Everyday notes")
        actions(); compose.onNodeWithText("Move to trash").performClick()
        compose.onNodeWithContentDescription("Open trash").performClick()
        search(""); waitFor("Everyday notes")
        screenshot("trash.png")
        actions(); compose.onNodeWithText("Restore").performClick()
        waitFor("Trash is empty")
        screenshot("trash-empty.png")
        if (compose.activity.resources.configuration.screenWidthDp >= 840) {
            compose.onNodeWithText("All notebooks").performClick()
        } else compose.onNodeWithContentDescription("Back to notebooks").performClick()
        search("Everyday notes"); waitFor("Everyday notes")
        actions(); compose.onNodeWithText("Move to trash").performClick()
        compose.onNodeWithContentDescription("Open trash").performClick()
        search(""); waitFor("Everyday notes")
        actions(); compose.onNodeWithText("Delete permanently").performClick()
        compose.onNodeWithText("Permanently delete notebook?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(1, runBlocking { fixture.notebooks.observeNotebooks(true, null, "").first().size })
        actions(); compose.onNodeWithText("Delete permanently").performClick()
        compose.onNodeWithText("Delete permanently").performClick()
        waitFor("Trash is empty")
        assertEquals(0, runBlocking { fixture.notebooks.observeNotebooks(true, null, "").first().size })
    }
}
