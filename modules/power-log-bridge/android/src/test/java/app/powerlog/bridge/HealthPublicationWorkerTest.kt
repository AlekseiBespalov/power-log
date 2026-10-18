package app.powerlog.bridge

import app.powerlog.core.health.ExecutionMode

class RefreshHealthWorker(context: android.content.Context, parameters: androidx.work.WorkerParameters) :
    HealthPublicationWorker(context, parameters) {
    internal override fun publisher(store: RideStore) =
        HealthExport(
            applicationContext,
            store,
            access,
            { clock },
            { mode },
        )

    internal override fun continueAt(id: String, next: Long?) {
        scheduled = next
        continuations.add(next)
    }

    companion object {
        internal var access = FakeHealthConnectAccess()
        var clock = 0L
        var scheduled: Long? = null
        internal var mode = ExecutionMode.foreground
        val continuations = mutableListOf<Long?>()
    }
}
