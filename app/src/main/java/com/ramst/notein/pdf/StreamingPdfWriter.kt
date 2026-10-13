package com.ramst.notein.pdf

import android.graphics.Bitmap
import java.io.OutputStream

/** Writes each JPEG immediately. Only cross-reference offsets survive between pages. */
internal class StreamingPdfWriter(private val output: OutputStream, private val pageCount: Int) {
    private var position = 0L
    private val offsets = LongArray(3 + pageCount * 4)
    private var written = 0
    private val counted = object : OutputStream() {
        override fun write(value: Int) { output.write(value); position++ }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            output.write(bytes, offset, length); position += length
        }
    }
    private fun text(value: String) = counted.write(value.toByteArray(Charsets.US_ASCII))
    private fun start(id: Int) { offsets[id] = position; text("$id 0 obj\n") }
    init {
        text("%PDF-1.4\n")
        start(1); text("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        start(2); text("<< /Type /Pages /Count $pageCount /Kids [")
        repeat(pageCount) { text("${3 + it * 4} 0 R ") }
        text("] >>\nendobj\n")
    }
    fun page(bitmap: Bitmap, width: Float, height: Float) {
        check(written < pageCount)
        val id = 3 + written * 4
        start(id)
        text("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $width $height] /Resources << /XObject << /Image ${id + 1} 0 R >> >> /Contents ${id + 3} 0 R >>\nendobj\n")
        start(id + 1)
        text("<< /Type /XObject /Subtype /Image /Width ${bitmap.width} /Height ${bitmap.height} /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${id + 2} 0 R >>\nstream\n")
        val beginning = position
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, counted)) { "Could not encode the exported page." }
        val length = position - beginning
        text("\nendstream\nendobj\n")
        start(id + 2); text("$length\nendobj\n")
        val commands = "q\n$width 0 0 $height 0 0 cm\n/Image Do\nQ\n"
        start(id + 3); text("<< /Length ${commands.toByteArray(Charsets.US_ASCII).size} >>\nstream\n${commands}endstream\nendobj\n")
        written++
    }
    fun finish() {
        check(written == pageCount)
        val xref = position
        text("xref\n0 ${offsets.size}\n0000000000 65535 f \n")
        for (id in 1 until offsets.size) text("${offsets[id].toString().padStart(10, '0')} 00000 n \n")
        text("trailer\n<< /Size ${offsets.size} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
    }
}
