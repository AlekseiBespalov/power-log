package app.powerlog.core.protocol

internal object ControllerSchema {
    fun metric(value: Json, maximum: Double): ControllerMetric {
        val o = value.obj()
        o.closed("contentVersion sourceRevision content")
        val c = o.required("content").obj()
        val content =
            when (choice(c.required("state"), "present absent")) {
                "present" -> {
                    c.closed("state value")
                    ControllerPresent(c.required("value").number().also { checkValue(it in 0.0..maximum) })
                }
                else -> {
                    c.closed("state reason")
                    ControllerAbsent(
                        ControllerAbsence.valueOf(
                            choice(c.required("reason"), "noValidReading noCoverage inactive invalid numericOverflow")
                        )
                    )
                }
            }
        return ControllerMetric(
            decimal(o.required("contentVersion"), true),
            decimal(o.required("sourceRevision"), true, MAX_REVISION),
            content,
        )
    }

    fun second(value: Json): ControllerSecond {
        val o = value.obj()
        o.closed("n endOffsetUs power cadence")
        val n = decimal(o.required("n"), maximum = 2678399)
        val end = decimal(o.required("endOffsetUs"), true, 2678400000000)
        checkValue(end > n * 1000000 && end <= (n + 1) * 1000000)
        val second = ControllerSecond(n, end, metric(o.required("power"), 5000.0), metric(o.required("cadence"), 300.0))
        checkValue(second.canonicalBytes.size <= 1024)
        return second
    }

    fun distance(value: Json): DisplayDistance {
        val o = value.obj()
        val generation = decimal(o.required("generation"), true, MAX_REVISION)
        val sequence = decimal(o.required("projectionSequence"), true)
        val selection = choice(o.required("selection"), DISTANCE_SOURCES)
        return when (choice(o.required("state"), "available unavailable")) {
            "available" -> {
                o.closed("generation projectionSequence selection state source meters partial estimated")
                val source = choice(o.required("source"), "controller gps:phone")
                val estimated = o.required("estimated").boolean()
                checkValue((selection == "auto" || selection == source) && estimated == (source == "controller"))
                AvailableDistance(
                    generation,
                    sequence,
                    selection,
                    source,
                    nonnegative(o.required("meters")),
                    o.required("partial").boolean(),
                    estimated,
                )
            }
            else -> {
                o.closed("generation projectionSequence selection state")
                UnavailableDistance(generation, sequence, selection)
            }
        }
    }

    fun display(value: Json): ControllerDisplay {
        val o = value.obj()
        val distance = o["distance"]?.let(::distance)
        val result =
            if (
                o["sourceSequence"] == null &&
                    o["ageSeconds"] == null &&
                    o["humanPowerW"] == null &&
                    o["cadenceRpm"] == null
            ) {
                o.closed("distance")
                ControllerDistanceOnly(distance ?: invalid())
            } else {
                o.closed("sourceSequence ageSeconds", "humanPowerW cadenceRpm distance")
                val power = o["humanPowerW"]?.number()?.also { checkValue(it in 0.0..5000.0) }
                val cadence = o["cadenceRpm"]?.number()?.also { checkValue(it in 0.0..300.0) }
                checkValue(power != null || cadence != null)
                ControllerReading(
                    decimal(o.required("sourceSequence"), true),
                    seconds(o.required("ageSeconds")),
                    power,
                    cadence,
                    distance,
                )
            }
        checkValue(JsonCodec.encode(result.json()).encodeToByteArray().size <= 2048)
        return result
    }
}
