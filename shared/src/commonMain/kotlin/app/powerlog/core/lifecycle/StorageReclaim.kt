package app.powerlog.core.lifecycle

enum class StorageReclaimAction {
    reclaim,
    enableIncremental,
    convert,
    waitForIdle,
    waitForSpace,
    done,
}

class StorageReclaimStep(val action: StorageReclaimAction, val pages: Long = 0)

object StorageReclaim {
    const val stepBytes = 1L shl 20
    const val conversionMarginBytes = 256L shl 20
    val recordingPhases = listOf("preparing", "running", "paused", "finishing")

    @Throws(IllegalArgumentException::class)
    fun next(
        autoVacuum: Long,
        pageSize: Long,
        pageCount: Long,
        freePages: Long,
        recording: Boolean,
        availableBytes: Long,
    ): StorageReclaimStep {
        require(autoVacuum in 0..2 && pageSize > 0 && freePages in 0..pageCount)
        // PRAGMA auto_vacuum reports 0 none, 1 full, 2 incremental (https://sqlite.org/pragma.html#pragma_auto_vacuum).
        return when (autoVacuum) {
            2L ->
                if (freePages == 0L) StorageReclaimStep(StorageReclaimAction.done)
                else StorageReclaimStep(StorageReclaimAction.reclaim, minOf(freePages, maxOf(1, stepBytes / pageSize)))
            1L -> StorageReclaimStep(StorageReclaimAction.enableIncremental)
            else ->
                when {
                    recording -> StorageReclaimStep(StorageReclaimAction.waitForIdle)
                    // VACUUM copies the live pages into a temporary database, then writes them all again through
                    // the WAL (https://sqlite.org/lang_vacuum.html).
                    availableBytes < 2 * (pageCount - freePages) * pageSize + conversionMarginBytes ->
                        StorageReclaimStep(StorageReclaimAction.waitForSpace)
                    else -> StorageReclaimStep(StorageReclaimAction.convert)
                }
        }
    }
}
