package app.powerlog.application.health

import app.powerlog.application.port.Clock
import app.powerlog.application.port.HealthAccess
import app.powerlog.application.port.HealthConnectWriter
import app.powerlog.application.port.HealthGrants
import app.powerlog.application.port.HealthOriginalPage
import app.powerlog.application.port.HealthProvider
import app.powerlog.application.port.HealthReader
import app.powerlog.application.port.PortError
import app.powerlog.application.port.PortErrorKind
import app.powerlog.application.port.Reply
import app.powerlog.domain.core.health.HealthProviderKind
import app.powerlog.domain.health.HealthConnectRecordPlan
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class HealthConnectCalls(
    private val adapter: HealthProvider,
    private val scope: CoroutineScope,
    private val health: CoroutineDispatcher,
    private val clock: Clock,
) : HealthProvider {
    private class Call(val deadlineMonoUs: Long, val start: () -> Unit, val busy: () -> Unit)

    private val queue = ArrayDeque<Call>()
    private var inFlight: Call? = null
    private var abandoned: Call? = null
    private val fifo = adapter.kind == HealthProviderKind.healthConnect

    override val kind: HealthProviderKind = adapter.kind

    override val access: HealthAccess =
        object : HealthAccess {
            override fun availability() = adapter.access.availability()

            override fun grants(reply: Reply<HealthGrants>) = queued(reply) { adapter.access.grants(it) }

            override fun request(scope: app.powerlog.application.port.HealthScope, reply: Reply<Unit>) =
                adapter.access.request(scope, reply)
        }

    override val reader: HealthReader =
        object : HealthReader {
            override fun read(
                metric: String,
                startUtcUs: Long,
                endUtcUs: Long,
                cursor: String?,
                reply: Reply<HealthOriginalPage>,
            ) = queued(reply) { adapter.reader.read(metric, startUtcUs, endUtcUs, cursor, it) }
        }

    override val session = adapter.session

    override val writer: HealthConnectWriter? =
        adapter.writer?.let { writer ->
            object : HealthConnectWriter {
                override val packageName: String = writer.packageName
                override val appLabel: String = writer.appLabel

                override fun insert(records: List<HealthConnectRecordPlan>, reply: Reply<List<String>>) =
                    queued(reply) { writer.insert(records, it) }
            }
        }

    private fun <T> queued(reply: Reply<T>, request: (Reply<T>) -> Unit) {
        if (!fifo) return request(reply)
        val deadline = clock.monoUs() + HealthWorkPolicy.portCallBudgetUs
        scope.launch(health) {
            lateinit var call: Call
            call =
                Call(
                    deadline,
                    start = {
                        request(
                            Reply(
                                onOk = { value -> settled(call) { reply.ok(value) } },
                                onFailed = { error -> settled(call) { reply.failed(error) } },
                            )
                        )
                    },
                    busy = { reply.failed(PortError(PortErrorKind.busy)) },
                )
            queue.addLast(call)
            pump()
        }
    }

    private fun settled(call: Call, deliver: () -> Unit) {
        scope.launch(health) {
            deliver()
            if (inFlight === call) inFlight = null
            if (abandoned === call) abandoned = null
            pump()
        }
    }

    private fun pump() {
        while (inFlight == null) {
            val call = queue.removeFirstOrNull() ?: return
            if (clock.monoUs() >= call.deadlineMonoUs) continue
            if (abandoned != null) {
                call.busy()
                continue
            }
            inFlight = call
            scope.launch(health) {
                delay((call.deadlineMonoUs - clock.monoUs() + 999) / 1_000)
                if (inFlight === call) {
                    inFlight = null
                    abandoned = call
                    pump()
                }
            }
            call.start()
        }
    }
}
