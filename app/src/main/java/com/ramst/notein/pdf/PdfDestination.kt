package com.ramst.notein.pdf

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/** CreateDocument destinations require the DocumentsProvider deletion protocol. */
internal fun discardPdfDestination(context: Context, destination: Uri) {
    when {
        DocumentsContract.isDocumentUri(context, destination) -> {
            check(DocumentsContract.deleteDocument(context.contentResolver, destination)) { "Could not remove the incomplete PDF." }
        }
        destination.scheme == "file" -> { destination.path?.let { File(it).delete() } }
        else -> context.contentResolver.delete(destination, null, null)
    }
}
