package com.ramst.notein.editor

import android.content.Context
import android.graphics.Bitmap
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
class Phase4UiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private var id:String?=null
    private val title="Phase4-UI-${UUID.randomUUID()}"
    private val notebooks get()=(compose.activity.application as NoteinApplication).container.notebookRepository
    private val repo get()=(compose.activity.application as NoteinApplication).container.editorRepository
    private val assetsToRemove=mutableListOf<String>()
    private var source:File?=null
    private fun open(mixed:Boolean=false) {
        id=runBlocking {notebooks.createNotebook(title,null)}
        if(mixed)runBlocking {
            val page=repo.firstPage(id!!)!!
            val bitmap=Bitmap.createBitmap(100,80,Bitmap.Config.ARGB_8888).apply {eraseColor(android.graphics.Color.BLUE)}
            val file=File(compose.activity.cacheDir,"phase4-ui-${UUID.randomUUID()}.png");source=file
            try {file.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}finally {bitmap.recycle()}
            val imported=ImageAssets(compose.activity).import(android.net.Uri.fromFile(file));assetsToRemove+=imported.assetId
            val base=System.currentTimeMillis()
            val ink=record(100f,100f,220f,100f,pageId=page.id).toEntity().copy(createdAt=base)
            repo.saveContentSnapshot(listOf(ink,phase4Text(page.id,x=70.0,y=250.0,time=base+1),
                phase4Image(page.id,imported.assetId,x=280.0,y=100.0,time=base+2)),page)
        }
        compose.onNodeWithText("Search titles").performTextReplacement(title)
        compose.activityRule.scenario.onActivity {activity->
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(activity.window.decorView.windowToken,0)
        }
        compose.waitUntil(15000) {compose.onAllNodes(hasText(title) and !hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()}
        compose.onNode(hasText(title) and !hasSetTextAction()).performClick();saved()
    }
    private fun objects()=runBlocking {repo.pageObjects(repo.firstPage(id!!)!!.id)}
    private fun saved() {compose.waitUntil(15000) {
        compose.onAllNodesWithText("Saved").fetchSemanticsNodes().isNotEmpty() &&
            compose.onAllNodesWithContentDescription("Back to notebooks").fetchSemanticsNodes().any {
                !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
            }
    }}
    private fun contentClick(text:String) {
        when (text) {
            "Lasso" -> compose.onNodeWithContentDescription("Lasso").performScrollTo().performClick()
            "Add text", "Image file" -> {
                compose.onNodeWithContentDescription("Settings").performScrollTo().performClick()
                compose.onNodeWithText(text).performScrollTo().performClick()
            }
            else -> {
                compose.onNodeWithContentDescription("Content editing").performScrollToNode(hasText(text))
                compose.onNodeWithText(text).performClick()
            }
        }
        compose.waitForIdle()
    }
    private fun worldPointer(canvas: InkCanvasView, x: Float, y: Float): Phase4Pointer {
        val (offsetX, offsetY, scale) = canvas.viewport()
        return Phase4Pointer(offsetX + x * scale, offsetY + y * scale)
    }
    @After fun cleanup() {
        runBlocking {id?.let {notebooks.trash(it);notebooks.permanentlyDelete(it)}}
        val assets=ImageAssets(compose.activity)
        assetsToRemove.forEach {assets.original(it).delete();assets.preview(it).delete()};source?.delete()
    }
    @Test fun realMixedLassoCopyDragDeleteUndoAndRecreateRetainRenderedContent() {
        open(true);val original=objects().associate {it.id to it.payload}
        contentClick("Lasso")
        compose.onNodeWithText("Lasso").assertIsSelected()
        compose.onNodeWithText("Fit page").performScrollTo().performClick()
        saved(); compose.waitForIdle()
        compose.activityRule.scenario.onActivity {activity->
            val canvas=findInkCanvas(activity.window.decorView)!!
            // Start inside the sheet; an exact page-edge hit can be rejected by rounding.
            phase4Event(canvas,MotionEvent.ACTION_DOWN,worldPointer(canvas,10f,10f))
            listOf(worldPointer(canvas,500f,10f),worldPointer(canvas,500f,400f),worldPointer(canvas,10f,400f)).forEach {
                phase4Event(canvas,MotionEvent.ACTION_MOVE,it)
            }
            phase4Event(canvas,MotionEvent.ACTION_UP,worldPointer(canvas,10f,10f))
        }
        compose.waitForIdle()
        assertEquals("Lasso must not create an ink stroke",3,objects().size)
        contentClick("Copy 3");saved();assertEquals(6,objects().size)
        compose.activityRule.scenario.onActivity {activity->
            val canvas=findInkCanvas(activity.window.decorView)!!
            phase4Event(canvas,MotionEvent.ACTION_DOWN,worldPointer(canvas,240f,210f))
            phase4Event(canvas,MotionEvent.ACTION_MOVE,worldPointer(canvas,280f,260f))
            phase4Event(canvas,MotionEvent.ACTION_UP,worldPointer(canvas,280f,260f))
        }
        saved();val moved=objects().associate {it.id to it.payload}
        contentClick("Delete selection");saved();assertEquals(3,objects().size)
        compose.onNodeWithText("Undo").performClick();saved()
        assertEquals(moved,objects().associate {it.id to it.payload})
        assertEquals(original,objects().filter {it.id in original.keys}.associate {it.id to it.payload})
        compose.activityRule.scenario.recreate();saved()
        assertEquals(moved,objects().associate {it.id to it.payload})
        val image=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val location=IntArray(2)
        var sample = Phase4Pointer(0f,0f)
        compose.activityRule.scenario.onActivity {
            val canvas = findInkCanvas(it.window.decorView)!!
            canvas.getLocationOnScreen(location)
            sample = worldPointer(canvas,330f,140f)
        }
        assertEquals("Imported image must render after recreation",android.graphics.Color.BLUE,
            image.getPixel(location[0]+sample.x.toInt(),location[1]+sample.y.toInt()))
        File(compose.activity.getExternalFilesDir(null),"phase4-mixed.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
    }
    @Test fun textDialogSaveEditUndoAndCancelledFilePickerPreserveContent() {
        open()
        contentClick("Add text")
        compose.onNodeWithText("Text").performTextInput("A text box\nwith editable content")
        compose.onNodeWithText("Bold").performScrollTo().performClick()
        compose.onNodeWithText("Save").performClick();saved()
        val original=objects().single()
        assertTrue(original.toContentObject()!!.bold)
        contentClick("Edit text")
        compose.onNodeWithText("Text").performTextReplacement("Updated content")
        compose.onNodeWithText("Save").performClick();saved()
        assertEquals("Updated content",objects().single().toContentObject()!!.text)
        compose.onNodeWithText("Undo").performClick();saved();assertEquals(original.payload,objects().single().payload)
        contentClick("Image file")
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        compose.waitUntil(15000) {instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()?.endsWith(".documentsui")==true}
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        saved();assertEquals(original.payload,objects().single().payload)
    }
}
