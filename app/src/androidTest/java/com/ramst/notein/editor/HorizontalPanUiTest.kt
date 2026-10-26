package com.ramst.notein.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ramst.notein.MainActivity
import com.ramst.notein.storage.EditableObjectEntity
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
class HorizontalPanUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var fixture: Phase10Fixture
    private lateinit var model: EditorViewModel
    private lateinit var notebook: String
    private lateinit var marker: EditableObjectEntity
    private val store = ViewModelStore()

    @Before fun setup() = runBlocking {
        fixture = Phase10Fixture()
        notebook = fixture.notebook()
        repeat(2) { fixture.editor.createPage(notebook, NotebookType.STANDARD) }
        val pages = fixture.editor.pages(notebook)
        fixture.editor.updatePageTemplate(pages[1].id, PaperTemplate("blank", 500f, 500f, backgroundColor = 0xFFDFF1E4.toInt()))
        marker = EditableObjectEntity("pan-marker", pages[0].id, SHAPE_OBJECT_TYPE,
            ObjectTransform(195.0, 50.0).write(ShapeSpec(width = 30f, height = 30f, color = Color.RED, filled = true).json()).toString(), 1, 1)
        fixture.editor.saveContentSnapshot(listOf(marker), pages[0])
        fixture.editor.selectPage(notebook, pages[0].id)
        fixture.editor.setPageLayout(notebook, PageLayout.VERTICAL)
        open()
    }
    @After fun cleanup() { compose.activityRule.scenario.onActivity { store.clear() }; fixture.close() }
    private fun open() {
        compose.activityRule.scenario.onActivity { activity ->
            store.clear(); model = EditorViewModel(notebook, fixture.editor, fixture.pdf); store.put("horizontal-pan", model)
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
    private fun drag(dx: Float, dy: Float = 0f) {
        canvas {
            val x = it.width / 2f; val y = it.height * .65f
            phase4Event(it, MotionEvent.ACTION_DOWN, finger(x, y))
            phase4Event(it, MotionEvent.ACTION_MOVE, finger(x + dx, y + dy))
            assertEquals(0, model.uiState.value.pendingInk)
            phase4Event(it, MotionEvent.ACTION_UP, finger(x + dx, y + dy))
        }
        saved()
    }
    private fun zoom(layout: PageLayout, locked: Boolean) {
        compose.activityRule.scenario.onActivity { model.setPageLayout(layout) }
        compose.waitUntil(15000) { model.uiState.value.notebook?.pageLayout == layout.storageValue }
        saved()
        canvas {
            val page = model.uiState.value.page!!
            val scale = (it.width / page.pageWidth * 1.6f).coerceIn(2f, 6f)
            model.updateViewport((it.width - page.pageWidth * scale) / 2f, -100f, scale)
        }
        saved()
        if (locked) {
            compose.activityRule.scenario.onActivity { model.setViewportLocked(true) }
            compose.waitUntil(15000) { model.uiState.value.notebook!!.viewportLocked && !model.uiState.value.pageBusy }
            saved()
        }
    }
    private fun capture(): Bitmap {
        lateinit var result: Bitmap
        canvas { result = Bitmap.createBitmap(it.width, it.height, Bitmap.Config.ARGB_8888); it.draw(Canvas(result)) }
        return result
    }
    private fun markerCentre(): Float {
        val image = capture()
        var left = image.width; var right = -1
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val color = image.getPixel(x, y)
            if (Color.red(color) > 200 && Color.green(color) < 60 && Color.blue(color) < 60) {
                left = minOf(left, x); right = maxOf(right, x)
            }
        }
        image.recycle()
        assertTrue("The marker should be visible on the zoomed paper", right >= left)
        return (left + right) / 2f
    }
    private fun screenshot(name: String) {
        val dir = File(compose.activity.filesDir, "horizontal-pan-evidence").apply { mkdirs() }
        File(dir, name).outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun exercise(layout: PageLayout, locked: Boolean) = runBlocking {
        zoom(layout, locked)
        val page = model.uiState.value.page!!
        val initialX = page.viewportX
        val initialMarker = markerCentre()
        drag(-80f)
        assertEquals(initialX - 80f, model.uiState.value.page!!.viewportX, .1f)
        assertEquals(initialMarker - 80f, markerCentre(), 1f)
        drag(130f)
        val panned = model.uiState.value.page!!
        assertEquals(initialX + 50f, panned.viewportX, .1f)
        assertEquals(initialMarker + 50f, markerCentre(), 1f)
        assertEquals(page.id, panned.id)
        assertEquals(page.viewportScale, panned.viewportScale, 0f)
        assertEquals(panned.viewportX, fixture.editor.pages(notebook).first().viewportX, .1f)
        screenshot("${layout.storageValue}-${if (locked) "locked" else "unlocked"}.png")
        open()
        assertEquals(panned.viewportX, model.uiState.value.page!!.viewportX, .1f)
        assertEquals(page.viewportScale, model.uiState.value.page!!.viewportScale, 0f)
        assertEquals(locked, model.uiState.value.notebook!!.viewportLocked)
        assertEquals(marker.payload, fixture.editor.pageObjects(page.id).single().payload)
        if (locked) {
            compose.activityRule.scenario.onActivity { model.setViewportLocked(false) }
            compose.waitUntil(15000) { !model.uiState.value.notebook!!.viewportLocked && !model.uiState.value.pageBusy }
            saved()
            assertEquals(panned.viewportX, model.uiState.value.page!!.viewportX, .1f)
            drag(-40f)
            assertEquals(panned.viewportX - 40f, model.uiState.value.page!!.viewportX, .1f)
        }
    }
    @Test fun verticalPaperPansAndReopensWhileUnlocked() = exercise(PageLayout.VERTICAL, false)
    @Test fun verticalPaperPansAndReopensWhileLocked() = exercise(PageLayout.VERTICAL, true)
    @Test fun horizontalPaperPansAndReopensWhileUnlocked() = exercise(PageLayout.HORIZONTAL, false)
    @Test fun horizontalPaperPansAndReopensWhileLocked() = exercise(PageLayout.HORIZONTAL, true)

    @Test fun mixedWidthVerticalPagesKeepTheirHorizontalOffsetAcrossPageChanges() {
        zoom(PageLayout.VERTICAL, true)
        drag(-80f)
        val first = model.uiState.value.page!!
        val second = model.uiState.value.pages[1]
        var targetY = 0f
        canvas {
            targetY = it.height * .25f
            val distance = first.viewportY + first.pageHeight * first.viewportScale + 24f * it.resources.displayMetrics.density - targetY
            val x = it.width / 2f; val y = it.height * .8f
            phase4Event(it, MotionEvent.ACTION_DOWN, finger(x, y))
            phase4Event(it, MotionEvent.ACTION_MOVE, finger(x, y - distance))
        }
        compose.waitForIdle()
        val before = capture()
        canvas { phase4Event(it, MotionEvent.ACTION_UP, finger(it.width / 2f, 0f)) }
        compose.waitUntil(15000) { model.uiState.value.page?.id == second.id && !model.uiState.value.pageBusy }
        saved()
        val selected = model.uiState.value.page!!
        assertEquals((selected.viewportWidth - second.pageWidth * selected.viewportScale) / 2f - 80f, selected.viewportX, .1f)
        assertEquals(targetY, selected.viewportY, .1f)
        val after = capture()
        for (y in 10 until after.height step 20) for (x in 10 until after.width step 20)
            assertEquals("Page change moved paper at $x,$y", before.getPixel(x, y), after.getPixel(x, y))
        before.recycle(); after.recycle()
        screenshot("vertical-page-handoff.png")
        open()
        assertEquals(selected.viewportX, model.uiState.value.page!!.viewportX, .1f)
    }
}
