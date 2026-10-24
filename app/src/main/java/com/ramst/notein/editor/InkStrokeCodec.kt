@file:OptIn(androidx.ink.brush.ExperimentalInkCustomBrushApi::class)

package com.ramst.notein.editor

import android.graphics.Color
import android.annotation.SuppressLint
import android.util.Base64
import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.BrushTip
import androidx.ink.brush.BrushBehavior
import androidx.ink.storage.StrokeInputBatchSerialization
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.StrokeInputBatch
import com.ramst.notein.storage.EditableObjectEntity
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream

internal const val INK_STROKE_TYPE = "ink_stroke_v1"

internal data class InkStrokeRecord(
    val id: String,
    val pageId: String,
    val payload: String,
    val createdAt: Long,
    val updatedAt: Long,
) {
    fun toEntity() = EditableObjectEntity(id, pageId, INK_STROKE_TYPE, payload, createdAt, updatedAt)

    fun decode(): DecodedInkStroke? = runCatching {
        val json = JSONObject(payload)
        val tool = InkTool.fromStorage(json.getString("tool"))
        val color = json.getInt("color")
        val transform = ObjectTransform.read(json)
        val size = (json.getDouble("size") * transform.scale).toFloat()
        val inputBytes = Base64.decode(json.getString("inputs"), Base64.NO_WRAP)
        val originalInputs = StrokeInputBatchSerialization.decode(ByteArrayInputStream(inputBytes))
        val transformedInputs = MutableStrokeInputBatch()
        repeat(originalInputs.size) { index ->
            val input = originalInputs[index]
            transformedInputs.add(input.toolType,
                (input.x * transform.scale + transform.x).toFloat(),
                (input.y * transform.scale + transform.y).toFloat(),
                input.elapsedTimeMillis, pressure = input.pressure)
        }
        val stroke = Stroke(
            brush = brushFor(tool, color, size, brushVersion = json.optInt("brushVersion", 1)),
            inputs = transformedInputs,
        )
        DecodedInkStroke(
            record = this,
            stroke = stroke,
            bounds = transform.bounds(InkBounds(
                left = json.getDouble("left").toFloat(),
                top = json.getDouble("top").toFloat(),
                right = json.getDouble("right").toFloat(),
                bottom = json.getDouble("bottom").toFloat(),
            )),
        )
    }.getOrNull()
}

internal data class DecodedInkStroke(
    val record: InkStrokeRecord,
    val stroke: Stroke,
    val bounds: InkBounds,
)

internal data class InkBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun contains(x: Float, y: Float, padding: Float): Boolean =
        x >= left - padding && x <= right + padding && y >= top - padding && y <= bottom + padding
}

internal enum class InkTool(val storageValue: String, val label: String, val defaultSize: Float, val range: ClosedFloatingPointRange<Float>) {
    Pencil("pencil", "Pencil", .5f, .25f..4f),
    BallPen("ball_pen", "Ball pen", 2f, .5f..8f),
    Pen("ink_pen", "Ink pen", 6f, 1f..24f),
    Highlighter("highlighter", "Highlighter", 6f, 2f..18f),
    Eraser("eraser", "Eraser", 6f, 2f..18f);

    companion object {
        fun fromStorage(value: String): InkTool = if (value == "pen") Pen else entries.firstOrNull { it.storageValue == value } ?: Pen
    }
}

// Compatibility decoding only. Version 2 used custom APIs restricted in Ink 1.0.0.
// Keep that appearance for any saved version-2 strokes; all new authoring uses public stock APIs.
@SuppressLint("RestrictedApi")
private fun pressureFamily(minimum: Float, maximum: Float) = BrushFamily(tip = BrushTip(behaviors = listOf(
    BrushBehavior(source = BrushBehavior.Source.NORMALIZED_PRESSURE,
        target = BrushBehavior.Target.SIZE_MULTIPLIER,
        sourceValueRangeStart = 0f, sourceValueRangeEnd = 1f,
        targetModifierRangeStart = minimum, targetModifierRangeEnd = maximum,
        enabledToolTypes = setOf(androidx.ink.brush.InputToolType.STYLUS)),
)))
private val pencilFamily by lazy { pressureFamily(.85f, 1.15f) }
private val inkFamily by lazy { pressureFamily(.3f, 1.7f) }

internal fun brushFor(tool: InkTool, color: Int, size: Float, legacy: Boolean = false, brushVersion: Int = if (legacy) 1 else 3): Brush {
    val family = when (tool) {
        InkTool.Highlighter -> StockBrushes.highlighter()
        InkTool.BallPen -> StockBrushes.marker()
        InkTool.Pencil -> if (brushVersion == 2) pencilFamily else StockBrushes.marker()
        else -> if (brushVersion == 2) inkFamily else StockBrushes.pressurePen()
    }
    val displayColor = if (tool == InkTool.Highlighter) {
        Color.argb(105, Color.red(color), Color.green(color), Color.blue(color))
    } else if (tool == InkTool.Pencil) {
        Color.argb(190, Color.red(color), Color.green(color), Color.blue(color))
    } else {
        color
    }
    return Brush.createWithColorIntArgb(
        family = family,
        colorIntArgb = displayColor,
        size = size.coerceAtLeast(0.000001f),
        epsilon = minOf(0.1f, size * 0.1f).coerceAtLeast(0.0000001f),
    )
}

internal fun strokeRecordFrom(
    id: String,
    pageId: String,
    stroke: Stroke,
    tool: InkTool,
    color: Int,
    size: Float,
    createdAt: Long = System.currentTimeMillis(),
): InkStrokeRecord {
    return strokeRecordFromInputs(id, pageId, stroke.inputs, tool, color, size, createdAt)
}

/** Persist samples on pointer-up without constructing a second native stroke mesh. */
internal fun strokeRecordFromInputs(
    id: String,
    pageId: String,
    inputs: StrokeInputBatch,
    tool: InkTool,
    color: Int,
    size: Float,
    createdAt: Long,
): InkStrokeRecord {
    var left = Float.POSITIVE_INFINITY
    var top = Float.POSITIVE_INFINITY
    var right = Float.NEGATIVE_INFINITY
    var bottom = Float.NEGATIVE_INFINITY
    repeat(inputs.size) { index ->
        val input = inputs[index]
        left = minOf(left, input.x)
        top = minOf(top, input.y)
        right = maxOf(right, input.x)
        bottom = maxOf(bottom, input.y)
    }
    val output = ByteArrayOutputStream()
    StrokeInputBatchSerialization.encode(inputs, output)
    val payload = JSONObject()
        .put("brushVersion", 3)
        .put("tool", tool.storageValue)
        .put("color", color)
        .put("size", size)
        .put("left", left)
        .put("top", top)
        .put("right", right)
        .put("bottom", bottom)
        .put("inputs", Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP))
        .toString()
    return InkStrokeRecord(id, pageId, payload, createdAt, createdAt)
}

internal fun EditableObjectEntity.toInkStrokeRecord(): InkStrokeRecord? =
    if (type == INK_STROKE_TYPE) InkStrokeRecord(id, pageId, payload, createdAt, updatedAt) else null
