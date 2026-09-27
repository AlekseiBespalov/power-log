package app.powerlog.bridge

import java.time.Instant

internal data class ConnectInput(val deviceId: String, val sampleHz: Int)

internal data class RideOptions(
    val indoor: Boolean,
    val useWatch: Boolean = false,
    val saveToHealth: Boolean = true,
    val recordGPS: Boolean = !indoor,
    val sampleHz: Int = 2,
) {
    fun effective(capabilities: RideCapabilities): RideOptions {
        val watch = capabilities.watchWorkout && useWatch
        return copy(
            useWatch = watch,
            saveToHealth = (if (watch) capabilities.watchHealth else capabilities.phoneHealth) && saveToHealth,
            recordGPS = capabilities.gps && recordGPS,
        )
    }
}

internal data class CatalogCursor(val startedAt: String, val id: String)

internal data class CatalogInput(val limit: Int = 100, val cursor: CatalogCursor? = null)

internal sealed interface MonitorTarget {
    data object Live : MonitorTarget

    data class Workout(val id: String) : MonitorTarget
}

internal enum class MonitorOperation {
    Describe,
    Latest,
    Plot,
    Inspect,
    Stats,
    Changes,
}

internal enum class DistanceSource(val wire: String) {
    Auto("auto"),
    WatchGPS("gps:watch"),
    PhoneGPS("gps:phone"),
    WatchHealth("health:watch"),
    PhoneHealth("health:phone"),
    Controller("controller"),
}

internal data class MonitorAnchor(val metric: String, val rowId: Long)

internal data class MonitorInput(
    val operation: MonitorOperation,
    val target: MonitorTarget,
    val generation: Int,
    val distanceSource: DistanceSource = DistanceSource.Auto,
    val expectedRevision: String? = null,
    val sinceRevision: String? = null,
    val metrics: List<String> = emptyList(),
    val startSeconds: Double = 0.0,
    val endSeconds: Double? = null,
    val seconds: Double? = null,
    val buckets: Int = 128,
    val includeEndpoints: Boolean = false,
    val anchor: MonitorAnchor? = null,
    val startAnchor: MonitorAnchor? = null,
    val endAnchor: MonitorAnchor? = null,
)

internal object BridgeInputs {
    const val MAX_GENERATION = Int.MAX_VALUE
    private val metrics =
        setOf(
            "humanPowerW",
            "cadenceRpm",
            "heartRateBpm",
            "pedalTorqueNm",
            "activeEnergyKcal",
            "basalEnergyKcal",
            "batteryVoltageV",
            "batteryCurrentA",
            "motorInputPowerW",
            "consumedAh",
            "consumedWh",
            "motorCurrentA",
            "motorTempC",
            "controllerTempC",
            "motorRpm",
            "throttleVoltageV",
            "speedMps",
            "healthSpeedMps",
            "controllerSpeedMps",
            "distanceMeters",
            "altitudeMeters",
            "horizontalAccuracyM",
            "verticalAccuracyM",
            "courseDegrees",
            "assistLevel",
            "raceMode",
            "faultCode",
            "speedRaw",
        )

    fun sampleHz(value: Any?, field: String = "sampleHz"): Int {
        val rate = if (value == null) 2.0 else (value as? Number)?.toDouble()
        require(rate != null && rate.isFinite() && rate in listOf(2.0, 4.0, 8.0)) {
            "$field must be 2, 4, or 8 Hz."
        }
        return rate.toInt()
    }

    fun connect(input: Map<String, Any?>) =
        ConnectInput(string(input["deviceId"], "deviceId"), sampleHz(input["hz"], "hz"))

    fun ride(input: Map<String, Any?>): RideOptions {
        val indoor = boolean(input["indoor"], "indoor")
        return RideOptions(
            indoor = indoor,
            useWatch = input["useWatch"]?.let { boolean(it, "useWatch") } ?: false,
            saveToHealth = input["saveToHealth"]?.let { boolean(it, "saveToHealth") } ?: true,
            recordGPS = input["recordGPS"]?.let { boolean(it, "recordGPS") } ?: !indoor,
            sampleHz = sampleHz(input["sampleHz"]),
        )
    }

    fun catalog(input: Map<String, Any?>): CatalogInput {
        val limit = if (input["limit"] != null) integer(input["limit"], "limit", 1, 100) else 100
        val hasStart = input["beforeStartedAt"] != null
        val hasId = input["beforeID"] != null
        require(hasStart == hasId) { "beforeStartedAt and beforeID must be supplied together." }
        val cursor =
            if (hasStart) {
                val start = string(input["beforeStartedAt"], "beforeStartedAt")
                require(runCatching { Instant.parse(start) }.isSuccess) { "beforeStartedAt must be an ISO timestamp." }
                CatalogCursor(start, string(input["beforeID"], "beforeID"))
            } else null
        return CatalogInput(limit, cursor)
    }

