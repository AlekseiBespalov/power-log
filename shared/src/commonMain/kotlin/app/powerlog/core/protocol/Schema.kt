package app.powerlog.core.protocol

import app.powerlog.core.presentation.SampleRates

internal const val MAX_REVISION = 9007199254740991L
internal val uuidGrammar = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
internal const val ZERO_ENDPOINT = "00000000-0000-0000-0000-000000000000"

internal fun uuid(value: Json): String {
    val text = value.string()
    checkValue(uuidGrammar.matches(text))
    return text.lowercase()
}

internal fun decimal(value: Json, positive: Boolean = false, maximum: Long = Long.MAX_VALUE): Long {
    val text = value.string()
    checkValue(text.isNotEmpty() && (text == "0" || text[0] in '1'..'9' && text.all { it in '0'..'9' }))
    val number = text.toLongOrNull() ?: invalid()
    checkValue(number in (if (positive) 1 else 0)..maximum)
    return number
}

internal fun integer(value: Json, low: Int = 0, high: Int = Int.MAX_VALUE): Int {
    val number = value.number()
    checkValue(number >= low && number <= high && number == number.toInt().toDouble())
    return number.toInt()
}

internal fun text(value: Json, maximum: Int = 4096): String =
    value.string().also { checkValue(it.encodeToByteArray().size <= maximum) }

internal fun choice(value: Json, allowed: String): String = value.string().also { checkValue(it in allowed.split(' ')) }

internal fun timestamp(value: Json) = Timestamps.parse(value.string())

internal fun seconds(value: Json): Double = value.number().also { checkValue(it in 0.0..2678400.0) }

internal fun nonnegative(value: Json): Double = value.number().also { checkValue(it >= 0) }

