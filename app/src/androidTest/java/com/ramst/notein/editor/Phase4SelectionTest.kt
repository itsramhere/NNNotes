package com.ramst.notein.editor

import android.content.Context
import android.graphics.PointF
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ramst.notein.storage.EditableObjectEntity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

internal fun phase4Text(page: String = "phase4", x: Double = 300.0, y: Double = 100.0,
    width: Float = 100f, height: Float = 80f, text: String = "Editable text", time: Long = 2) =
    EditableObjectEntity(UUID.randomUUID().toString(), page, TEXT_OBJECT_TYPE,
        ObjectTransform(x,y).write(JSONObject().put("width",width).put("height",height).put("text",text)
            .put("fontSize",24).put("color",android.graphics.Color.RED)).toString(),time,time)

internal fun phase4Image(page: String = "phase4", asset: String = UUID.randomUUID().toString(),
    x: Double = 500.0, y: Double = 100.0, time: Long = 3) =
    EditableObjectEntity(UUID.randomUUID().toString(),page,IMAGE_OBJECT_TYPE,
        ObjectTransform(x,y).write(JSONObject().put("width",100).put("height",80).put("assetId",asset)).toString(),time,time)

internal fun rectangle(left: Float, top: Float, right: Float, bottom: Float) = listOf(
    PointF(left,top),PointF(right,top),PointF(right,bottom),PointF(left,bottom))

