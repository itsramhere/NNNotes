package com.ramst.notein.editor

import org.json.JSONArray
import org.json.JSONObject

internal const val SHAPE_OBJECT_TYPE = "shape_v1"
internal const val GRAPH_OBJECT_TYPE = "coordinate_graph_v1"
internal const val HEADING_OBJECT_TYPE = "heading_v1"
internal const val TABLE_OBJECT_TYPE = "table_v1"
internal val STRUCTURED_CONTENT_TYPES = setOf(SHAPE_OBJECT_TYPE, GRAPH_OBJECT_TYPE, HEADING_OBJECT_TYPE, TABLE_OBJECT_TYPE)
internal val DIALOG_CONTENT_TYPES = STRUCTURED_CONTENT_TYPES + TEXT_OBJECT_TYPE

internal enum class ShapeKind(val label: String) {
    SQUARE("Square"), RECTANGLE("Rectangle"), TRIANGLE("Triangle"), RIGHT_TRIANGLE("Right triangle"),
    CIRCLE("Circle"), ELLIPSE("Ellipse"), DIAMOND("Diamond"), LINE("Line"), ARROW("Arrow");
}

internal data class ShapeSpec(val kind: ShapeKind = ShapeKind.RECTANGLE, val width: Float = 200f,
    val height: Float = 140f, val color: Int = 0xFF28594C.toInt(), val filled: Boolean = false, val strokeWidth: Float = 2f) {
    fun json(): JSONObject {
        require(width.isFinite() && height.isFinite() && width in 24f..2400f && height in 24f..2400f)
        require(strokeWidth.isFinite() && strokeWidth in .5f..16f)
        require(kind !in setOf(ShapeKind.SQUARE, ShapeKind.CIRCLE) || width == height)
        return JSONObject().put("shape", kind.name).put("width", width).put("height", height)
            .put("color", color).put("filled", filled).put("strokeWidth", strokeWidth)
    }
    companion object {
        fun read(json: JSONObject) = ShapeSpec(ShapeKind.valueOf(json.getString("shape")), json.getDouble("width").toFloat(),
            json.getDouble("height").toFloat(), json.getInt("color"), json.getBoolean("filled"),
            json.getDouble("strokeWidth").toFloat()).also { it.json() }
    }
}

internal data class GraphSpec(val xMin: Float = -5f, val xMax: Float = 5f, val yMin: Float = -5f,
    val yMax: Float = 5f, val xStep: Float = 1f, val yStep: Float = 1f, val grid: Boolean = true,
    val labels: Boolean = true, val width: Float = 320f, val height: Float = 300f) {
    fun json(): JSONObject {
        require(listOf(xMin, xMax, yMin, yMax, xStep, yStep, width, height).all { it.isFinite() })
        require(listOf(xMin, xMax, yMin, yMax).all { kotlin.math.abs(it) <= 1_000_000f })
        require(xMin < xMax && yMin < yMax && xMin <= 0f && xMax >= 0f && yMin <= 0f && yMax >= 0f) {
            "Each axis range must increase and include zero."
        }
        require(xStep > 0f && yStep > 0f && (xMax - xMin) / xStep in 1f..100f && (yMax - yMin) / yStep in 1f..100f) {
            "Use between 1 and 100 grid intervals per axis."
        }
        require(width in 120f..2400f && height in 120f..2400f)
        return JSONObject().put("width", width).put("height", height).put("xMin", xMin).put("xMax", xMax)
            .put("yMin", yMin).put("yMax", yMax).put("xStep", xStep).put("yStep", yStep).put("grid", grid).put("labels", labels)
    }
    companion object {
        fun read(json: JSONObject) = GraphSpec(json.getDouble("xMin").toFloat(), json.getDouble("xMax").toFloat(),
            json.getDouble("yMin").toFloat(), json.getDouble("yMax").toFloat(), json.getDouble("xStep").toFloat(),
            json.getDouble("yStep").toFloat(), json.getBoolean("grid"), json.getBoolean("labels"),
            json.getDouble("width").toFloat(), json.getDouble("height").toFloat()).also { it.json() }
    }
}

internal fun headingSize(level: Int): Float = listOf(36f, 30f, 26f, 22f, 19f, 16f)[level.coerceIn(1, 6) - 1]

internal data class TableSpec(val rows: List<Float> = List(3) { 40f }, val columns: List<Float> = List(3) { 100f },
    val cells: List<List<String>> = List(3) { List(3) { "" } }, val header: Boolean = true) {
    val width get() = columns.sum()
    val height get() = rows.sum()
    fun withSize(rowCount: Int, columnCount: Int): TableSpec {
        require(rowCount in 1..50 && columnCount in 1..30)
        return copy(rows = List(rowCount) { rows.getOrElse(it) { 40f } },
            columns = List(columnCount) { columns.getOrElse(it) { 100f } },
            cells = List(rowCount) { r -> List(columnCount) { c -> cells.getOrNull(r)?.getOrNull(c).orEmpty() } })
    }
    fun json(): JSONObject {
        require(rows.size in 1..50 && columns.size in 1..30)
        require(rows.all { it.isFinite() && it in 24f..600f } && columns.all { it.isFinite() && it in 32f..1000f })
        require(cells.size == rows.size && cells.all { row -> row.size == columns.size && row.all { it.length <= 2000 } })
        return JSONObject().put("width", width).put("height", height).put("rows", JSONArray(rows))
            .put("columns", JSONArray(columns)).put("cells", JSONArray(cells.map { JSONArray(it) })).put("header", header)
    }
    fun cell(row: Int, column: Int, text: String) = copy(cells = cells.mapIndexed { r, values ->
        if (r == row) values.mapIndexed { c, value -> if (c == column) text.take(2000) else value } else values
    })
    fun resizeRow(index: Int, height: Float) = copy(rows = rows.mapIndexed { i, old -> if (i == index) height else old })
    fun resizeColumn(index: Int, width: Float) = copy(columns = columns.mapIndexed { i, old -> if (i == index) width else old })
    fun addRow() = copy(rows = rows + 40f, cells = cells + listOf(List(columns.size) { "" }))
    fun addColumn() = copy(columns = columns + 100f, cells = cells.map { it + "" })
    fun deleteRow(index: Int) = copy(rows = rows.filterIndexed { i, _ -> i != index }, cells = cells.filterIndexed { i, _ -> i != index })
    fun deleteColumn(index: Int) = copy(columns = columns.filterIndexed { i, _ -> i != index },
        cells = cells.map { it.filterIndexed { i, _ -> i != index } })
    companion object {
        fun read(json: JSONObject): TableSpec {
            val rows = json.getJSONArray("rows")
            val columns = json.getJSONArray("columns")
            val cells = json.getJSONArray("cells")
            require(rows.length() in 1..50 && columns.length() in 1..30 && cells.length() == rows.length())
            val result = TableSpec(List(rows.length()) { rows.getDouble(it).toFloat() },
                List(columns.length()) { columns.getDouble(it).toFloat() }, List(cells.length()) { r ->
                    val row = cells.getJSONArray(r)
                    require(row.length() == columns.length())
                    List(row.length()) { row.getString(it) }
                }, json.getBoolean("header"))
            result.json()
            require(kotlin.math.abs(json.getDouble("width") - result.width) < .1 &&
                kotlin.math.abs(json.getDouble("height") - result.height) < .1)
            return result
        }
    }
}
