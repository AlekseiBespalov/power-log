package app.powerlog.data

import androidx.sqlite.SQLiteException

internal actual fun typedResultCode(error: SQLiteException): Int? = null
