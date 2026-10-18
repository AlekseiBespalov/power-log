package app.powerlog.bridge

import expo.modules.kotlin.Promise

internal class MonitorReads : AutoCloseable {
    private val latest = SummaryReads()
    private val inspect = SummaryReads()
    private val plot = SummaryReads()
    private val range = SummaryReads()
    private val metadata = SummaryReads()
    private val lanes = listOf(latest, inspect, plot, range, metadata)

    fun submit(owner: String, cancellationId: String, operation: MonitorOperation, promise: Promise, body: () -> Any?) {
        val lane =
            when (operation) {
                MonitorOperation.Latest -> latest
                MonitorOperation.Inspect -> inspect
                MonitorOperation.Plot -> plot
                MonitorOperation.Stats -> range
                else -> metadata
            }
        lane.submit(owner, cancellationId, promise, body)
    }

    fun cancel(cancellationId: String) = lanes.forEach { it.cancel(cancellationId) }

    fun cancelOwner(owner: String) = lanes.forEach { it.cancelOwner(owner) }

    override fun close() = lanes.forEach { it.close() }
}
