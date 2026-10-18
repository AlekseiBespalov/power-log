package app.powerlog.core.protocol

internal fun obj(vararg fields: Pair<String, Json?>) =
    Obj(fields.filter { it.second != null }.associate { it.first to it.second!! })

internal fun String.json() = Str(this)

internal fun Long.json() = Str(toString())

internal fun Int.json() = Num(toDouble())

internal fun Double.json() = Num(this)

internal fun Boolean.json() = Bool(this)

internal fun OwnerTiming.json() =
    obj(
        "timestamp" to timestamp.original.json(),
        "elapsedSeconds" to elapsedSeconds.json(),
        "timerSeconds" to timerSeconds.json(),
    )

internal fun OwnerSnapshot.json() =
    obj(
        "owner" to owner.name.json(),
        "ownerRevision" to ownerRevision.json(),
        "effectiveAt" to effectiveAt.original.json(),
        "phase" to phase.json(),
        "healthOutcome" to healthOutcome.json(),
        "healthWorkoutID" to healthWorkoutID?.json(),
        "stopCutoff" to stopCutoff?.original?.json(),
        "timing" to timing?.json(),
        "healthReason" to healthReason?.json(),
    )
