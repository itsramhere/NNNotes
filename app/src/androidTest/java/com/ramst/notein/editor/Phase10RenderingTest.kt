package com.ramst.notein.editor

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ramst.notein.storage.EditableObjectEntity
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class Phase10RenderingTest {
    private fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private fun objects(count: Int) = (0 until count).map { index ->
        EditableObjectEntity("pdf-$index", "page", PDF_OBJECT_TYPE, JSONObject().put("width", 200).put("height", 300)
            .put("pdfSourceId", "00000000-0000-0000-0000-000000000010").put("pdfPath", "/unused.pdf").put("pdfPageIndex", index).toString(), 1, 1).toContentObject()!!
    }
    @Test fun overlappingPdfObjectsLimitConcurrentWorkAndSharePreviewPixelBudget() {
        val active = AtomicInteger(); val maximum = AtomicInteger(); val completed = AtomicInteger()
        val slotsStarted = CountDownLatch(2); val proceed = CountDownLatch(1); val pixels = AtomicInteger()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val renderer = ContentRenderer(context, { completed.incrementAndGet() }, { fail(it) }, { _, _, width, height ->
            val count = active.incrementAndGet(); maximum.updateAndGet { maxOf(it, count) }
            slotsStarted.countDown(); proceed.await(10, TimeUnit.SECONDS)
            try { pixels.addAndGet(width * height); Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }
            finally { active.decrementAndGet() }
        })
        try {
            main { renderer.attach(); renderer.prepare(objects(20), 6f) }
            assertTrue(slotsStarted.await(10, TimeUnit.SECONDS)); assertEquals(2, active.get())
            proceed.countDown()
            val end = SystemClock.uptimeMillis() + 15000
            while (completed.get() < 20 && SystemClock.uptimeMillis() < end) SystemClock.sleep(20)
            assertEquals(20, completed.get()); assertEquals(2, maximum.get())
            assertTrue("Visible previews exceeded the shared 16 MiB pixel budget", pixels.get().toLong() * 4 <= 16 * 1024 * 1024)
        } finally { proceed.countDown(); main { renderer.detach() } }
    }
    @Test fun detachDuringIoHandoffRecyclesTheCancelledBitmap() {
        val allocated = CountDownLatch(1); val proceed = CountDownLatch(1); val reference = AtomicReference<Bitmap>()
        val renderer = ContentRenderer(ApplicationProvider.getApplicationContext<Context>(), {}, { fail(it) }, { _, _, _, _ ->
            val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
            reference.set(bitmap); allocated.countDown(); proceed.await(10, TimeUnit.SECONDS); bitmap
        })
        try {
            main { renderer.attach(); renderer.prepare(objects(1)) }
            assertTrue(allocated.await(10, TimeUnit.SECONDS)); main { renderer.detach() }; proceed.countDown()
            val end = SystemClock.uptimeMillis() + 10000
            while (!reference.get().isRecycled && SystemClock.uptimeMillis() < end) SystemClock.sleep(20)
            assertTrue("Cancellation lost the allocated bitmap between IO and Main", reference.get().isRecycled)
        } finally { proceed.countDown(); main { renderer.detach() } }
    }
}
