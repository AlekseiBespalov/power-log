package app.powerlog.data

import androidx.sqlite.SQLiteException
import app.powerlog.application.store.StorageError
import app.powerlog.domain.core.failure.PlatformCode

private val RESULT_CODE = Regex("""(?:Error code: |\(code )(\d+)""")

internal fun sqliteResultCode(error: Throwable): Int? {
    if (error !is SQLiteException) return null
    return error.message?.let { RESULT_CODE.find(it) }?.groupValues?.get(1)?.toIntOrNull() ?: typedResultCode(error)
}

// The result code an exception's type tells when its message carries none.
internal expect fun typedResultCode(error: SQLiteException): Int?

internal fun storageError(error: Throwable): StorageError {
    if (error is ConstraintViolation) return StorageError(StorageError.Kind.constraint)
    val code = sqliteResultCode(error) ?: return StorageError(StorageError.Kind.io)
    val kind =
        when (code and 0xff) {
            SQLITE_BUSY,
            SQLITE_LOCKED -> StorageError.Kind.busy
            SQLITE_FULL -> StorageError.Kind.full
            SQLITE_CORRUPT,
            SQLITE_NOTADB -> StorageError.Kind.corrupt
            SQLITE_CONSTRAINT -> StorageError.Kind.constraint
            else -> StorageError.Kind.io
        }
    return StorageError(kind, PlatformCode("SQLite", resultName(code)))
}

internal fun resultName(code: Int): String {
    val primary = PRIMARY.getOrNull(code and 0xff) ?: return "SQLITE_$code"
    val extended = EXTENDED[code and 0xff]?.getOrNull((code shr 8) - 1)
    return if (code shr 8 == 0 || extended == null) "SQLITE_$primary" else "SQLITE_${primary}_$extended"
}

internal const val SQLITE_BUSY = 5
internal const val SQLITE_LOCKED = 6
internal const val SQLITE_IOERR = 10
internal const val SQLITE_CORRUPT = 11
internal const val SQLITE_FULL = 13
internal const val SQLITE_CANTOPEN = 14
internal const val SQLITE_CONSTRAINT = 19
internal const val SQLITE_NOTADB = 26

private val PRIMARY =
    listOf(
        "OK",
        "ERROR",
        "INTERNAL",
        "PERM",
        "ABORT",
        "BUSY",
        "LOCKED",
        "NOMEM",
        "READONLY",
        "INTERRUPT",
        "IOERR",
        "CORRUPT",
        "NOTFOUND",
        "FULL",
        "CANTOPEN",
        "PROTOCOL",
        "EMPTY",
        "SCHEMA",
        "TOOBIG",
        "CONSTRAINT",
        "MISMATCH",
        "MISUSE",
        "NOLFS",
        "AUTH",
        "FORMAT",
        "RANGE",
        "NOTADB",
        "NOTICE",
        "WARNING",
    )

// sqlite3.h: extended code n of a primary is primary | (n << 8), listed from n = 1
private val EXTENDED: Map<Int, List<String?>> =
    mapOf(
        1 to listOf("MISSING_COLLSEQ", "RETRY", "SNAPSHOT"),
        4 to listOf(null, "ROLLBACK"),
        5 to listOf("RECOVERY", "SNAPSHOT", "TIMEOUT"),
        6 to listOf("SHAREDCACHE", "VTAB"),
        8 to listOf("RECOVERY", "CANTLOCK", "ROLLBACK", "DBMOVED", "CANTINIT", "DIRECTORY"),
        10 to
            listOf(
                "READ",
                "SHORT_READ",
                "WRITE",
                "FSYNC",
                "DIR_FSYNC",
                "TRUNCATE",
                "FSTAT",
                "UNLOCK",
                "RDLOCK",
                "DELETE",
                "BLOCKED",
                "NOMEM",
                "ACCESS",
                "CHECKRESERVEDLOCK",
                "LOCK",
                "CLOSE",
                "DIR_CLOSE",
                "SHMOPEN",
                "SHMSIZE",
                "SHMLOCK",
                "SHMMAP",
                "SEEK",
                "DELETE_NOENT",
                "MMAP",
                "GETTEMPPATH",
                "CONVPATH",
                "VNODE",
                "AUTH",
                "BEGIN_ATOMIC",
                "COMMIT_ATOMIC",
                "ROLLBACK_ATOMIC",
                "DATA",
                "CORRUPTFS",
                "IN_PAGE",
            ),
        11 to listOf("VTAB", "SEQUENCE", "INDEX"),
        14 to listOf("NOTEMPDIR", "ISDIR", "FULLPATH", "CONVPATH", "DIRTYWAL", "SYMLINK"),
        19 to
            listOf(
                "CHECK",
                "COMMITHOOK",
                "FOREIGNKEY",
                "FUNCTION",
                "NOTNULL",
                "PRIMARYKEY",
                "TRIGGER",
                "UNIQUE",
                "VTAB",
                "ROWID",
                "PINNED",
                "DATATYPE",
            ),
        23 to listOf("USER"),
        27 to listOf("RECOVER_WAL", "RECOVER_ROLLBACK", "RBU"),
        28 to listOf("AUTOINDEX"),
    )
