package com.ramst.notein.editor

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ramst.notein.MainActivity
import com.ramst.notein.library.LibraryScreen
import com.ramst.notein.library.LibraryViewModel
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
class Phase9UiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val name="phase9-ui-${UUID.randomUUID()}.db"
    private lateinit var db:NoteinDatabase
    private lateinit var repo:EditorRepository
    private val store=ViewModelStore()
    private var opened:String?=null
    @Before fun setup()=runBlocking {
        val context=compose.activity.applicationContext
        db=Room.databaseBuilder(context,NoteinDatabase::class.java,name).build();repo=EditorRepository(db.editorDao())
        db.folderDao().insert(FolderEntity("folder","Test folder",1,1))
        compose.activityRule.scenario.onActivity { activity ->
            val model=LibraryViewModel(NotebookRepository(db.notebookDao(),db.folderDao()),PdfRepository(context,db.pdfDao()))
            store.put("library",model)
            activity.setContent {NoteinTheme {LibraryScreen(model,false,{opened=it},{})}}
        }
        compose.waitUntil(15000) {compose.onAllNodesWithText("Test folder").fetchSemanticsNodes().isNotEmpty()}
    }
    @After fun cleanup() {compose.activityRule.scenario.onActivity {store.clear()};db.close();compose.activity.deleteDatabase(name)}
    private fun hideKeyboard() {
        compose.activityRule.scenario.onActivity {activity ->
            (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(activity.window.decorView.windowToken,0)
        }
        compose.waitForIdle()
    }
    private fun click(text:String) {
        hideKeyboard()
        val container=compose.onNodeWithTag("Notebook configuration")
        val target=compose.onAllNodesWithText(text,useUnmergedTree=true).filter(hasAnyAncestor(isDialog())).onFirst()
        val viewport=container.getUnclippedBoundsInRoot()
        val bounds=target.getUnclippedBoundsInRoot()
        val delta=((bounds.top.value+bounds.bottom.value-viewport.top.value-viewport.bottom.value)/2f)*compose.activity.resources.displayMetrics.density
        if(delta!=0f) container.performSemanticsAction(SemanticsActions.ScrollBy) {it(0f,delta)}
        compose.waitForIdle()
        target.performScrollTo().assertIsDisplayed().performClick()
    }
    private fun count(table:String):Int = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use {it.moveToFirst();it.getInt(0)}
    private fun assets() = File(compose.activity.filesDir,"image-assets").listFiles()?.map {it.name}?.toSet().orEmpty()
    private fun open()=compose.onNodeWithText("New notebook",useUnmergedTree=true).performClick()
    private fun screenshot(name: String) {
        val evidence = File(compose.activity.filesDir, "notebook-picker-evidence").apply { mkdirs() }
        File(evidence, name).outputStream().use {
            compose.onNode(isDialog()).captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
        }
    }

    @Test fun repeatedCancellationPersistsNothingAndInfiniteHidesPageSettings() {
        val before=assets()
        repeat(4) {
            open()
            if (it == 0) screenshot("new-notebook.png")
            click("Dots");click("Infinite canvas")
            compose.onNodeWithText("Automatically add next page").assertDoesNotExist()
            compose.onNodeWithText("Paper size").assertDoesNotExist()
            if (it == 0) screenshot("infinite-notebook.png")
            if (it % 2 == 0) compose.onNodeWithText("Cancel",useUnmergedTree=true).performClick()
            else compose.onNode(isDialog()).performTouchInput { click(androidx.compose.ui.geometry.Offset(2f, 2f)) }
            compose.waitForIdle()
            compose.onNode(isDialog()).assertDoesNotExist()
        }
        assertEquals(0,count("notebooks"));assertEquals(0,count("pages"));assertEquals(before,assets())
    }

    @Test fun creationControlsPersistExactTemplateAndRejectDoubleCreate() {
        open();compose.onNodeWithText("Notebook title").performTextInput("Template UI")
        click("Dots")
        click("More paper options")
        compose.onNodeWithText("Spacing in mm (2–10)").performScrollTo().performTextReplacement("3.5")
        click("Large")
        click("Custom")
        compose.onNodeWithText("Custom color #RRGGBB").performScrollTo().performTextReplacement("#123ABC")
        click("LETTER");click("Landscape")
        compose.onNodeWithText("Landscape").assertIsSelected()
        compose.onNodeWithContentDescription("Choose folder").performScrollTo().performClick()
        compose.onAllNodesWithText("Test folder").filter(hasAnyAncestor(isPopup())).onFirst().performClick()
        val debugEvidence=File(compose.activity.filesDir,"phase9-evidence").apply {mkdirs()}
        File(debugEvidence,"folder-dialog.txt").writeText(compose.onNode(isDialog()).printToString())
        File(debugEvidence,"folder-dialog.png").outputStream().use {
            compose.onNode(isDialog()).captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
        }
        compose.onAllNodesWithText("Test folder").filter(hasAnyAncestor(isDialog())).onFirst().assertIsSelected()
        compose.onNodeWithContentDescription("Paper preview").performScrollTo()
        val evidence=File(compose.activity.filesDir,"phase9-evidence").apply {mkdirs()}
        File(evidence,"creation-preview.png").outputStream().use {
            compose.onNodeWithContentDescription("Paper preview").captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
        }
        // Queue two taps before the composition updates. Only the first may persist.
        compose.onNodeWithText("Create",useUnmergedTree=true).performTouchInput {click();click()}
        compose.waitUntil(15000) {opened!=null}
        val id=opened!!;val page=runBlocking {repo.firstPage(id)!!};val notebook=runBlocking {repo.notebook(id)!!}
        assertEquals(1,count("notebooks"));assertEquals(1,count("pages"))
        assertEquals("folder",notebook.folderId);assertTrue(notebook.autoMatchingPages)
        assertEquals(PaperTemplate("dots",792f,612f,3.5f,"bold",0xFF123ABC.toInt()),page.paperTemplate())
        lateinit var editor:EditorViewModel
        compose.activityRule.scenario.onActivity {activity ->
            editor=EditorViewModel(id,repo,PdfRepository(activity,db.pdfDao()));store.put("editor",editor)
            activity.setContent {NoteinTheme {EditorScreen(editor) {}}}
        }
        compose.waitUntil(15000) {!editor.uiState.value.isLoading && !editor.uiState.value.pageBusy}
        File(evidence,"created-editor.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
        }
        compose.onNodeWithText("Export",useUnmergedTree=true).performScrollTo().performClick()
        compose.onNodeWithText("Whole notebook").assertExists()
        compose.onNodeWithText("Current page").assertExists()
        compose.onNodeWithText("Cancel",useUnmergedTree=true).performClick()
    }

    @Test fun patternSwitchesUseValidDefaultsAndNumericRanges() {
        open();click("Lined");click("More paper options")
        compose.onNodeWithText("Spacing in mm (4–12)").performScrollTo().performTextReplacement("12")
        click("Squares")
        compose.onNodeWithText("Spacing: 5.0 mm").assertExists()
        compose.onNodeWithText("Spacing in mm (2–10)").performScrollTo().performTextReplacement("20")
        compose.onNodeWithText("Spacing: 5.0 mm").assertExists()
        click("Fine 2 mm")
        compose.onNodeWithText("Spacing: 2.0 mm").assertExists()
        click("Lined")
        compose.onNodeWithText("Spacing: 7.0 mm").assertExists()
        compose.onNodeWithText("Cancel",useUnmergedTree=true).performClick()
        assertEquals(0,count("notebooks"))
    }

    @Test fun visualChoicesKeepTheirValuesAcrossNotebookTypesAndCreateWithAutomaticPagesDisabled() {
        open()
        val configuration = compose.onNodeWithTag("Notebook configuration")
        val scroll = androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange
        val beforeScroll = configuration.fetchSemanticsNode().config[scroll].value()
        configuration.performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertTrue("The form must still scroll with a finger", configuration.fetchSemanticsNode().config[scroll].value() > beforeScroll)
        compose.onNodeWithText("Notebook title").performScrollTo().performTextInput("A little room to think")
        click("Squares")
        compose.onNodeWithText("Squares").assertIsSelected()
        compose.onNodeWithContentDescription("Paper color Cream").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Paper color Cream").assertIsSelected()
        click("A5"); click("Landscape")
        compose.onNodeWithText("Automatically add next page").performScrollTo().performClick()
        click("Infinite canvas")
        compose.onNodeWithText("Paper size").assertDoesNotExist()
        compose.onNodeWithText("Squares").assertIsSelected()
        screenshot("infinite-configured.png")
        click("Standard pages")
        compose.onNodeWithText("A5").assertIsSelected()
        compose.onNodeWithText("Landscape").assertIsSelected()
        compose.onNodeWithText("Automatically add next page").assertIsOff()
        click("Paper layout")
        screenshot("paper-choices.png")
        compose.onNodeWithText("Create", useUnmergedTree = true).performClick()
        compose.waitUntil(15000) { opened != null }
        val notebook = runBlocking { repo.notebook(opened!!)!! }
        val page = runBlocking { repo.firstPage(opened!!)!! }
        assertFalse(notebook.autoMatchingPages)
        assertEquals("A little room to think", notebook.title)
        assertEquals(PaperTemplate("grid",595f,420f,5f,"normal",PaperColor.CREAM.argb), page.paperTemplate())
    }
}
