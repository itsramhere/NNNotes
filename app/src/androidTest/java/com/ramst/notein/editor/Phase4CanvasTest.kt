package com.ramst.notein.editor

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ramst.notein.MainActivity
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

internal data class Phase4Pointer(val x:Float,val y:Float,val id:Int=0,val tool:Int=MotionEvent.TOOL_TYPE_STYLUS)
private var phase4Clock=SystemClock.uptimeMillis()
private var phase4Down=phase4Clock
internal fun phase4Event(view:View,action:Int,vararg points:Phase4Pointer,flags:Int=0) {
    phase4Clock=maxOf(phase4Clock+16,SystemClock.uptimeMillis())
    if(action==MotionEvent.ACTION_DOWN)phase4Down=phase4Clock
    val props=points.map {p->MotionEvent.PointerProperties().apply {id=p.id;toolType=p.tool}}.toTypedArray()
    val coords=points.map {p->MotionEvent.PointerCoords().apply {x=p.x;y=p.y;pressure=0.5f;size=0.1f}}.toTypedArray()
    val motion=MotionEvent.obtain(phase4Down,phase4Clock,action,points.size,props,coords,0,0,1f,1f,0,0,InputDevice.SOURCE_STYLUS,flags)
    try {view.dispatchTouchEvent(motion)}finally {motion.recycle()}
}

@RunWith(AndroidJUnit4::class)
class Phase4CanvasTest {
    @Test fun canvasRoutingPreservesPenLassoWithCanceledPalmAndCommitsPointerUp() {
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        try {
            lateinit var canvas:InkCanvasView
            val objects=listOf(record(100f,100f,200f,150f,pageId="phase4").toEntity(),phase4Text(),phase4Image())
            var selected=emptySet<String>();var pending=0
            scenario.onActivity {activity->
                canvas=InkCanvasView(activity)
                canvas.setPageId("phase4");canvas.onPendingInkChanged={pending=it}
                canvas.setStoredStrokes(objects.mapNotNull {it.toInkStrokeRecord()})
                canvas.setContentObjects(objects,emptySet(),true,{p->selected=objects.filter {contentIntersectsLasso(it,p)}.map {it.id}.toSet()},
                    {_,_,_->},{},{},{})
                activity.setContentView(canvas)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                phase4Event(canvas,MotionEvent.ACTION_DOWN,Phase4Pointer(0f,0f))
                phase4Event(canvas,MotionEvent.ACTION_MOVE,Phase4Pointer(650f,0f))
                val palm=Phase4Pointer(800f,500f,1,MotionEvent.TOOL_TYPE_FINGER)
                phase4Event(canvas,MotionEvent.ACTION_POINTER_DOWN or (1 shl 8),Phase4Pointer(650f,0f),palm)
                phase4Event(canvas,MotionEvent.ACTION_POINTER_UP or (1 shl 8),Phase4Pointer(650f,0f),palm,flags=MotionEvent.FLAG_CANCELED)
                assertEquals(1,pending)
                phase4Event(canvas,MotionEvent.ACTION_MOVE,Phase4Pointer(650f,300f))
                phase4Event(canvas,MotionEvent.ACTION_MOVE,Phase4Pointer(0f,300f))
                phase4Event(canvas,MotionEvent.ACTION_POINTER_DOWN or (1 shl 8),Phase4Pointer(0f,300f),palm)
                phase4Event(canvas,MotionEvent.ACTION_POINTER_UP,Phase4Pointer(0f,0f),palm)
                assertEquals(objects.map {it.id}.toSet(),selected);assertEquals(0,pending)
            }
        }finally {scenario.close()}
    }
}
