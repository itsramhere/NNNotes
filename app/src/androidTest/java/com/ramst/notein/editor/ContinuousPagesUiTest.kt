package com.ramst.notein.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ramst.notein.MainActivity
import com.ramst.notein.storage.NotebookType
import com.ramst.notein.storage.PageLayout
import com.ramst.notein.storage.PaperTemplate
import com.ramst.notein.ui.theme.NoteinTheme
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ContinuousPagesUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var fixture: Phase10Fixture
    private lateinit var model: EditorViewModel
    private lateinit var notebook: String
    private val store = ViewModelStore()
    private val colors = listOf(0xFFFFF2D2.toInt(), 0xFFDFF1E4.toInt(), 0xFFDFEAFE.toInt())

    @Before fun setup() = runBlocking {
        fixture = Phase10Fixture()
        notebook = fixture.notebook()
        repeat(2) { fixture.editor.createPage(notebook, NotebookType.STANDARD) }
        fixture.editor.pages(notebook).forEachIndexed { index, page ->
            fixture.db.editorDao().updatePageTemplateAndTouchNotebook(page.id,
                PaperTemplate("blank", 420f, if (index == 1) 500f else 595f, backgroundColor = colors[index]), 1L)
        }
        fixture.editor.selectPage(notebook, fixture.editor.pages(notebook).first().id)
        fixture.editor.setPageLayout(notebook, PageLayout.VERTICAL)
        open()
    }
    @After fun cleanup() { compose.activityRule.scenario.onActivity { store.clear() }; fixture.close() }
    private fun open() {
        compose.activityRule.scenario.onActivity { activity ->
            store.clear(); model = EditorViewModel(notebook, fixture.editor, fixture.pdf); store.put("continuous", model)
            activity.setContent { NoteinTheme { EditorScreen(model) {} } }
        }
        saved()
    }
    private fun saved() {
        compose.waitUntil(15000) { !model.uiState.value.isLoading && !model.uiState.value.pageBusy && !model.uiState.value.hasUnsavedChanges }
        compose.waitForIdle()
    }
    private fun canvas(block: (InkCanvasView) -> Unit) = compose.activityRule.scenario.onActivity {
        block(checkNotNull(findInkCanvas(it.window.decorView)))
    }
    private fun finger(x: Float, y: Float) = Phase4Pointer(x, y, 0, MotionEvent.TOOL_TYPE_FINGER)
    private fun capture(): Bitmap {
        lateinit var image: Bitmap
        canvas { image = Bitmap.createBitmap(it.width, it.height, Bitmap.Config.ARGB_8888); it.draw(Canvas(image)) }
        return image
    }
    private fun screenshot(name: String) {
        val dir = File(compose.activity.filesDir, "continuous-evidence").apply { mkdirs() }
        File(dir, name).outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun adjacentPaperStaysVisibleAndReleaseRebasesWithoutMovingAnyPixels() = runBlocking {
        val first = model.uiState.value.page!!
        val second = model.uiState.value.pages[1]
        var targetY = 0f
        canvas {
            targetY = it.height * .3f
            val distance = first.pageHeight * it.viewport().third + 24f * it.resources.displayMetrics.density - targetY
            phase4Event(it, MotionEvent.ACTION_DOWN, finger(it.width / 2f, it.height * .8f))
            phase4Event(it, MotionEvent.ACTION_MOVE, finger(it.width / 2f, it.height * .8f - distance))
        }
        compose.waitForIdle()
        val before = capture()
        assertEquals(colors[0], before.getPixel(before.width / 2, (before.height * .1f).toInt()))
        assertEquals(colors[1], before.getPixel(before.width / 2, (before.height * .5f).toInt()))
        canvas { phase4Event(it, MotionEvent.ACTION_UP, finger(it.width / 2f, 0f)) }
        compose.waitUntil(15000) { model.uiState.value.page?.id == second.id && !model.uiState.value.pageBusy }
        saved()
        assertEquals(targetY, model.uiState.value.page!!.viewportY, .1f)
        val after = capture()
        for (y in 10 until after.height step 20) for (x in 10 until after.width step 20)
            assertEquals("Paper moved on release at $x,$y", before.getPixel(x, y), after.getPixel(x, y))
        screenshot("adjacent-pages.png")
        before.recycle(); after.recycle()
        canvas { drawSyntheticStroke(it, it.width / 2f, targetY + 40f) }
        saved()
        assertEquals(second.id, model.uiState.value.strokes.single().pageId)
        open()
        assertEquals(second.id, model.uiState.value.page!!.id)
        assertEquals(targetY, model.uiState.value.page!!.viewportY, .1f)
        assertEquals(1, fixture.editor.pageObjects(second.id).size)
    }

    @Test fun scrollingAndPinchingStaySavedWhileNavigationStillProtectsTheGesture() {
        fun assertScrolling() {
            val state = model.uiState.value
            assertTrue(state.navigationInProgress)
            assertTrue(state.hasPendingInput)
            assertEquals(0, state.pendingInk)
            assertEquals("Saved", state.saveMessage)
        }
        canvas {
            val x = it.width / 2f; val y = it.height * .7f
            phase4Event(it, MotionEvent.ACTION_DOWN, finger(x, y)); assertScrolling()
            phase4Event(it, MotionEvent.ACTION_MOVE, finger(x, y - 80f)); assertScrolling()
        }
        compose.onNodeWithText("Finishing…").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back to notebooks").assertIsNotEnabled()
        screenshot("scrolling-saved-status.png")
        canvas {
            val x = it.width / 2f; val y = it.height * .7f
            phase4Event(it, MotionEvent.ACTION_MOVE, finger(x, y)); assertScrolling()
            phase4Event(it, MotionEvent.ACTION_CANCEL, finger(x, y))
            assertFalse(model.uiState.value.navigationInProgress)
            assertFalse(model.uiState.value.hasPendingInput)
            assertEquals("Saved", model.uiState.value.saveMessage)
            val before = it.viewport().third
            // Keep this pinch away from the previous tap so Android does not enter quick-scale mode.
            val pinchY = it.height * .25f
            fun left(spread: Float) = Phase4Pointer(x - spread, pinchY, 0, MotionEvent.TOOL_TYPE_FINGER)
            fun right(spread: Float) = Phase4Pointer(x + spread, pinchY, 1, MotionEvent.TOOL_TYPE_FINGER)
            phase4Event(it, MotionEvent.ACTION_DOWN, left(100f)); assertScrolling()
            phase4Event(it, MotionEvent.ACTION_POINTER_DOWN or (1 shl 8), left(100f), right(100f)); assertScrolling()
            repeat(10) { step ->
                phase4Event(it, MotionEvent.ACTION_MOVE, left(110f + step * 40f), right(110f + step * 40f)); assertScrolling()
            }
            assertTrue("Pinch should increase zoom from $before; actual ${it.viewport().third}", it.viewport().third > before)
            phase4Event(it, MotionEvent.ACTION_POINTER_UP or (1 shl 8), left(470f), right(470f)); assertScrolling()
            phase4Event(it, MotionEvent.ACTION_UP, left(470f))
        }
        saved()
        assertFalse(model.uiState.value.navigationInProgress)
        assertEquals(0, model.uiState.value.pendingInk)
        compose.onNodeWithText("Finishing…").assertDoesNotExist()
    }

    @Test fun actualPenInputStillReportsPendingInkUntilTheStrokeIsFinished() {
        canvas {
            val page = model.uiState.value.page!!
            val x = it.viewport().first + page.pageWidth * it.viewport().third * .4f
            val y = it.height * .3f
            phase4Event(it, MotionEvent.ACTION_DOWN, Phase4Pointer(x, y))
            assertTrue(model.uiState.value.pendingInk > 0)
            assertTrue(model.uiState.value.hasPendingInput)
            assertFalse(model.uiState.value.navigationInProgress)
            assertEquals("Finishing ink…", model.uiState.value.saveMessage)
            phase4Event(it, MotionEvent.ACTION_MOVE, Phase4Pointer(x + 30f, y + 15f))
            phase4Event(it, MotionEvent.ACTION_UP, Phase4Pointer(x + 60f, y + 20f))
        }
        compose.waitUntil(15000) { model.uiState.value.pendingInk == 0 && !model.uiState.value.hasUnsavedChanges }
        saved()
        assertEquals(1, model.uiState.value.strokes.size)
        assertEquals("Saved", model.uiState.value.saveMessage)
    }

    @Test fun aSingleDragCanCrossSeveralPagesAndTheNotebookEndsStayBounded() {
        val last = model.uiState.value.pages.last()
        canvas {
            phase4Event(it, MotionEvent.ACTION_DOWN, finger(it.width / 2f, it.height * .8f))
            phase4Event(it, MotionEvent.ACTION_MOVE, finger(it.width / 2f, -it.height * 8f))
            phase4Event(it, MotionEvent.ACTION_UP, finger(it.width / 2f, -it.height * 8f))
        }
        compose.waitUntil(15000) { model.uiState.value.page?.id == last.id && !model.uiState.value.pageBusy }
        saved()
        canvas {
            assertEquals(it.height.toFloat(), it.viewport().second + last.pageHeight * it.viewport().third, .1f)
            phase4Event(it, MotionEvent.ACTION_DOWN, finger(it.width / 2f, it.height * .8f))
            phase4Event(it, MotionEvent.ACTION_MOVE, finger(it.width / 2f, it.height * 10f))
            phase4Event(it, MotionEvent.ACTION_UP, finger(it.width / 2f, it.height * 10f))
        }
        compose.waitUntil(15000) { model.uiState.value.page?.id == model.uiState.value.pages.first().id && !model.uiState.value.pageBusy }
        saved()
        canvas { assertEquals(0f, it.viewport().second, .1f) }
    }
}
