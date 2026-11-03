package app.powerlog.core.presentation

import app.powerlog.core.protocol.*
import app.powerlog.core.telemetry.MetricCatalog

enum class MonitorPresetId {
    ride,
    battery,
    temperature,
}

enum class MonitorRange {
    seconds30,
    seconds120,
    seconds600,
    all,
}

class MonitorPreset
@Throws(IllegalArgumentException::class)
constructor(
    numbers: List<String>,
    charts: List<String>,
    val range: MonitorRange,
    val webColumns: Int,
) {
    private val retainedNumbers = numbers.toList()
    private val retainedCharts = charts.toList()
    val numbers: List<String>
        get() = retainedNumbers.toList()

    val charts: List<String>
        get() = retainedCharts.toList()

    init {
        require(webColumns in 1..3)
        for (selection in listOf(retainedNumbers, retainedCharts)) {
            require(selection.distinct().size == selection.size)
            selection.forEach {
                require(it != "speedRaw")
                MetricCatalog.metric(it)
            }
        }
    }
}

class MonitorPreferences
@Throws(IllegalArgumentException::class)
constructor(
    val activeView: MonitorPresetId,
    val historyView: MonitorPresetId?,
    val historyRange: MonitorRange,
    val speedUnit: String,
    val distanceSource: String,
    val sampleHz: Int,
    val workoutOptions: RequestedRideOptions,
    views: Map<MonitorPresetId, MonitorPreset>,
    val batteryConsumptionSource: String = "controller",
    val fitPowerSource: String = "rider",
) {
    private val retainedViews = views.toMap()
    val views: Map<MonitorPresetId, MonitorPreset>
        get() = retainedViews.toMap()

    init {
        require(retainedViews.keys == MonitorPresetId.entries.toSet())
        require(MetricCatalog.acceptsSpeedUnit(speedUnit))
        require(app.powerlog.core.analytics.DistancePolicy.validSelection(distanceSource))
        require(batteryConsumptionSource in listOf("controller", "calculated"))
        require(fitPowerSource in listOf("rider", "battery"))
        SampleRates.require(sampleHz.toDouble())
        require(
            workoutOptions.useWatch != null && workoutOptions.saveToHealth != null && workoutOptions.sampleHz == null
        )
    }

    @Throws(IllegalArgumentException::class)
    fun withPreset(id: MonitorPresetId, preset: MonitorPreset): MonitorPreferences =
        MonitorPreferences(
            activeView,
            historyView,
            historyRange,
            speedUnit,
            distanceSource,
            sampleHz,
            workoutOptions,
            retainedViews + (id to preset),
            batteryConsumptionSource,
            fitPowerSource,
        )

    @Throws(IllegalArgumentException::class)
    fun withSelection(
        live: MonitorPresetId,
        history: MonitorPresetId?,
        range: MonitorRange,
    ): MonitorPreferences =
        MonitorPreferences(
            live,
            history,
            range,
            speedUnit,
            distanceSource,
            sampleHz,
            workoutOptions,
            retainedViews,
            batteryConsumptionSource,
            fitPowerSource,
        )

    @Throws(IllegalArgumentException::class)
    fun withOptions(
        options: RequestedRideOptions,
        sampleHz: Int = this.sampleHz,
    ): MonitorPreferences =
        MonitorPreferences(
            activeView,
            historyView,
            historyRange,
            speedUnit,
            distanceSource,
            sampleHz,
            options,
            retainedViews,
            batteryConsumptionSource,
            fitPowerSource,
        )

    @Throws(IllegalArgumentException::class)
    fun withBatteryConsumptionSource(source: String): MonitorPreferences =
        MonitorPreferences(
            activeView,
            historyView,
            historyRange,
            speedUnit,
            distanceSource,
            sampleHz,
            workoutOptions,
            retainedViews,
            source,
            fitPowerSource,
        )

    @Throws(IllegalArgumentException::class)
    fun withFitPowerSource(source: String): MonitorPreferences =
        MonitorPreferences(
            activeView,
            historyView,
            historyRange,
            speedUnit,
            distanceSource,
            sampleHz,
            workoutOptions,
            retainedViews,
            batteryConsumptionSource,
            source,
        )

    fun startOptions(): RequestedRideOptions =
        workoutOptions.copy(
            sampleHz = sampleHz.toDouble(),
            distanceSource = distanceSource,
            batteryConsumptionSource = batteryConsumptionSource,
        )
}

