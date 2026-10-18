@file:Suppress("NOTHING_TO_INLINE", "UNUSED_PARAMETER")

package app.powerlog.bridge

import java.util.UUID

internal object RecordingIdentities {
    inline fun telemetry(): String = UUID.randomUUID().toString()

    inline fun command(): String = UUID.randomUUID().toString()

    inline fun ride(): String = UUID.randomUUID().toString()

    inline fun clockEpoch(): String = UUID.randomUUID().toString()
}

internal object RecordingTransactions {
    inline fun beforeCommit(store: RideStore) {}

    inline fun afterCommit(store: RideStore) {}
}
