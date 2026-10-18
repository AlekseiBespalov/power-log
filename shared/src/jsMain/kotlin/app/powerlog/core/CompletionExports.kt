@file:OptIn(kotlin.js.ExperimentalJsExport::class)

package app.powerlog.core

import app.powerlog.core.completion.*
import app.powerlog.core.protocol.*
import kotlin.js.JsExport
import kotlin.js.jsTypeOf

@JsExport
class CompletionSnapshotFields internal constructor(internal val snapshot: RideCompletionSnapshot) {
    val collectionRevision: String? = snapshot.collectionRevision?.toString()
    val exportReady: Boolean = RideCompletion.exportReady(snapshot, snapshot.rideId)
    val historyIdentity: String by lazy { CompletionCodec.identity(snapshot) }
    val controlIdentity: String by lazy { CompletionCodec.identity(snapshot, true) }
}

@JsExport
fun completionSnapshotFields(payload: Any): CompletionSnapshotFields =
    CompletionSnapshotFields(CompletionCodec.decodeSnapshot(completionObject(payload, CompletionCodec.snapshotFields)))

@JsExport fun completionExportReady(fields: CompletionSnapshotFields): Boolean = fields.exportReady

@JsExport
fun completionCanDelete(
    targetId: String,
    targetPhase: String,
    current: Any,
    facts: Any? = null,
): Boolean {
    return HistoricalDeletionAdmission.canDelete(
        CompletionCodec.deleteContext(
            targetId,
            targetPhase,
            completionObject(
                current,
                listOf(
                    "id",
                    "phase",
                    "pendingAction",
                    "recoveryState",
                    "healthBusy",
                    "startBusy",
                    "finalizationBusy",
                    "readWorkoutIds",
                ),
            ),
            facts?.let { completionObject(it) },
        )
    )
}

@JsExport
fun completionIdentity(fields: CompletionSnapshotFields, includeActiveFinality: Boolean = false): String =
    if (includeActiveFinality) fields.controlIdentity else fields.historyIdentity

@JsExport
class CompletionUpdateCursor {
    private val updates = CompletionUpdates()

    fun beginRefresh(): Double = updates.beginRefresh().toDouble()

    fun receive(fields: CompletionSnapshotFields): Boolean = updates.receive(fields.snapshot)

    fun finishRefresh(request: Double, fields: CompletionSnapshotFields): Boolean {
        if (
            !request.isFinite() ||
                request <= 0 ||
                request > app.powerlog.core.protocol.MAX_REVISION.toDouble() ||
                request.toLong().toDouble() != request
        )
            return false
        return updates.finishRefresh(request.toLong(), fields.snapshot)
    }
}

@JsExport
fun completionOpenExport(json: String, selectedRideId: String, pinRevision: String): Boolean {
    return try {
        val revision =
            app.powerlog.core.protocol.decimal(
                app.powerlog.core.protocol.Str(pinRevision),
                true,
                app.powerlog.core.protocol.MAX_REVISION,
            )
        RideCompletion.openExport(CompletionCodec.decodeSnapshot(json), selectedRideId, revision)
    } catch (_: IllegalArgumentException) {
        false
    }
}

private val completionObjectKeys: (dynamic) -> Array<String> = js("Object.keys")
private val completionArray: (dynamic) -> Boolean = js("Array.isArray")

private fun completionObject(value: dynamic, keys: List<String>? = null, depth: Int = 0): Obj {
    require(value != null && jsTypeOf(value) == "object" && !completionArray(value)) { "Invalid workout payload" }
    require(depth <= 24) { "Invalid workout payload depth" }
    val fields = mutableMapOf<String, Json>()
    for (key in keys ?: completionObjectKeys(value).asList()) {
        val item = value[key]
        if (jsTypeOf(item) != "undefined") fields[key] = completionValue(item, depth + 1)
    }
    return Obj(fields)
}

private fun completionValue(value: dynamic, depth: Int): Json =
    when (jsTypeOf(value)) {
        "string" -> Str(value as String).also { JsonCodec.checkUnicode(it.value) }
        "boolean" -> Bool(value as Boolean)
        "number" -> Num(value as Double).also { require(it.value.isFinite()) }
        "undefined" -> Nil
        "object" ->
            when {
                value == null -> Nil
                completionArray(value) -> {
                    require(depth <= 24 && (value.length as Int) <= 8000000) { "Invalid workout array" }
                    Arr((value as Array<dynamic>).map { completionValue(it, depth + 1) })
                }
                else -> completionObject(value, depth = depth)
            }
        else -> throw IllegalArgumentException("Invalid workout value")
    }
