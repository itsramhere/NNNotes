package com.ramst.notein.editor

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class Phase4ImageTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val root=File(context.cacheDir,"phase4-assets-${UUID.randomUUID()}")
    private lateinit var assets: ImageAssets
    private lateinit var scoped: Context
    @Before fun setup() {
        root.mkdirs()
        scoped=object:ContextWrapper(context) {
            override fun getApplicationContext():Context=this
            override fun getFilesDir():File=root
        }
        assets=ImageAssets(scoped)
    }
    @After fun cleanup() {root.deleteRecursively()} // This unique fixture directory only.
    private fun image(width:Int,height:Int):File {
        val file=File(root,"source.png")
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLUE)
        try {file.outputStream().use {assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}} finally {bitmap.recycle()}
        return file
    }
    @Test fun largeImagePreservesOriginalBytesAndBoundsPreviewMemory()=runBlocking {
        val source=image(5000,3000)
        val imported=assets.import(Uri.fromFile(source))
        assertEquals(5000,imported.width);assertEquals(3000,imported.height)
        assertArrayEquals(source.readBytes(),assets.original(imported.assetId).readBytes())
        val preview=BitmapFactory.decodeFile(assets.preview(imported.assetId).absolutePath)
        try {assertTrue(preview.width<=2048 && preview.height<=2048);assertTrue(preview.allocationByteCount<=16*1024*1024)}
        finally {preview.recycle()}
        assertFalse(File(root,"image-assets").listFiles()!!.any {it.extension=="importing"})
    }
    @Test fun unsupportedFileAndUnreadableSourceRemoveOnlyUncommittedAssetFiles()=runBlocking {
        val source=File(root,"bad.bin").apply {writeText("unsupported file")}
        for (uri in listOf(Uri.fromFile(source),Uri.fromFile(File(root,"missing.png")))) {
            try {assets.import(uri);fail("Expected import failure")} catch (_:Exception) {}
            assertTrue(File(root,"image-assets").listFiles().orEmpty().isEmpty())
            assertEquals("unsupported file",source.readText())
        }
    }
    @Test fun failedAssetDirectoryCreationDoesNotDeleteExistingData()=runBlocking {
        val blocker=File(root,"image-assets").apply {writeText("retained unrelated file")}
        val source=image(80,60)
        try {assets.import(Uri.fromFile(source));fail("Expected storage failure")} catch (_:IllegalStateException) {}
        assertEquals("retained unrelated file",blocker.readText());assertTrue(source.isFile)
    }
    @Test fun interruptedSourceCopyClosesStreamAndRetainsPreviouslyImportedAssets()=runBlocking {
        val kept=assets.import(Uri.fromFile(image(80,60)))
        val existing=File(root,"image-assets").listFiles()!!.map {it.name}.toSet()
        var closed=false
        val stream=object:java.io.InputStream() {
            var reads=0
            override fun read():Int {if(reads++>32)throw java.io.IOException("injected USB read failure");return 0}
            override fun close(){closed=true}
        }
        val failing=ImageAssets(scoped,openSource={stream})
        try {failing.import(Uri.parse("content://fixture/failing.png"));fail("Expected partial-copy failure")}
        catch (_:java.io.IOException) {}
        assertTrue(closed)
        assertEquals(existing,File(root,"image-assets").listFiles()!!.map {it.name}.toSet())
        assertTrue(assets.original(kept.assetId).isFile);assertTrue(assets.preview(kept.assetId).isFile)
    }
}