data class PreferenceLoadDecision(
    val preferences: MonitorPreferences,
    val error: String?,
    val writable: Boolean,
    val shouldSave: Boolean = false,
) {
    val resetOffered: Boolean
        get() = !writable
}

object Preferences {
    fun presetLabel(id: MonitorPresetId): String =
        when (id) {
            MonitorPresetId.ride -> "Ride"
            MonitorPresetId.battery -> "Battery"
            MonitorPresetId.temperature -> "Temperature"
        }

    fun defaults(): MonitorPreferences =
        MonitorPreferences(
            MonitorPresetId.ride,
            null,
            MonitorRange.all,
            "km/h",
            "auto",
            SampleRates.defaultHz,
            RequestedRideOptions(false, false, true),
            mapOf(
                MonitorPresetId.ride to
                    MonitorPreset(
                        listOf(
                            "humanPowerW",
                            "cadenceRpm",
                            "motorInputPowerW",
                            "controllerSpeedMps",
                        ),
                        listOf("humanPowerW", "motorInputPowerW", "cadenceRpm"),
                        MonitorRange.seconds120,
                        2,
                    ),
                MonitorPresetId.battery to
                    MonitorPreset(
                        listOf("batteryVoltageV", "batteryCurrentA", "motorInputPowerW"),
                        listOf("batteryVoltageV", "batteryCurrentA", "motorInputPowerW"),
                        MonitorRange.seconds120,
                        2,
                    ),
                MonitorPresetId.temperature to
                    MonitorPreset(
                        listOf("motorTempC", "controllerTempC", "motorInputPowerW"),
                        listOf("motorTempC", "controllerTempC", "motorInputPowerW"),
                        MonitorRange.seconds600,
                        2,
                    ),
            ),
        )

    fun watchDefaults(): RequestedRideOptions =
        RequestedRideOptions(false, true, true, null, SampleRates.defaultHz.toDouble())

    @Throws(IllegalArgumentException::class)
    fun decode(text: String): MonitorPreferences =
        try {
            val root = JsonCodec.parse(text, arrayLimit = 64, byteLimit = 65536, wireNumbers = false).obj()
            root.closed(
                "version activeView historyRange speedUnit distanceSource batteryConsumptionSource sampleHz workoutOptions views",
                "historyView fitPowerSource",
            )
            checkValue(root.required("version").number() == 1.0)
            val options = root.required("workoutOptions").obj()
            options.closed("indoor useWatch saveToHealth", "recordGPS")
            val views = root.required("views").obj()
            views.closed("ride battery temperature")
            val presets =
                MonitorPresetId.entries.associateWith { id ->
                    val preset = views.required(id.name).obj()
                    preset.closed("numbers charts range webColumns")
                    MonitorPreset(
                        selectable(preset.required("numbers")),
                        selectable(preset.required("charts")),
                        range(preset.required("range")),
                        integer(preset.required("webColumns"), 1, 3),
                    )
                }
            MonitorPreferences(
                MonitorPresetId.valueOf(root.required("activeView").string()),
                root["historyView"]?.let { MonitorPresetId.valueOf(it.string()) },
                range(root.required("historyRange")),
                root.required("speedUnit").string(),
                root
                    .required("distanceSource")
                    .string()
                    .takeIf(app.powerlog.core.analytics.DistancePolicy::validSelection) ?: "auto",
                SampleRates.require(root.required("sampleHz").number()),
                RequestedRideOptions(
                    options.required("indoor").boolean(),
                    options.required("useWatch").boolean(),
                    options.required("saveToHealth").boolean(),
                    options["recordGPS"]?.boolean(),
                ),
                presets,
                root.required("batteryConsumptionSource").string(),
                root["fitPowerSource"]?.string() ?: "rider",
            )
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid saved monitor settings.")
        }

