package com.ramst.notein.editor

/** A small bridge that keeps toolbar actions independent of the Android View host. */
internal class InkEditorController {
    private var view: InkCanvasView? = null

    internal fun attach(inkCanvasView: InkCanvasView) {
        view = inkCanvasView
    }

    internal fun detach(inkCanvasView: InkCanvasView) {
        if (view === inkCanvasView) view = null
    }

    fun remove(recordId: String) {
        view?.removeStroke(recordId)
    }

    fun restore(record: InkStrokeRecord) {
        view?.restoreStroke(record)
    }

    fun fitPage() {
        view?.fitPage()
    }

    fun fitContent() {
        view?.fitContent()
    }

    fun centerOn(x: Float, y: Float) { view?.centerOn(x, y) }
    fun viewportCenter(): android.graphics.PointF = view?.viewportCenter() ?: android.graphics.PointF(0f, 0f)
    fun commitViewportBeforeLock() { view?.commitViewportBeforeLock() }
}
