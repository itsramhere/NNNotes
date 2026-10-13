package com.ramst.notein.editor

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.os.Bundle
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
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

/** Real activity/navigation; native checks avoid Compose's detached-root frame wait on rotation. */
@RunWith(AndroidJUnit4::class)
class Phase9RotationTest {
    private val context=ApplicationProvider.getApplicationContext<NoteinApplication>()
    private val container get()=context.container
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private var id:String?=null
    private var scenario:ActivityScenario<MainActivity>?=null
    private fun shell(command:String):String=instrumentation.uiAutomation.executeShellCommand(command).use {
        ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use {reader->reader.readText()}
    }
    private fun nodes(text:String):List<AccessibilityNodeInfo> {
        val result=mutableListOf<AccessibilityNodeInfo>()
        fun visit(node:AccessibilityNodeInfo?,depth:Int) {
            if(node==null || depth>15) return
            if(node.text?.toString()?.contains(text)==true || node.contentDescription?.toString()?.contains(text)==true) result+=node
            repeat(node.childCount) {visit(node.getChild(it),depth+1)}
        }
        visit(instrumentation.uiAutomation.rootInActiveWindow,0)
        return result
    }
    private fun editable(node:AccessibilityNodeInfo?):AccessibilityNodeInfo? {
        if(node==null) return null
        if(node.isEditable && node.packageName=="com.ramst.notein") return node
        repeat(node.childCount) {editable(node.getChild(it))?.let {child->return child}}
        return null
    }
    private fun titleNode(title:String)=nodes(title).mapNotNull {node->
        var current:AccessibilityNodeInfo?=node
        while(current!=null && !current.isClickable) current=current.parent
        current?.takeIf {!it.isEditable}
    }.firstOrNull()
    private fun await(check:()->Boolean) {
        val until=SystemClock.uptimeMillis()+20000
        while(SystemClock.uptimeMillis()<until) {if(runCatching(check).getOrDefault(false)) return;SystemClock.sleep(50)}
        val trace=StringBuilder()
        fun dump(node:AccessibilityNodeInfo?,depth:Int) {
            if(node==null || depth>15) return
            trace.append(" ".repeat(depth)).append(node.className).append(" editable=").append(node.isEditable)
                .append(" clickable=").append(node.isClickable).append(" text=").append(node.text)
                .append(" description=").append(node.contentDescription).append('\n')
            repeat(node.childCount) {dump(node.getChild(it),depth+1)}
        }
        dump(instrumentation.uiAutomation.rootInActiveWindow,0)
        File(context.filesDir,"phase9-evidence/rotation-native-tree.txt").apply {parentFile!!.mkdirs();writeText(trace.toString())}
        screenshot("rotation-native-timeout.png")
        assertTrue("Timed out waiting for native rotation/activity state",check())
    }
    private fun screenshot(name:String):Int {
        val image=checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val file=File(context.filesDir,"phase9-evidence/$name").apply {parentFile!!.mkdirs()}
            file.outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)}
            return image.width
        } finally {image.recycle()}
    }
    @After fun cleanup() {
        try {runBlocking {id?.let {container.notebookRepository.trash(it);container.notebookRepository.permanentlyDelete(it)}}}
        finally {shell("wm user-rotation free");scenario?.close()}
    }
    @Test fun liveRotationPreservesPaperInkFocusAndAutomaticPageCount() {
        shell("wm user-rotation free")
        scenario=ActivityScenario.launch(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val title="Phase9 rotate ${UUID.randomUUID()}"
        val template=PaperTemplate("dots",792f,612f,3.5f,"bold",0xFF123ABC.toInt())
        val fixture=runBlocking {container.notebookRepository.createNotebook(title,null,template=template)}
        id=fixture
        val page=runBlocking {container.editorRepository.firstPage(fixture)!!}
        val ink=record(pageId=page.id).toEntity()
        runBlocking {
            container.editorRepository.selectPage(fixture,page.id)
            container.editorRepository.saveContentSnapshot(listOf(ink),page.copy(viewportX=-20f,viewportY=10f,viewportScale=.75f))
        }
        await {editable(instrumentation.uiAutomation.rootInActiveWindow)!=null}
        val search=checkNotNull(editable(instrumentation.uiAutomation.rootInActiveWindow))
        assertTrue(search.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,title)
        }))
        await {titleNode(title)!=null}
        var target:AccessibilityNodeInfo?=titleNode(title)
        while(target!=null && !target.isClickable) target=target.parent
        assertTrue("The fixture notebook must open",checkNotNull(target).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        await {nodes("Finger writing").isNotEmpty()}
        val beforeWidth=context.resources.configuration.screenWidthDp
        val bitmapWidth=screenshot("rotation-before.png")
        shell("wm user-rotation lock 1")
        await {context.resources.configuration.screenWidthDp!=beforeWidth && nodes("Finger writing").isNotEmpty()}
        assertNotEquals(bitmapWidth,screenshot("rotation-after.png"))
        runBlocking {
            val pages=container.editorRepository.pages(fixture)
            assertEquals(2,pages.size);assertEquals(template,pages.first().paperTemplate());assertEquals(template,pages.last().paperTemplate())
            assertEquals(page.id,container.editorRepository.notebook(fixture)!!.selectedPageId)
            assertEquals(ink.payload,container.editorRepository.pageObjects(page.id).single().payload)
        }
    }
}