    fun distanceSource(value: Any?): DistanceSource =
        DistanceSource.entries.firstOrNull { it.wire == value }
            ?: throw IllegalArgumentException("distanceSource must name a supported distance source.")

    fun monitor(operation: MonitorOperation, input: Map<String, Any?>): MonitorInput {
        val target =
            when (input["source"]) {
                "live" -> MonitorTarget.Live
                "workout" -> MonitorTarget.Workout(string(input["id"], "id"))
                else -> throw IllegalArgumentException("source must be live or workout.")
            }
        if (input["id"] != null) string(input["id"], "id")
        val generation = integer(input["generation"], "generation", 0, MAX_GENERATION)
        val source =
            if (input["distanceSource"] != null) distanceSource(input["distanceSource"]) else DistanceSource.Auto
        val requestedMetrics =
            if (input["metrics"] != null) {
                val list = input["metrics"] as? List<*> ?: throw IllegalArgumentException("metrics must be a list.")
                require(list.size <= 32) { "metrics must contain at most 32 members." }
                list.mapIndexed { index, value ->
                    require(value is String && value in metrics) { "metrics[$index] must name a supported metric." }
                    value
                }
            } else {
                require(operation in listOf(MonitorOperation.Describe, MonitorOperation.Changes)) {
                    "metrics is required."
                }
                emptyList()
            }
        val expected = optional(input, "expectedRevision", ::revision)
        val since = optional(input, "sinceRevision", ::revision)
        if (operation in listOf(MonitorOperation.Plot, MonitorOperation.Inspect, MonitorOperation.Stats))
            require(expected != null) { "expectedRevision is required." }
        if (operation == MonitorOperation.Changes) require(since != null) { "sinceRevision is required." }
        val start = optional(input, "startSeconds", ::nonnegative)
        val end = optional(input, "endSeconds", ::nonnegative)
        if (operation == MonitorOperation.Stats) {
            require(start != null) { "startSeconds is required." }
            require(end != null) { "endSeconds is required." }
        }
        require(end == null || end >= (start ?: 0.0)) { "endSeconds must be at least startSeconds." }
        val seconds = optional(input, "seconds", ::nonnegative)
        if (operation == MonitorOperation.Inspect) require(seconds != null) { "seconds is required." }
        val buckets = if (input["buckets"] != null) integer(input["buckets"], "buckets", 1, 512) else 128
        optional(input, "pixelWidth", ::nonnegative)
        val endpoints = optional(input, "includeEndpoints", ::boolean) ?: false
        fun anchor(field: String, time: Double?): MonitorAnchor? {
            if (input[field] == null) return null
            val value = input[field] as? Map<*, *> ?: throw IllegalArgumentException("$field must be an anchor.")
            require(time != null) { "$field requires its time." }
            val metric = string(value["metric"], "$field.metric")
            require(metric in requestedMetrics) { "$field.metric must belong to metrics." }
            val id = string(value["observationId"], "$field.observationId")
            val row = id.toLongOrNull()
            require(id.all { it in '0'..'9' } && row != null && row > 0) {
                "$field.observationId must be a positive 64-bit integer string."
            }
            return MonitorAnchor(metric, row)
        }
        return MonitorInput(
            operation,
            target,
            generation,
            source,
            expected,
            since,
            requestedMetrics,
            start ?: 0.0,
            end,
            seconds,
            buckets,
            endpoints,
            anchor("anchor", seconds),
            anchor("startAnchor", start),
            anchor("endAnchor", end),
        )
    }

    private fun string(value: Any?, field: String): String {
        require(value is String && value.isNotBlank() && value.none { it.isISOControl() }) {
            "$field must be a nonempty string."
        }
        return value
    }

    private fun boolean(value: Any?, field: String): Boolean {
        require(value is Boolean) { "$field must be a boolean." }
        return value
    }

    private fun nonnegative(value: Any?, field: String): Double {
        val number = (value as? Number)?.toDouble()
        require(number != null && number.isFinite() && number >= 0) { "$field must be finite and nonnegative." }
        return number
    }

    private fun integer(value: Any?, field: String, minimum: Int, maximum: Int): Int {
        val number = nonnegative(value, field)
        require(number >= minimum && number <= maximum && number % 1.0 == 0.0) {
            "$field must be an integer in $minimum..$maximum."
        }
        return number.toInt()
    }

    private fun revision(value: Any?, field: String): String {
        val text = string(value, field)
        val number = text.toLongOrNull()
        require(text.all { it in '0'..'9' } && number != null && number >= 0) {
            "$field must be a nonnegative revision string."
        }
        return text
    }

    private fun <T> optional(input: Map<String, Any?>, field: String, parse: (Any?, String) -> T): T? =
        input[field]?.let { parse(it, field) }
}
