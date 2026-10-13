package com.ramst.notein.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ramst.notein.storage.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class Phase4ProcessTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val prefs get()=context.getSharedPreferences("phase4-process",Context.MODE_PRIVATE)
    private fun sha(file:File)=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") {"%02x".format(it)}
    @Test fun prepare()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("class")?.endsWith("#prepare")==true)
        assertFalse(prefs.contains("STANDARD"))
        val db=NoteinDatabase.create(context)
        val source=File(context.cacheDir,"phase4-process-${UUID.randomUUID()}.png")
        try {
            val bitmap=Bitmap.createBitmap(128,80,Bitmap.Config.ARGB_8888).apply {eraseColor(android.graphics.Color.GREEN)}
            try {source.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}finally {bitmap.recycle()}
            val imported=ImageAssets(context).import(Uri.fromFile(source))
            assertTrue(prefs.edit().putString("asset",imported.assetId).putString("sha",sha(source)).commit())
            val notebooks=NotebookRepository(db.notebookDao(),db.folderDao());val repo=EditorRepository(db.editorDao())
            for(type in listOf(NotebookType.STANDARD, NotebookType.INFINITE)) {
                val id=notebooks.createNotebook("Phase4 restart ${UUID.randomUUID()}",null,type)
                val pageId=if(type==NotebookType.STANDARD)repo.createPage(id,type)else repo.firstPage(id)!!.id
                val page=db.editorDao().page(pageId)!!
                val objects=listOf(record(pageId=pageId).toEntity(),phase4Text(pageId,text="Persisted\ntext Ω",time=System.currentTimeMillis()+1),
                    phase4Image(pageId,imported.assetId,time=System.currentTimeMillis()+2))
                    .map {transformContent(it,0f,0f,-180f,240f,1.75f)}
                repo.saveContentSnapshot(objects,page.copy(viewportX=-200f,viewportY=100f,viewportScale=1.5f))
                repo.selectPage(id,pageId)
                val payloads=JSONObject();objects.forEach {payloads.put(it.id,it.payload)}
                assertTrue(prefs.edit().putString(type.name,id).putString("${type.name}-page",pageId).putString("${type.name}-objects",payloads.toString()).commit())
            }
            println("PHASE4_PROCESS_PREPARED mixed content and shared asset in both notebook types")
        }finally {source.delete();db.close()}
    }
    @Test fun verify()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("class")?.endsWith("#verify")==true)
        val db=NoteinDatabase.create(context)
        try {
            val notebooks=NotebookRepository(db.notebookDao(),db.folderDao());val repo=EditorRepository(db.editorDao())
            val asset=prefs.getString("asset",null)?:error("Prepare first")
            val assets=ImageAssets(context)
            assertEquals(prefs.getString("sha",null),sha(assets.original(asset)))
            val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
            BitmapFactory.decodeFile(assets.preview(asset).absolutePath,bounds)
            assertEquals(128,bounds.outWidth);assertEquals(80,bounds.outHeight)
            for(type in listOf(NotebookType.STANDARD, NotebookType.INFINITE)) {
                val id=prefs.getString(type.name,null)!!
                val notebook=repo.notebook(id)!!;val pageId=prefs.getString("${type.name}-page",null)!!
                assertEquals(pageId,notebook.selectedPageId);assertEquals(type.storageValue,notebook.notebookType)
                val page=db.editorDao().page(pageId)!!
                assertEquals(-200f,page.viewportX,0f);assertEquals(100f,page.viewportY,0f);assertEquals(1.5f,page.viewportScale,0f)
                val expected=JSONObject(prefs.getString("${type.name}-objects",null)!!)
                val actual=db.editorDao().pageObjects(pageId)
                assertEquals(3,actual.size)
                actual.forEach {assertEquals(expected.getString(it.id),it.payload);assertEquals(1.75,it.toContentObject()!!.transform.scale,0.0)}
                assertEquals("Persisted\ntext Ω",actual.single {it.type==TEXT_OBJECT_TYPE}.toContentObject()!!.text)
                assertEquals(asset,actual.single {it.type==IMAGE_OBJECT_TYPE}.toContentObject()!!.assetId)
                assertNotNull(actual.single {it.type==INK_STROKE_TYPE}.toInkStrokeRecord()!!.decode())
                notebooks.trash(id);notebooks.permanentlyDelete(id)
            }
            assets.original(asset).delete();assets.preview(asset).delete();assertTrue(prefs.edit().clear().commit())
            println("PHASE4_PROCESS_VERIFIED exact payloads, transforms, text, IDs, original image bytes and preview")
        }finally {db.close()}
    }
}
