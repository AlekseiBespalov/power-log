package app.powerlog.bridge

import java.util.UUID
import java.util.WeakHashMap

internal object RecordingIdentities {
    var telemetryId: (() -> String)? = null
    var commandIds = ArrayDeque<String>()
    var rideId: String? = null
    var epoch: String? = null

    fun telemetry(): String = telemetryId?.invoke() ?: UUID.randomUUID().toString()

    fun command(): String = commandIds.removeFirstOrNull() ?: UUID.randomUUID().toString()

    fun ride(): String = rideId ?: UUID.randomUUID().toString()

    fun clockEpoch(): String = epoch ?: UUID.randomUUID().toString()
}

internal object RecordingTransactions {
    val before = WeakHashMap<RideStore, () -> Unit>()
    val after = WeakHashMap<RideStore, () -> Unit>()

    fun beforeCommit(store: RideStore) {
        before[store]?.invoke()
    }

    fun afterCommit(store: RideStore) {
        after[store]?.invoke()
    }
}

internal var RideStore.beforeTransactionEnd: (() -> Unit)?
    get() = RecordingTransactions.before[this]
    set(value) {
        if (value == null) RecordingTransactions.before.remove(this) else RecordingTransactions.before[this] = value
    }

internal var RideStore.afterTransactionEnd: (() -> Unit)?
    get() = RecordingTransactions.after[this]
    set(value) {
        if (value == null) RecordingTransactions.after.remove(this) else RecordingTransactions.after[this] = value
    }
