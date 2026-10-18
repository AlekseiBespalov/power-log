package app.powerlog.core.completion

import app.powerlog.core.health.*
import app.powerlog.core.protocol.*

object CompletionCodec {
    val snapshotFields: List<String> =
        listOf(
            "id",
            "phase",
            "endedAt",
            "collectionRevision",
            "healthKitState",
            "healthReason",
            "saveToHealth",
            "watchEnabled",
            "useWatch",
            "storage",
            "exportEligible",
            "healthRetryAvailable",
        )

    @Throws(IllegalArgumentException::class, ProtocolException::class)
    fun decodeSnapshot(json: String): RideCompletionSnapshot =
        decodeSnapshot(
            JsonCodec.parse(json, arrayLimit = 8000000, byteLimit = Int.MAX_VALUE, wireNumbers = false).obj()
        )

    internal fun decodeSnapshot(o: Obj): RideCompletionSnapshot {
        fun text(key: String): String? = o[key]?.takeUnless { it == Nil }?.string()
        fun flag(key: String, fallback: Boolean): Boolean = o[key]?.boolean() ?: fallback
        val health =
            HealthOutcome.entries.firstOrNull { it.name == text("healthKitState") }
                ?: throw IllegalArgumentException("Invalid Health outcome")
        val revision = o["collectionRevision"]?.takeUnless { it == Nil }?.let { decimal(it, maximum = MAX_REVISION) }
        return RideCompletionSnapshot(
            text("id"),
            text("phase") ?: "idle",
            text("endedAt")?.let(Timestamps::parse),
            revision,
            health,
            text("healthReason"),
            flag("saveToHealth", true),
            flag("watchEnabled", flag("useWatch", false)),
            text("storage") == "browser",
            flag("exportEligible", true),
            flag("healthRetryAvailable", false),
        )
    }

    @Throws(ProtocolException::class)
    fun deleteContext(
        targetId: String,
        targetPhase: String,
        currentJson: String,
        factsJson: String? = null,
    ): HistoricalDeleteContext {
        val current =
            JsonCodec.parse(currentJson, arrayLimit = 8000000, byteLimit = Int.MAX_VALUE, wireNumbers = false).obj()
        val facts = factsJson?.let { JsonCodec.parse(it).obj() }
        return deleteContext(targetId, targetPhase, current, facts)
    }

    internal fun deleteContext(
        targetId: String,
        targetPhase: String,
        current: Obj,
        facts: Obj?,
    ): HistoricalDeleteContext {
        facts?.closed("", "healthBusy startBusy finalizationBusy targetPending targetRecovering targetBusy")
        fun busy(key: String): Boolean? = facts?.get(key)?.takeUnless { it == Nil }?.boolean()
        fun currentBusy(key: String, observed: Boolean = false): Boolean? {
            val currentWork = current[key]?.takeUnless { it == Nil }?.boolean()
            val retainedWork = busy(key)
            return if (observed || currentWork == true || retainedWork == true) true else retainedWork ?: currentWork
        }
        val selected = current["id"] == Str(targetId)
        val phase = current.required("phase").string()
        val pending = current["pendingAction"]?.takeUnless { it == Nil }?.string()
        return HistoricalDeleteContext(
            targetPhase,
            if (selected) phase else null,
            healthBusy = currentBusy("healthBusy"),
            startBusy = currentBusy("startBusy", phase == "preparing" || pending == "start"),
            finalizationBusy = currentBusy("finalizationBusy", phase == "finishing"),
            targetPending = if (selected && pending != null) true else busy("targetPending"),
            targetRecovering =
                if (
                    selected &&
                        (phase == "interrupted" ||
                            current["recoveryState"] in
                                listOf("checking", "checkingStorage", "storage", "unresolved").map(::Str))
                )
                    true
                else busy("targetRecovering"),
            targetBusy =
                if (
                    current["readWorkoutIds"]?.takeUnless { it == Nil }?.array()?.any { it.string() == targetId } ==
                        true
                )
                    true
                else busy("targetBusy"),
        )
    }

    @Suppress("UNUSED_PARAMETER")
    @Throws(ProtocolException::class)
    fun identity(snapshot: RideCompletionSnapshot, includeActiveFinality: Boolean = false): String = buildString {
        appendIdentity(
            Obj(
                mapOf(
                    "id" to (snapshot.rideId?.let(::Str) ?: Nil),
                    "phase" to Str(snapshot.phase),
                    "endedAt" to (snapshot.endedAt?.original?.let(::Str) ?: Nil),
                    "collectionRevision" to
                        (snapshot.collectionRevision?.takeIf { snapshot.phase == "completed" }?.toString()?.let(::Str)
                            ?: Nil),
                    "health" to Str(snapshot.healthOutcome.name),
                    "healthReason" to (snapshot.healthReason?.let(::Str) ?: Nil),
                    "healthRetryAvailable" to Bool(snapshot.healthRetryAvailable),
                )
            )
        )
    }
}

private fun StringBuilder.appendIdentity(value: Json) {
    when (value) {
        is Obj -> {
            append('o').append(value.fields.size).append(':')
            for (key in value.fields.keys.sorted()) {
                appendIdentity(Str(key))
                appendIdentity(value.fields.getValue(key))
            }
        }
        is Arr -> {
            append('a').append(value.values.size).append(':')
            for (item in value.values) appendIdentity(item)
        }
        is Str -> append('s').append(value.value.length).append(':').append(value.value)
        is Bool -> append(if (value.value) 't' else 'f')
        Nil -> append('n')
        is Num -> error("Unexpected completion identity number")
    }
}
