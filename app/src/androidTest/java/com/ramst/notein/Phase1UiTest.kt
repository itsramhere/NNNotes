package com.ramst.notein

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ramst.notein.storage.NotebookRepository
import com.ramst.notein.storage.NoteinDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class Phase1UiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val prefix = "UI-${UUID.randomUUID().toString().take(8)}"
    private val createdIds = mutableSetOf<String>()
    private val createdFolderIds = mutableSetOf<String>()
    private val repository: NotebookRepository get() =
        (compose.activity.application as NoteinApplication).container.notebookRepository

    private fun waitFor(text: String) {
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasText(text) and !hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun search(value: String) {
        compose.onNodeWithText("Search titles").performTextReplacement(value)
        compose.activityRule.scenario.onActivity { activity ->
            val manager = activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            manager.hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
        }
        compose.waitForIdle()
    }

    private fun create(title: String) {
        compose.onNodeWithText("New notebook", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Notebook title").performTextInput(title)
        compose.onNodeWithText("Create", useUnmergedTree = true).performClick()
        waitFor("Finger writing")
        runBlocking { createdIds += repository.observeNotebooks(false, null, title).first().map { it.id } }
        compose.onNodeWithContentDescription("Back to notebooks").performClick()
        waitFor(title)
    }

    @After fun removeOnlyTestNotebooks() = runBlocking {
        for (id in createdIds) {
            val exists = (repository.observeNotebooks(false, null, "").first() +
                repository.observeNotebooks(true, null, "").first()).any { it.id == id }
            if (exists) { repository.trash(id); repository.permanentlyDelete(id) }
        }
        if (createdFolderIds.isNotEmpty()) {
            val db = NoteinDatabase.create(compose.activity)
            try {
                for (id in createdFolderIds) {
                    db.openHelper.writableDatabase.execSQL("DELETE FROM folders WHERE id = ?", arrayOf(id))
                }
            } finally { db.close() }
        }
    }

    @Test fun createRenameMoveSearchAndActivityRecreation() {
        val title = "$prefix draft"
        val renamed = "$prefix renamed"
        create(title)
        search(title)
        compose.onNodeWithContentDescription("Notebook actions").performClick()
        compose.onNodeWithText("Rename", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Notebook title").performTextReplacement(renamed)
        compose.onNodeWithText("Save", useUnmergedTree = true).performClick()
        search(renamed)
        waitFor(renamed)
        val originalId = runBlocking { repository.observeNotebooks(false, null, renamed).first().single().id }
        compose.onNodeWithContentDescription("Create folder").performClick()
        compose.onNodeWithText("Folder name").performTextInput(prefix)
        compose.onNodeWithText("Create", useUnmergedTree = true).performClick()
        waitFor(prefix)
        runBlocking { createdFolderIds += repository.observeFolders().first().filter { it.name == prefix }.map { it.id } }
        compose.onNodeWithContentDescription("Notebook actions").performClick()
        compose.onNodeWithText("Move", useUnmergedTree = true).performClick()
        compose.onAllNodesWithText(prefix).filter(hasAnyAncestor(isDialog())).onFirst().performClick()
        compose.waitUntil(10_000) {
            runBlocking { repository.observeNotebooks(false, null, renamed).first().single().folderName == prefix }
        }
        compose.activityRule.scenario.recreate()
        waitFor(renamed)
        assertEquals(originalId, runBlocking { repository.observeNotebooks(false, null, renamed).first().single().id })
        compose.onNode(hasText(renamed) and !hasSetTextAction()).performClick()
        waitFor("Finger writing")
    }

    @Test fun duplicateTitlesRestoreAndPermanentDeletionRequireConfirmation() {
        create(prefix)
        create(prefix)
        search(prefix)
        compose.onAllNodesWithContentDescription("Notebook actions").onFirst().performClick()
        compose.onNodeWithText("Move to trash", useUnmergedTree = true).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Notebook actions").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithContentDescription("Open trash").performClick()
        waitFor("Trash")
        waitFor(prefix)
        compose.onNode(hasText(prefix) and !hasSetTextAction()).performClick()
        compose.onNodeWithText("Trash").assertIsDisplayed()
        compose.onNodeWithContentDescription("Notebook actions").performClick()
        compose.onNodeWithText("Restore", useUnmergedTree = true).performClick()
        waitFor("Trash is empty")
        compose.onNodeWithContentDescription("Back to notebooks").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Notebook actions").fetchSemanticsNodes().size == 2 }
        compose.onAllNodesWithContentDescription("Notebook actions").onFirst().performClick()
        compose.onNodeWithText("Move to trash", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("Open trash").performClick()
        waitFor(prefix)
        compose.onNodeWithContentDescription("Notebook actions").performClick()
        compose.onNodeWithText("Delete permanently", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Permanently delete notebook?").assertIsDisplayed()
        compose.onNodeWithText("Cancel", useUnmergedTree = true).performClick()
        assertEquals(1, runBlocking { repository.observeNotebooks(true, null, prefix).first().size })
        compose.onNodeWithContentDescription("Notebook actions").performClick()
        compose.onNodeWithText("Delete permanently", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Delete permanently", useUnmergedTree = true).performClick()
        waitFor("Trash is empty")
        assertEquals(1, runBlocking { repository.observeNotebooks(false, null, prefix).first().size })
    }

    @Test fun staleRenameDisplaysSaveFailure() {
        create(prefix)
        search(prefix)
        compose.onNodeWithContentDescription("Notebook actions").performClick()
        compose.onNodeWithText("Rename", useUnmergedTree = true).performClick()
        val id = createdIds.single()
        runBlocking { repository.trash(id); repository.permanentlyDelete(id) }
        compose.onNodeWithText("Notebook title").performTextReplacement("$prefix renamed")
        compose.onNodeWithText("Save", useUnmergedTree = true).performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Could not save your change.", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Could not save your change.", substring = true).assertIsDisplayed()
    }
}