@RunWith(AndroidJUnit4::class)
class Phase4SelectionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private var time = 1000L
    private data class Pointer(val id: Int, val x: Float, val y: Float, val tool: Int = MotionEvent.TOOL_TYPE_STYLUS)
    private fun event(overlay: SelectionOverlay, action: Int, vararg points: Pointer, flags: Int = 0) {
        time += 20
        val props = points.map { p -> MotionEvent.PointerProperties().apply { id=p.id; toolType=p.tool } }.toTypedArray()
        val coords = points.map { p -> MotionEvent.PointerCoords().apply { x=p.x; y=p.y; pressure=0.5f; size=0.1f } }.toTypedArray()
        val motion = MotionEvent.obtain(1000,time,action,points.size,props,coords,0,0,1f,1f,0,0,InputDevice.SOURCE_STYLUS,flags)
        try { overlay.handle(motion) } finally { motion.recycle() }
    }
    @Test fun lassoSelectsMixedContentAtDifferentZoomsAndRejectsEmptyDiagonalBounds() = main {
        val ink = record(100f,100f,200f,150f,pageId="phase4").toEntity()
        val objects = listOf(ink,phase4Text(),phase4Image())
        for (scale in listOf(0.25f,1f,4f)) {
            var selected = emptySet<String>()
            val overlay = SelectionOverlay(context).apply {
                selectionEnabled=true; this.objects=objects; viewportScale=scale; viewportX=40f; viewportY=80f
                onLasso={ p -> selected=objects.filter { contentIntersectsLasso(it,p) }.map { it.id }.toSet() }
            }
            val poly=rectangle(-20f,-20f,650f,250f)
            fun point(p: PointF)=Pointer(0,p.x*scale+40f,p.y*scale+80f)
            event(overlay,MotionEvent.ACTION_DOWN,point(poly.first()))
            poly.drop(1).forEach { event(overlay,MotionEvent.ACTION_MOVE,point(it)) }
            event(overlay,MotionEvent.ACTION_UP,point(poly.first()))
            assertEquals(objects.map { it.id }.toSet(),selected)
        }
        val diagonal=record(100f,100f,300f,300f).toEntity()
        assertFalse(contentIntersectsLasso(diagonal,rectangle(95f,270f,125f,300f)))
        assertTrue(contentIntersectsLasso(diagonal,rectangle(195f,195f,205f,205f)))
    }
    @Test fun smallSelectionCenterMovesInsteadOfResizingAtLowZoom() = main {
        val text=phase4Text(x=100.0,y=100.0,width=40f,height=40f)
        var transform: Triple<Float,Float,Float>?=null
        val overlay=SelectionOverlay(context).apply {
            selectionEnabled=true; objects=listOf(text); selectedIds=setOf(text.id); viewportScale=0.25f
            onTransform={ x,y,s -> transform=Triple(x,y,s) }
        }
        event(overlay,MotionEvent.ACTION_DOWN,Pointer(0,30f,30f))
        event(overlay,MotionEvent.ACTION_MOVE,Pointer(0,80f,80f))
        event(overlay,MotionEvent.ACTION_UP,Pointer(0,80f,80f))
        assertEquals(Triple(200f,200f,1f),transform)
    }
    @Test fun nearestBottomRightResizeHandleKeepsOppositeCornerFixed() = main {
        val text=phase4Text(x=100.0,y=100.0,width=100f,height=100f)
        var transform: Triple<Float,Float,Float>?=null
        val overlay=SelectionOverlay(context).apply {
            selectionEnabled=true; objects=listOf(text); selectedIds=setOf(text.id); viewportScale=0.25f
            onTransform={ x,y,s -> transform=Triple(x,y,s) }
        }
        event(overlay,MotionEvent.ACTION_DOWN,Pointer(0,50f,50f))
        event(overlay,MotionEvent.ACTION_MOVE,Pointer(0,100f,100f))
        event(overlay,MotionEvent.ACTION_UP,Pointer(0,100f,100f))
        assertEquals(0f,transform!!.first,0f); assertEquals(0f,transform!!.second,0f)
        assertEquals(3f,transform!!.third,0.001f)
    }
    @Test fun tapSelectsTopmostObjectButNotEmptySpaceInStrokeBounds() = main {
        val diagonal=record(100f,100f,300f,300f).toEntity()
        var selected: String?="unset"
        val overlay=SelectionOverlay(context).apply { selectionEnabled=true; objects=listOf(diagonal); onSelect={selected=it} }
        event(overlay,MotionEvent.ACTION_DOWN,Pointer(0,110f,280f)); event(overlay,MotionEvent.ACTION_UP,Pointer(0,110f,280f))
        assertNull(selected)
        val first=phase4Text(x=100.0,y=100.0); val top=phase4Text(x=100.0,y=100.0,time=3)
        overlay.objects=listOf(first,top)
        event(overlay,MotionEvent.ACTION_DOWN,Pointer(0,130f,130f)); event(overlay,MotionEvent.ACTION_UP,Pointer(0,130f,130f))
        assertEquals(top.id,selected)
    }
    @Test fun unrelatedCanceledPalmDoesNotCancelStylusSelection() = main {
        var commits=0
        val overlay=SelectionOverlay(context).apply { selectionEnabled=true; onLasso={commits++} }
        val finger=MotionEvent.TOOL_TYPE_FINGER
        event(overlay,MotionEvent.ACTION_DOWN,Pointer(0,10f,10f))
        event(overlay,MotionEvent.ACTION_MOVE,Pointer(0,400f,10f))
        event(overlay,MotionEvent.ACTION_POINTER_DOWN or (1 shl 8),Pointer(0,400f,10f),Pointer(1,800f,800f,finger))
        event(overlay,MotionEvent.ACTION_POINTER_UP or (1 shl 8),Pointer(0,400f,10f),Pointer(1,800f,800f,finger),flags=MotionEvent.FLAG_CANCELED)
        event(overlay,MotionEvent.ACTION_MOVE,Pointer(0,400f,400f))
        event(overlay,MotionEvent.ACTION_UP,Pointer(0,10f,400f))
        assertEquals(1,commits)
    }
    @Test fun stylusPointerUpCommitsBeforeRemainingPalmLifts() = main {
        var pending=0; var commits=0
        val overlay=SelectionOverlay(context).apply { selectionEnabled=true; onPending={pending=it}; onLasso={commits++} }
        val finger=MotionEvent.TOOL_TYPE_FINGER
        event(overlay,MotionEvent.ACTION_DOWN,Pointer(0,10f,10f))
        event(overlay,MotionEvent.ACTION_MOVE,Pointer(0,400f,10f))
        event(overlay,MotionEvent.ACTION_POINTER_DOWN or (1 shl 8),Pointer(0,400f,10f),Pointer(1,800f,800f,finger))
        event(overlay,MotionEvent.ACTION_MOVE,Pointer(0,400f,400f),Pointer(1,800f,800f,finger))
        event(overlay,MotionEvent.ACTION_POINTER_UP,Pointer(0,10f,400f),Pointer(1,800f,800f,finger))
        assertEquals(0,pending); assertEquals(1,commits)
    }
    @Test fun canceledLassoMoveAndResizeNeverCommit() = main {
        val text=phase4Text(x=100.0,y=100.0,width=400f,height=400f)
        for (point in listOf(PointF(0f,0f),PointF(300f,300f),PointF(500f,500f))) {
            var committed=0; var pending=0
            val overlay=SelectionOverlay(context).apply {
                selectionEnabled=true; objects=listOf(text); selectedIds=setOf(text.id)
                onTransform={_,_,_->committed++}; onLasso={committed++}; onPending={pending=it}
            }
            event(overlay,MotionEvent.ACTION_DOWN,Pointer(0,point.x,point.y))
            event(overlay,MotionEvent.ACTION_MOVE,Pointer(0,point.x+100f,point.y+100f))
            event(overlay,MotionEvent.ACTION_CANCEL,Pointer(0,point.x+100f,point.y+100f))
            assertEquals(0,committed); assertEquals(0,pending)
        }
    }
    @Test fun repeatedResizePreservesSourceSamplesAndAccurateTransforms() {
        val original=record(pageId="phase4").toEntity()
        var current=original
        repeat(1000) {
            current=transformContent(current,0f,0f,0f,0f,1.25f)
            current=transformContent(current,0f,0f,0f,0f,0.8f)
        }
        assertEquals(JSONObject(original.payload).getString("inputs"),JSONObject(current.payload).getString("inputs"))
        val inputs=current.toInkStrokeRecord()!!.decode()!!.stroke.inputs
        assertEquals(100f,inputs[0].x,0.01f)
        assertEquals(300f,inputs[1].x,0.01f)
        assertEquals(1.0,ObjectTransform.read(JSONObject(current.payload)).scale,0.00002)
    }
}
