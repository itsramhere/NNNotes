package com.ramst.notein.editor

import android.util.Log
import com.ramst.notein.BuildConfig

/** Debug metadata only: never serialized handwriting or notebook titles. */
internal object InkTrace {
    fun log(message: String) {
        if (BuildConfig.DEBUG) Log.d("NoteinInk", message)
    }
}
