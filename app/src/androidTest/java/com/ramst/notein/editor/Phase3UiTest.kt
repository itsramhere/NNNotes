package com.ramst.notein.editor

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ramst.notein.MainActivity
import com.ramst.notein.NoteinApplication
import com.ramst.notein.storage.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class Phase3UiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val ids = mutableListOf<String>()
    private val notebooks get() = (compose.activity.application as NoteinApplication).container.notebookRepository
    private fun open(type: NotebookType): String {
        val title = "Phase3-UI-${UUID.randomUUID()}"
        val id = runBlocking { notebooks.createNotebook(title, null, type) }; ids += id
        compose.onNodeWithText("Search titles").performTextReplacement(title)
        compose.activityRule.scenario.onActivity { activity ->
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
        }
        compose.waitUntil(15000) { compose.onAllNodes(hasText(title) and !hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText(title) and !hasSetTextAction()).performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("Finger writing").fetchSemanticsNodes().isNotEmpty() }
        saved()
        return id
    }
    private fun saved() { compose.waitUntil(15000) {
        compose.onAllNodesWithText("Saved").fetchSemanticsNodes().isNotEmpty() &&
            compose.onAllNodesWithContentDescription("Back to notebooks").fetchSemanticsNodes().any { it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled).not() }
    } }
    private fun click(text: String) {
        compose.onNodeWithContentDescription("Page navigation").performScrollToNode(hasText(text))
        compose.onNodeWithText(text, useUnmergedTree = true).performClick(); saved()
    }
    private fun assertPage(text: String) {
        compose.onNodeWithContentDescription("Page navigation").performScrollToNode(hasText(text))
        compose.onNodeWithText(text).assertExists()
    }
    private fun draw() { compose.activityRule.scenario.onActivity { activity -> drawSyntheticStroke(findInkCanvas(activity.window.decorView)!!, 80f,100f) }; saved() }
    private fun screenshot(name: String) {
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.getExternalFilesDir(null), name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
        image.recycle()
    }
    @After fun cleanup() {
        compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        runBlocking { ids.forEach { notebooks.trash(it); notebooks.permanentlyDelete(it) } }
    }
    @Test fun pagesWithHandwritingDuplicateReorderDeleteRotateAndReopen() {
        val id = open(NotebookType.STANDARD)
        draw()
        val db = NoteinDatabase.create(compose.activity)
        try {
            val source = runBlocking { db.editorDao().firstPage(id)!! }
            val original = runBlocking { db.editorDao().pageObjects(source.id).single().payload }
            click("Duplicate")
            compose.waitUntil(15000) { runBlocking { db.editorDao().pageCount(id) == 2 } }
            assertPage("Page 2 of 2")
            draw()
            val duplicate = runBlocking { db.editorDao().pages(id).last().id }
            assertEquals(2, runBlocking { db.editorDao().pageObjects(duplicate).size })
            assertEquals(original, runBlocking { db.editorDao().pageObjects(source.id).single().payload })
            click("Move ←")
            assertPage("Page 1 of 2")
            click("Grid")
            click("Fit page")
            val viewport = runBlocking { db.editorDao().page(duplicate)!! }
            compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(15000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            saved()
            assertEquals(original, runBlocking { db.editorDao().pageObjects(source.id).single().payload })
            assertEquals(viewport.viewportScale, runBlocking { db.editorDao().page(duplicate)!!.viewportScale },0f)
            compose.activityRule.scenario.recreate(); saved()
            assertPage("Page 1 of 2")
            compose.onNodeWithContentDescription("Page navigation").performScrollToIndex(0)
            screenshot("phase3-pages-landscape.png")
            compose.onNodeWithContentDescription("Back to notebooks").performClick()
            val title = runBlocking { db.editorDao().notebook(id)!!.title }
            compose.waitUntil(15000) { compose.onAllNodes(hasText(title) and !hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasText(title) and !hasSetTextAction()).performClick(); saved()
            assertPage("Page 1 of 2")
            click("Delete")
            compose.waitUntil(15000) { runBlocking { db.editorDao().pageCount(id) == 1 } }
            assertPage("Page 1 of 1")
            compose.onNodeWithContentDescription("Page navigation").performScrollToNode(hasText("Delete"))
            compose.onNodeWithText("Delete").assertIsNotEnabled()
            assertEquals(source.id, runBlocking { db.editorDao().firstPage(id)!!.id })
        } finally { db.close() }
    }
    @Test fun overviewTapNavigatesActualCanvasAndSurvivesRotation() {
        val id = open(NotebookType.INFINITE)
        draw()
        click("Fit content")
        val db = NoteinDatabase.create(compose.activity)
        try {
            val before = runBlocking { db.editorDao().firstPage(id)!! }
            val payload = runBlocking { db.editorDao().pageObjects(before.id).single().payload }
            compose.onNodeWithContentDescription("Canvas overview").performTouchInput { click(androidx.compose.ui.geometry.Offset(width * 0.1f, height * 0.1f)) }
            saved()
            val after = runBlocking { db.editorDao().firstPage(id)!! }
            assertTrue(before.viewportX != after.viewportX || before.viewportY != after.viewportY)
            compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(15000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            saved()
            compose.onNodeWithContentDescription("Canvas overview").assertExists()
            assertEquals(payload, runBlocking { db.editorDao().pageObjects(before.id).single().payload })
            assertEquals(after.viewportX, runBlocking { db.editorDao().page(before.id)!!.viewportX },0f)
            screenshot("phase3-infinite-landscape.png")
        } finally { db.close() }
    }
}
