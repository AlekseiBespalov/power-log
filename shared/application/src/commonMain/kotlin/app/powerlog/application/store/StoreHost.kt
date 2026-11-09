package app.powerlog.application.store

import app.powerlog.domain.core.failure.RefusalDetail

public interface StoreHost {
    public suspend fun open(): StoreResult<StoreOpen>

    public suspend fun close(): StoreResult<Unit>
}

public sealed interface StoreOpen {
    // phones and JVM: the archive, then the cache if it opened; web: empty
    public data class Opened(public val pragmas: List<FilePragmas>) : StoreOpen

    public data class Refused(public val detail: RefusalDetail) : StoreOpen
}

public enum class StoreFile {
    archive,
    cache,
}

public data class FilePragmas(
    public val file: StoreFile,
    public val synchronous: Int,
    public val journalMode: String,
    public val autoVacuum: Int,
    public val walAutocheckpoint: Int,
)
