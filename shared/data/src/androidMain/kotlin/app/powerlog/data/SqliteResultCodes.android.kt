package app.powerlog.data

import android.database.sqlite.SQLiteAbortException
import android.database.sqlite.SQLiteAccessPermException
import android.database.sqlite.SQLiteBlobTooBigException
import android.database.sqlite.SQLiteCantOpenDatabaseException
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteDatatypeMismatchException
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteFullException
import android.database.sqlite.SQLiteMisuseException
import android.database.sqlite.SQLiteOutOfMemoryException
import android.database.sqlite.SQLiteReadOnlyDatabaseException
import android.database.sqlite.SQLiteTableLockedException
import androidx.sqlite.SQLiteException

internal actual fun typedResultCode(error: SQLiteException): Int? =
    when (error) {
        is SQLiteAbortException -> 4
        is SQLiteDatabaseLockedException -> SQLITE_BUSY
        is SQLiteTableLockedException -> SQLITE_LOCKED
        is SQLiteOutOfMemoryException -> 7
        is SQLiteReadOnlyDatabaseException -> 8
        is SQLiteDiskIOException -> SQLITE_IOERR
        is SQLiteDatabaseCorruptException -> SQLITE_CORRUPT
        is SQLiteFullException -> SQLITE_FULL
        is SQLiteCantOpenDatabaseException -> SQLITE_CANTOPEN
        is SQLiteBlobTooBigException -> 18
        is SQLiteConstraintException -> SQLITE_CONSTRAINT
        is SQLiteDatatypeMismatchException -> 20
        is SQLiteMisuseException -> 21
        is SQLiteAccessPermException -> 3
        else -> null
    }
