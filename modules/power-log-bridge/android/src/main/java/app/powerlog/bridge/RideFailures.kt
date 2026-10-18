package app.powerlog.bridge

import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import android.system.ErrnoException
import android.system.OsConstants
import app.powerlog.core.presentation.Failures
import app.powerlog.core.presentation.RideFailure
import expo.modules.kotlin.Promise
import expo.modules.kotlin.exception.CodedException

internal class RideFailureException(val failure: RideFailure, cause: Throwable? = null) :
    IllegalStateException(failure.text, cause) {
    constructor(code: String?) : this(Failures.present(code))
}

internal object RideFailures {
    private val busyCodes = setOf("ERR_READ_DEFERRED", "ERR_READ_CONTENTION", "ERR_STORAGE_BUSY")
    private val readCodes =
        busyCodes + setOf("ERR_READ_CANCELLED", "ERR_INVALID_RECORD", "ERR_NUMERIC_OVERFLOW", "ERR_CHART_CAPACITY")

    fun classify(error: Throwable, stage: String? = null): RideFailure {
        if (error is RideFailureException) return error.failure
        val code = (error as? CodedException)?.code
        val coded = RideFailure.entries.firstOrNull { it.name == code }
        if (coded != null) return coded
        if (code in busyCodes || error is SQLiteDatabaseLockedException) return RideFailure.storageBusy
        if (error is SQLiteFullException || outOfSpace(error)) return RideFailure.storageFull
        if (error is SQLiteException) return RideFailure.storageFailed
        return when (stage) {
            "location" -> if (error is SecurityException) RideFailure.locationDenied else RideFailure.unknown
            "health" -> if (error is SecurityException) RideFailure.healthDenied else RideFailure.unknown
            "bike" -> if (error is SecurityException) RideFailure.bluetoothDenied else RideFailure.bikeUnavailable
            "start" -> RideFailure.startInterrupted
            else -> RideFailure.unknown
        }
    }

    fun code(error: Throwable, failure: RideFailure): String =
        (error as? CodedException)?.code?.takeIf { it in readCodes } ?: failure.name

    private fun outOfSpace(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.take(8).any { it is ErrnoException && it.errno == OsConstants.ENOSPC }
}

internal fun Promise.rejectFailure(error: Throwable, stage: String? = null) {
    val failure = RideFailures.classify(error, stage)
    reject(
        RideFailures.code(error, failure),
        if ((error as? CodedException)?.code == "ERR_CHART_CAPACITY") error.message else failure.text,
        error,
    )
}
