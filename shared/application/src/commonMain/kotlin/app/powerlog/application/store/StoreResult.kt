package app.powerlog.application.store

import app.powerlog.domain.core.failure.PlatformCode

public sealed interface StoreResult<out T> {
    public data class Ok<T>(public val value: T) : StoreResult<T>

    public data class Failed(public val error: StorageError) : StoreResult<Nothing>
}

public data class StorageError(public val kind: Kind, public val platform: PlatformCode? = null) {
    public enum class Kind {
        busy,
        full,
        io,
        corrupt,
        constraint,
        closed,
    }
}
