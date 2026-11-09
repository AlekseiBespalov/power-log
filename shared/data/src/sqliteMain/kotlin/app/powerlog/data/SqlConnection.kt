package app.powerlog.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement

internal class SqlConnection(val connection: SQLiteConnection) {
    private val statements = HashMap<String, SQLiteStatement>()

    fun <T> statement(sql: String, block: (SQLiteStatement) -> T): T {
        val statement = statements.getOrPut(sql) { connection.prepare(sql) }
        val result =
            try {
                block(statement)
            } catch (error: Throwable) {
                runCatching { statement.reset() }
                runCatching { statement.clearBindings() }
                throw error
            }
        statement.reset()
        statement.clearBindings()
        return result
    }

    // One step: androidx's journal_mode statement on Android fails when stepped past its one row.
    fun execute(sql: String) {
        statement(sql) { it.step() }
    }

    fun long(sql: String): Long =
        statement(sql) {
            check(it.step()) { "$sql answered no row" }
            it.getLong(0)
        }

    fun text(sql: String): String =
        statement(sql) {
            check(it.step()) { "$sql answered no row" }
            it.getText(0)
        }

    fun <T> transaction(keep: (T) -> Boolean = { true }, block: () -> T): T {
        execute("BEGIN IMMEDIATE")
        try {
            val result = block()
            execute(if (keep(result)) "COMMIT" else "ROLLBACK")
            return result
        } catch (error: Throwable) {
            if (connection.inTransaction()) runCatching { execute("ROLLBACK") }
            throw error
        }
    }

    fun finish() {
        for (statement in statements.values) runCatching { statement.close() }
        statements.clear()
    }
}

internal fun SQLiteStatement.bindNullable(index: Int, value: Long?) {
    if (value == null) bindNull(index) else bindLong(index, value)
}

internal fun SQLiteStatement.bindNullable(index: Int, value: Double?) {
    if (value == null) bindNull(index) else bindDouble(index, value)
}

internal fun SQLiteStatement.bindNullable(index: Int, value: String?) {
    if (value == null) bindNull(index) else bindText(index, value)
}

internal fun SQLiteStatement.bindFlag(index: Int, value: Boolean) {
    bindLong(index, if (value) 1 else 0)
}

internal fun SQLiteStatement.longOrNull(index: Int): Long? = if (isNull(index)) null else getLong(index)

internal fun SQLiteStatement.doubleOrNull(index: Int): Double? = if (isNull(index)) null else getDouble(index)

internal fun SQLiteStatement.textOrNull(index: Int): String? = if (isNull(index)) null else getText(index)