internal fun hash(value: Json): String =
    value.string().also { checkValue(it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' }) }

internal fun rate(value: Json) = integer(value, 2, 8).also { checkValue(SampleRates.accepts(it.toDouble())) }

internal fun version(value: Json) {
    if (integer(value) != 1) reject(Rejection.unsupportedVersion)
}

internal fun Obj.optionalUuid(key: String): String? = this[key]?.let(::uuid)

internal fun Obj.optionalTime(key: String): ProtocolTimestamp? = this[key]?.let(::timestamp)

internal fun Obj.optionalText(key: String): String? = this[key]?.let { text(it, 1024) }

internal object Schema {
    fun timing(value: Json): OwnerTiming {
        val o = value.obj()
        o.closed("timestamp elapsedSeconds timerSeconds")
        val elapsed = seconds(o.required("elapsedSeconds"))
        val timer = seconds(o.required("timerSeconds"))
        checkValue(timer <= elapsed)
        return OwnerTiming(timestamp(o.required("timestamp")), elapsed, timer)
    }

    fun owner(value: Json): OwnerSnapshot {
        val o = value.obj()
        o.closed(
            "owner ownerRevision effectiveAt phase healthOutcome",
            "healthWorkoutID stopCutoff timing healthReason",
        )
        val outcome =
            choice(
                o.required("healthOutcome"),
                "pending saved failed notSaved unavailable unknown discarded notRequested",
            )
        val reason = o.optionalText("healthReason")
        checkValue(outcome != "unavailable" || !reason.isNullOrEmpty())
        val phase = choice(o.required("phase"), "ready preparing running paused interrupted finishing completed failed")
        val cutoff = o.optionalTime("stopCutoff")
        val timing = o["timing"]?.let(::timing)
        checkValue(cutoff == null || timing != null && cutoff.original == timing.timestamp.original)
        checkValue(phase !in listOf("finishing", "completed") || cutoff != null && timing != null)
        return OwnerSnapshot(
            Role.valueOf(choice(o.required("owner"), "phone watch")),
            decimal(o.required("ownerRevision"), true, MAX_REVISION),
            timestamp(o.required("effectiveAt")),
            phase,
            outcome,
            o.optionalUuid("healthWorkoutID"),
            cutoff,
            timing,
            reason,
        )
    }

    fun event(value: Json, rideId: String): Event {
        val o = value.obj()
        o.closed("schemaVersion eventId workoutId kind source timestamp payload", "elapsedSeconds")
        version(o.required("schemaVersion"))
        val eventId = uuid(o.required("eventId"))
        val workoutId = uuid(o.required("workoutId"))
        checkValue(workoutId == rideId)
        val kind = choice(o.required("kind"), "telemetry location health lifecycle")
        val source = choice(o.required("source"), "controller phone watch")
        val time = timestamp(o.required("timestamp"))
        val elapsed = o["elapsedSeconds"]?.let(::seconds)
        checkValue(kind == "health" || elapsed != null)
        val payload = o.required("payload").obj()
        checkValue(payload.fields.size <= 64)
        when (kind) {
            "telemetry" -> {
                checkValue(source == "controller")
                payload.required("humanPowerW").number()
                payload.required("cadenceRpm").number()
            }
            "location" -> {
                checkValue(source != "controller")
                checkValue(payload.required("latitude").number() in -90.0..90.0)
                checkValue(payload.required("longitude").number() in -180.0..180.0)
            }
            "health" -> {
                checkValue(source != "controller" && payload.fields.isNotEmpty())
                for (key in listOf("activeEnergyKcal", "basalEnergyKcal", "distanceMeters")) payload[key]?.let(
                    ::nonnegative
                )
            }
            "lifecycle" -> {
                checkValue(source != "controller")
                choice(payload.required("action"), "start pause resume lap stop")
            }
        }
        payload["observationSequence"]?.let {
            val counter = it.string()
            checkValue(
                counter == "0" || counter.isNotEmpty() && counter[0] in '1'..'9' && counter.all { c -> c in '0'..'9' }
            )
            checkValue(counter.length < 20 || counter.length == 20 && counter <= "18446744073709551615")
        }
        payload["sequence"]?.let { decimal(it) }
        payload["providerMetadata"]?.let { metadata(it, 0) }
        val normalized = normalize(o, mapOf("eventId" to Str(eventId), "workoutId" to Str(workoutId)))
        bounded(normalized, 0)
        checkValue(JsonCodec.encode(normalized).encodeToByteArray().size <= 32768)
        return Event(eventId, workoutId, kind, source, time, elapsed, JsonCodec.encode(payload), normalized)
    }

    private fun metadata(value: Json, depth: Int) {
        checkValue(depth <= 6)
        when (value) {
            is Obj -> {
                checkValue(value.fields.size <= 128)
                when ((value["type"] as? Str)?.value) {
                    "date" -> {
                        value.closed("type value")
                        timestamp(value.required("value"))
                    }
                    "quantity" -> {
                        value.closed("type value unit originalDescription")
                        value.required("value").number()
                        text(value.required("unit"))
                        text(value.required("originalDescription"))
                    }
                    "quantityDescription",
                    "string" -> {
                        value.closed("type value")
                        text(value.required("value"))
                    }
                    "boolean" -> {
                        value.closed("type value")
                        value.required("value").boolean()
                    }
                    "number" -> {
                        value.closed("type value")
                        value.required("value").number()
                    }
                }
                value.fields.values.forEach { metadata(it, depth + 1) }
            }
            is Arr -> {
                checkValue(value.values.size <= 128)
                value.values.forEach { metadata(it, depth + 1) }
            }
            else -> Unit
        }
    }

    private fun bounded(value: Json, depth: Int) {
        checkValue(depth <= 8)
        when (value) {
            is Obj -> {
                checkValue(value.fields.size <= 128)
                value.fields.forEach { (key, child) ->
                    checkValue(key.encodeToByteArray().size <= 4096)
                    bounded(child, depth + 1)
                }
            }
            is Arr -> {
                checkValue(value.values.size <= 256)
                value.values.forEach { bounded(it, depth + 1) }
            }
            is Str -> text(value)
            else -> Unit
        }
    }

    fun normalize(o: Obj, changes: Map<String, Json>) = Obj(o.fields + changes)
}
