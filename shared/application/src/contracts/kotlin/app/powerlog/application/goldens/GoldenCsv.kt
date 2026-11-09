package app.powerlog.application.goldens

internal class CsvTable(val name: String, text: String) {
    private val records = records(text).iterator()

    val header: List<String> = if (records.hasNext()) records.next() else refuse("$name has no header")

    private val columns = header.withIndex().associate { (index, column) -> column to index }

    init {
        if (columns.size != header.size) refuse("$name repeats a column")
    }

    fun requireColumns(expected: Set<String>) {
        val unknown = header.toSet() - expected
        val missing = expected - header.toSet()
        if (unknown.isNotEmpty()) refuse("$name has columns no stored column holds: ${unknown.sorted()}")
        if (missing.isNotEmpty()) refuse("$name lacks columns: ${missing.sorted()}")
    }

    fun rows(): Sequence<CsvRow> = sequence {
        var line = 1
        for (cells in records) {
            line++
            if (cells.size != header.size) refuse("$name line $line has ${cells.size} cells for ${header.size} columns")
            yield(CsvRow(this@CsvTable, line, cells))
        }
    }

    fun index(column: String): Int = columns[column] ?: refuse("$name has no column $column")

    private fun records(text: String): Sequence<List<String>> = sequence {
        var at = 0
        while (at < text.length) {
            val cells = mutableListOf<String>()
            while (true) {
                if (at < text.length && text[at] == '"') {
                    val cell = StringBuilder()
                    at++
                    while (true) {
                        if (at >= text.length) refuse("$name ends inside a quoted cell")
                        val char = text[at++]
                        when {
                            char != '"' -> cell.append(char)
                            at < text.length && text[at] == '"' -> cell.append(text[at++])
                            else -> break
                        }
                    }
                    if (at < text.length && text[at] != ',' && text[at] != '\n') refuse("$name has text after a quote")
                    cells += cell.toString()
                } else {
                    val start = at
                    while (at < text.length && text[at] != ',' && text[at] != '\n') at++
                    cells += text.substring(start, at)
                }
                if (at >= text.length) break
                if (text[at++] == '\n') break
            }
            yield(cells)
        }
    }
}

internal class CsvRow(private val table: CsvTable, val line: Int, private val cells: List<String>) {
    fun where(column: String): String = "${table.name} line $line, $column"

    fun textOrNull(column: String): String? = cells[table.index(column)].ifEmpty { null }

    fun text(column: String): String = textOrNull(column) ?: refuse("${where(column)} is empty")

    fun longOrNull(column: String): Long? =
        textOrNull(column)?.let { it.toLongOrNull() ?: refuse("${where(column)} is not an integer") }

    fun intOrNull(column: String): Int? =
        longOrNull(column)?.let {
            if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else refuse("${where(column)} is too large")
        }

    fun int(column: String): Int = intOrNull(column) ?: refuse("${where(column)} is empty")

    fun doubleOrNull(column: String): Double? =
        textOrNull(column)?.let { it.toDoubleOrNull() ?: refuse("${where(column)} is not a number") }

    fun double(column: String): Double = doubleOrNull(column) ?: refuse("${where(column)} is empty")
}