    fun encode(preferences: MonitorPreferences): String {
        val options = preferences.workoutOptions
        val fields =
            linkedMapOf<String, Json>(
                "version" to Num(1.0),
                "activeView" to Str(preferences.activeView.name),
                "historyRange" to rangeJson(preferences.historyRange),
                "speedUnit" to Str(preferences.speedUnit),
                "distanceSource" to Str(preferences.distanceSource),
                "batteryConsumptionSource" to Str(preferences.batteryConsumptionSource),
                "fitPowerSource" to Str(preferences.fitPowerSource),
                "sampleHz" to Num(preferences.sampleHz.toDouble()),
                "workoutOptions" to
                    Obj(
                        linkedMapOf<String, Json>(
                                "indoor" to Bool(options.indoor),
                                "useWatch" to Bool(options.useWatch!!),
                                "saveToHealth" to Bool(options.saveToHealth!!),
                            )
                            .apply { options.recordGPS?.let { put("recordGPS", Bool(it)) } }
                    ),
                "views" to
                    Obj(
                        preferences.views
                            .mapKeys { it.key.name }
                            .mapValues { (_, value) ->
                                Obj(
                                    mapOf(
                                        "numbers" to Arr(value.numbers.map(::Str)),
                                        "charts" to Arr(value.charts.map(::Str)),
                                        "range" to rangeJson(value.range),
                                        "webColumns" to Num(value.webColumns.toDouble()),
                                    )
                                )
                            }
                    ),
            )
        preferences.historyView?.let { fields["historyView"] = Str(it.name) }
        return JsonCodec.encode(Obj(fields))
    }

    fun load(
        previous: MonitorPreferences,
        text: String?,
        loadError: String? = null,
    ): PreferenceLoadDecision {
        if (loadError != null) return failedLoad(previous)
        return try {
            PreferenceLoadDecision(if (text == null) defaults() else decode(text), null, true)
        } catch (_: IllegalArgumentException) {
            failedLoad(previous)
        }
    }

    fun change(
        state: PreferenceLoadDecision,
        replacement: MonitorPreferences,
    ): PreferenceLoadDecision = state.copy(preferences = replacement, shouldSave = state.writable)

    fun reset(): PreferenceLoadDecision = PreferenceLoadDecision(defaults(), null, writable = true, shouldSave = true)

    private fun failedLoad(previous: MonitorPreferences): PreferenceLoadDecision =
        PreferenceLoadDecision(
            previous,
            "Power Log couldn't read your saved settings. Reset settings to use the defaults.",
            false,
        )

    private fun selectable(value: Json): List<String> =
        value.array().map { it.string() }.filter { id -> id != "speedRaw" && MetricCatalog.all.any { it.id == id } }

    private fun range(value: Json): MonitorRange =
        when (value) {
            is Str -> {
                checkValue(value.value == "all")
                MonitorRange.all
            }
            is Num ->
                when (value.value) {
                    30.0 -> MonitorRange.seconds30
                    120.0 -> MonitorRange.seconds120
                    600.0 -> MonitorRange.seconds600
                    else -> invalid()
                }
            else -> invalid()
        }

    private fun rangeJson(value: MonitorRange): Json =
        when (value) {
            MonitorRange.all -> Str("all")
            MonitorRange.seconds30 -> Num(30.0)
            MonitorRange.seconds120 -> Num(120.0)
            MonitorRange.seconds600 -> Num(600.0)
        }
}
