package app.powerlog.core.analytics

import app.powerlog.core.protocol.*

object SummaryWire {
    fun fields(summary: Summary, lifecycleCount: Long): Map<String, Any?> {
        val s = summary
        fun profile(p: DistanceProfile): Map<String, Any?> =
            mapOf(
                "source" to p.source,
                "label" to p.label,
                "estimated" to p.estimated,
                "partial" to p.partial,
                "coveredSeconds" to p.coveredSeconds,
                "uncoveredSeconds" to p.uncoveredSeconds,
                "distanceMeters" to p.distanceMeters,
            )
        val optional =
            listOf(
                    "distanceMeters" to s.distanceMeters,
                    "gpsDistanceMeters" to s.gpsDistanceMeters,
                    "averageSpeedMps" to s.averageSpeedMps,
                    "maximumSpeedMps" to s.maximumSpeedMps,
                    "ascentMeters" to s.ascentMeters,
                    "descentMeters" to s.descentMeters,
                    "averageHeartRateBpm" to s.averageHeartRateBpm,
                    "maximumHeartRateBpm" to s.maximumHeartRateBpm,
                    "averageRiderPowerW" to s.averageRiderPowerW,
                    "maximumRiderPowerW" to s.maximumRiderPowerW,
                    "averageCadenceRpm" to s.averageCadenceRpm,
                    "maximumCadenceRpm" to s.maximumCadenceRpm,
                    "activeEnergyKcal" to s.activeEnergyKcal,
                    "basalEnergyKcal" to s.basalEnergyKcal,
                    "riderWorkJoules" to s.riderWorkJoules,
                )
                .filter { it.second != null }
        return mapOf(
            "schemaVersion" to s.schemaVersion,
            "id" to s.id,
            "startedAt" to s.startedAt,
            "endedAt" to s.endedAt,
            "elapsedSeconds" to s.elapsedSeconds,
            "timerSeconds" to s.timerSeconds,
            "distance" to
                mapOf(
                    "selection" to s.distance.selection,
                    "selected" to s.distance.selected?.let(::profile),
                    "available" to s.distance.available.map(::profile),
                ),
            "telemetryCoveredSeconds" to s.telemetryCoveredSeconds,
            "heartRateCoveredSeconds" to s.heartRateCoveredSeconds,
            "eventCount" to s.eventCount.toString(),
            "telemetryCount" to s.telemetryCount.toString(),
            "locationCount" to s.locationCount.toString(),
            "healthCount" to s.healthCount.toString(),
            "lifecycleCount" to lifecycleCount.toString(),
            "lapCount" to s.lapCount,
            "routePreview" to
                s.routePreview.map {
                    mapOf(
                        "latitude" to it.latitude,
                        "longitude" to it.longitude,
                        "elapsedSeconds" to it.elapsedSeconds,
                        "segment" to it.segment,
                        "startsSegment" to it.startsSegment,
                    )
                },
            "provenance" to s.provenance,
            "completeness" to
                mapOf(
                    "distance" to s.completeness.distance,
                    "riderPower" to s.completeness.riderPower,
                    "heartRate" to s.completeness.heartRate,
                    "route" to s.completeness.route,
                ),
        ) + optional
    }

    @Throws(ProtocolException::class)
    fun encode(summary: Summary, lifecycleCount: Long): String = buildString { write(fields(summary, lifecycleCount)) }

    private fun StringBuilder.write(value: Any?) {
        when (value) {
            null -> append("null")
            is String -> append(JsonCodec.encode(Str(value)))
            is Double -> append(if (value.toBits() == (-0.0).toBits()) "-0" else JsonCodec.encode(Num(value)))
            is Boolean,
            is Int -> append(value)
            is Map<*, *> -> {
                append('{')
                value.entries.forEachIndexed { index, (key, item) ->
                    if (index > 0) append(',')
                    write(key as String)
                    append(':')
                    write(item)
                }
                append('}')
            }
            is List<*> -> {
                append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) append(',')
                    write(item)
                }
                append(']')
            }
            else -> throw IllegalArgumentException("Unsupported summary value")
        }
    }
}
