@file:OptIn(ExperimentalAtomicApi::class, ExperimentalUuidApi::class)

package app.powerlog.runtime.bridge

import app.powerlog.application.api.ApiFailure
import app.powerlog.application.api.ApiVersion
import app.powerlog.application.api.CancelInput
import app.powerlog.application.api.Outcome
import app.powerlog.application.api.PowerLogApi
import app.powerlog.application.exports.ExportCloseInput
import app.powerlog.application.exports.ExportOpenInput
import app.powerlog.application.exports.ExportSessionView
import app.powerlog.application.imports.ImportInput
import app.powerlog.application.imports.ImportedRideView
import app.powerlog.application.imports.KeyInput
import app.powerlog.application.port.Clock
import app.powerlog.application.store.DiagnosticLogRepository
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.ids.RideId
import app.powerlog.runtime.API_HASH
import app.powerlog.runtime.diagnostics.Unexpected
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

public class BridgeSession
internal constructor(
    private val api: PowerLogApi,
    private val codec: WireCodec,
    private val clock: Clock,
    private val log: DiagnosticLogRepository,
    sections: List<ApiSection> = emptyList(),
) {
    private data class Ledger(
        val keys: Map<Long, String> = emptyMap(),
        val exports: Set<String> = emptySet(),
        val imports: Set<String> = emptySet(),
        val closed: Boolean = false,
    )

    @Serializable private class NextRequest(val params: JsonObject, val after: Long?, val key: String)

    private class UnknownName(name: String) : IllegalArgumentException(name)

    private val sessionId = Uuid.random().toString()
    private val dispatch = ApiDispatch(api, codec)
    private val sections = listOf(dispatch) + sections

    init {
        val names = this.sections.flatMap { it.names }
        require(names.size == names.toSet().size) {
            "API sections share a name: ${names.groupBy { it }.filter { it.value.size > 1 }.keys}"
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val ledger = AtomicReference(Ledger())
    private val requests = AtomicLong(0L)
    private val unknown = ApiFailure(FailureCode.unknown, retryable = false, occurrence = null, detail = null)

    public fun call(op: String, request: String, reply: (String) -> Unit) {
        if (ledger.load().closed) return
        if (op == "apiVersion") return reply(codec.answer(ApiVersion.serializer(), Outcome.Ok(ApiVersion(API_HASH))))
        serve(reply) { id ->
            val input = keyed(request)
            when (op) {
                "exports.close" ->
                    input.text("sessionId")?.let { closed -> update { it.copy(exports = it.exports - closed) } }
                "imports.close" ->
                    input.text("importId")?.let { closed -> update { it.copy(imports = it.imports - closed) } }
            }
            val key = if (op == "cancel") null else input.text("key")
            inFlight(id, key) {
                when (op) {
                    "exports.open" ->
                        opened(
                            ExportSessionView.serializer(),
                            api.exports.open(codec.decode(ExportOpenInput.serializer(), input)),
                            { it.sessionId },
                            { ledger, opened -> ledger.copy(exports = ledger.exports + opened) },
                            ::closeExport,
                        )
                    "imports.open" ->
                        opened(
                            ImportedRideView.serializer(),
                            api.imports.open(codec.decode(KeyInput.serializer(), input)),
                            { it.importId.value },
                            { ledger, opened -> ledger.copy(imports = ledger.imports + opened) },
                            ::closeImport,
                        )
                    else -> section(op).call(op, input) ?: throw UnknownName(op)
                }
            }
        }
    }

    public fun next(stream: String, request: String, reply: (String) -> Unit) {
        if (ledger.load().closed) return
        serve(reply) { id ->
            val next = codec.decode(NextRequest.serializer(), keyed(request))
            inFlight(id, next.key) {
                section(stream).next(stream, next.params, next.after, next.key) ?: throw UnknownName(stream)
            }
        }
    }

    public fun exportPage(request: String, reply: (BinaryPage) -> Unit) {
        if (ledger.load().closed) return
        scope.launch {
            val page =
                try {
                    dispatch.exportPage(codec.json.parseToJsonElement(request).jsonObject)
                } catch (_: CancellationException) {
                    BinaryPage(WireCodec.CANCELLED, emptyMap())
                } catch (error: Throwable) {
                    deliver { reply(BinaryPage(codec.failed(unknown), emptyMap())) }
                    report(error)
                    return@launch
                }
            deliver { reply(page) }
        }
    }

    public fun close() {
        val previous = update { it.copy(closed = true) }
        if (previous.closed) return
        for (key in previous.keys.values.toSet()) guarded { api.cancel(CancelInput(key)) }
        for (id in previous.exports) closeExport(id)
        for (id in previous.imports) closeImport(id)
    }

    private fun serve(reply: (String) -> Unit, work: suspend (id: Long) -> String) {
        val id = requests.fetchAndAdd(1L)
        scope.launch {
            val answer =
                try {
                    work(id)
                } catch (_: CancellationException) {
                    WireCodec.CANCELLED
                } catch (error: Throwable) {
                    deliver { reply(codec.failed(unknown)) }
                    report(error)
                    return@launch
                }
            deliver { reply(answer) }
        }
    }

    private fun section(name: String): ApiSection =
        sections.firstOrNull { name.substringBefore('.') in it.names } ?: throw UnknownName(name)

    private fun keyed(request: String): JsonObject {
        val input = codec.json.parseToJsonElement(request).jsonObject
        val key = input["key"] as? JsonPrimitive
        if (key == null || !key.isString) return input
        return JsonObject(input + ("key" to JsonPrimitive("$sessionId/${key.content}")))
    }

    private suspend fun inFlight(id: Long, key: String?, work: suspend () -> String): String {
        if (key == null) return work()
        if (update { if (it.closed) it else it.copy(keys = it.keys + (id to key)) }.closed) return WireCodec.CANCELLED
        try {
            return work()
        } finally {
            update { it.copy(keys = it.keys - id) }
        }
    }

    private fun <T> opened(
        serializer: KSerializer<T>,
        outcome: Outcome<T>,
        id: (T) -> String,
        record: (Ledger, String) -> Ledger,
        close: (String) -> Unit,
    ): String {
        if (outcome !is Outcome.Ok) return codec.answer(serializer, outcome)
        val opened = id(outcome.value)
        val answer =
            try {
                codec.answer(serializer, outcome)
            } catch (error: Throwable) {
                close(opened)
                throw error
            }
        if (update { if (it.closed) it else record(it, opened) }.closed) close(opened)
        return answer
    }

    private fun closeExport(id: String) = guarded { api.exports.close(ExportCloseInput(id, null)) }

    private fun closeImport(id: String) = guarded { api.imports.close(ImportInput(RideId(id))) }

    private fun guarded(work: suspend () -> Unit) {
        scope.launch {
            try {
                work()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                report(error)
            }
        }
    }

    private inline fun deliver(reply: () -> Unit) {
        if (!ledger.load().closed) reply()
    }

    private suspend fun report(error: Throwable) {
        log.append(Unexpected.line("bridge", error, clock.read()))
    }

    private inline fun update(change: (Ledger) -> Ledger): Ledger {
        while (true) {
            val current = ledger.load()
            if (ledger.compareAndSet(current, change(current))) return current
        }
    }

    private fun JsonObject.text(field: String): String? = (get(field) as? JsonPrimitive)?.contentOrNull
}

public class BinaryPage(public val envelope: String, public val numbers: Map<String, ByteArray>)
