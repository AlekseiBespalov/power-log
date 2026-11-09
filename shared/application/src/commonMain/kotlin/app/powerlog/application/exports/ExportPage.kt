package app.powerlog.application.exports

import kotlinx.serialization.Serializable

@Serializable
public data class ExportPage(
    public val rows: Int,
    public val next: List<Double>?,
    public val done: Boolean,
    public val connections: List<ExportConnection>,
    public val columns: Map<String, ExportColumn>,
)

@Serializable
public sealed interface ExportColumn {
    // NaN: absent
    @Serializable public data class Numbers(public val values: DoubleArray) : ExportColumn

    @Serializable public data class Texts(public val values: List<String?>) : ExportColumn
}

// An upper bound on the page's size on the wire: 8 bytes a number, a text as escaped JSON.
internal fun ExportPage.encodedBytes(): Long =
    columns.values.sumOf { column ->
        when (column) {
            is ExportColumn.Numbers -> 8L * column.values.size
            is ExportColumn.Texts -> column.values.sumOf { text -> if (text == null) 5L else jsonBytes(text) + 3L }
        }
    }

private fun jsonBytes(text: String): Long = text.sumOf { char ->
    when {
        char == '"' || char == '\\' -> 2L
        char < ' ' -> 6L
        char.code < 0x80 -> 1L
        char.code < 0x800 -> 2L
        else -> 3L
    }
}
