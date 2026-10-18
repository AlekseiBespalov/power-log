package app.powerlog.core.presentation

object SampleRates {
    val admitted: List<Int>
        get() = listOf(2, 4, 8)

    const val defaultHz = 2

    fun accepts(value: Double?): Boolean = value != null && value.isFinite() && admitted.any { it.toDouble() == value }

    @Throws(IllegalArgumentException::class)
    fun require(value: Double?): Int {
        require(accepts(value)) { "Sample rate must be 2, 4, or 8 Hz" }
        return value!!.toInt()
    }
}

data class RideCapabilities(
    val phoneWorkout: Boolean,
    val watchWorkout: Boolean,
    val phoneHealth: Boolean,
    val watchHealth: Boolean,
    val gps: Boolean,
    val healthProvider: String?,
    val foregroundOnly: Boolean = false,
)

object PlatformCapabilities {
    fun iphone(healthAvailable: Boolean) =
        RideCapabilities(
            true,
            healthAvailable,
            healthAvailable,
            healthAvailable,
            true,
            if (healthAvailable) "appleHealth" else null,
        )

    fun appleWatch() = RideCapabilities(true, true, true, true, true, "appleHealth")

    fun android(healthAvailable: Boolean) =
        RideCapabilities(true, false, healthAvailable, false, true, if (healthAvailable) "healthConnect" else null)
}

data class RequestedRideOptions(
    val indoor: Boolean = false,
    val useWatch: Boolean? = null,
    val saveToHealth: Boolean? = null,
    val recordGPS: Boolean? = null,
    val sampleHz: Double? = null,
    val distanceSource: String = "auto",
    val batteryConsumptionSource: String = "controller",
) {
    constructor(
        indoor: Boolean,
        useWatch: Boolean?,
        saveToHealth: Boolean?,
        recordGPS: Boolean?,
        sampleHz: Double?,
    ) : this(indoor, useWatch, saveToHealth, recordGPS, sampleHz, "auto")
}

data class FrozenRideOptions
@Throws(IllegalArgumentException::class)
constructor(
    val indoor: Boolean,
    val useWatch: Boolean,
    val saveToHealth: Boolean,
    val recordGPS: Boolean,
    val sampleHz: Int,
    val distanceSource: String = if (recordGPS && !indoor) "gps:phone" else "controller",
    val batteryConsumptionSource: String = "controller",
) {
    constructor(
        indoor: Boolean,
        useWatch: Boolean,
        saveToHealth: Boolean,
        recordGPS: Boolean,
        sampleHz: Int,
    ) : this(
        indoor,
        useWatch,
        saveToHealth,
        recordGPS,
        sampleHz,
        if (recordGPS && !indoor) "gps:phone" else "controller",
    )

    init {
        require(app.powerlog.core.analytics.BatteryConsumptionPolicy.validSource(batteryConsumptionSource))
        SampleRates.require(sampleHz.toDouble())
        require(distanceSource in listOf("gps:phone", "controller"))
    }

    val recordingOwner: String
        get() = "phone"

    val healthWriter: String?
        get() = if (!saveToHealth) null else if (useWatch) "watch" else "phone"
}

object RideOptions {
    @Throws(IllegalArgumentException::class)
    fun resolve(requested: RequestedRideOptions, capabilities: RideCapabilities): FrozenRideOptions {
        val watch = capabilities.watchWorkout && requested.useWatch == true
        return FrozenRideOptions(
            requested.indoor,
            watch,
            (if (watch) capabilities.watchHealth else capabilities.phoneHealth) && requested.saveToHealth != false,
            capabilities.gps && (requested.recordGPS ?: !requested.indoor),
            SampleRates.require(requested.sampleHz ?: SampleRates.defaultHz.toDouble()),
            app.powerlog.core.analytics.DistancePolicy.resolveAtStart(
                requested.distanceSource,
                capabilities.gps && (requested.recordGPS ?: !requested.indoor),
                requested.indoor,
            ),
            requested.batteryConsumptionSource,
        )
    }

    @Throws(IllegalArgumentException::class)
    fun restore(
        indoor: Boolean,
        useWatch: Boolean,
        saveToHealth: Boolean,
        recordGPS: Boolean,
        sampleHz: Double?,
        distanceSource: String = if (recordGPS && !indoor) "gps:phone" else "controller",
        batteryConsumptionSource: String = "controller",
    ): FrozenRideOptions =
        FrozenRideOptions(
            indoor,
            useWatch,
            saveToHealth,
            recordGPS,
            SampleRates.require(sampleHz),
            distanceSource,
            batteryConsumptionSource,
        )

    @Throws(IllegalArgumentException::class)
    fun restore(
        indoor: Boolean,
        useWatch: Boolean,
        saveToHealth: Boolean,
        recordGPS: Boolean,
        sampleHz: Double?,
    ): FrozenRideOptions =
        restore(
            indoor,
            useWatch,
            saveToHealth,
            recordGPS,
            sampleHz,
            if (recordGPS && !indoor) "gps:phone" else "controller",
        )

    fun readiness(
        options: FrozenRideOptions,
        capabilities: RideCapabilities,
        bikeReady: Boolean,
        watchInstalled: Boolean,
        phase: String? = null,
        startContext: RideStartContext = RideStartContext(),
        error: String? = null,
    ): StartReadiness {
        val startRejection = RideStarts.rejection(phase, startContext)
        val reason =
            when {
                error != null -> error
                startRejection != null -> startRejection
                !bikeReady || (!capabilities.phoneWorkout && !options.useWatch) -> RideFailure.bikeRequired.name
                options.useWatch && !watchInstalled -> RideFailure.watchNotInstalled.name
                else -> null
            }
        return StartReadiness(reason == null, reason)
    }
}

data class StartReadiness(val available: Boolean, val error: String?)

data class PermissionStatus(
    val healthAvailable: Boolean,
    val healthProvider: String?,
    val requiredWrites: List<String>?,
    val writeAuthorization: Map<String, String>,
    val location: String,
    val locationServicesEnabled: Boolean,
    val locationAccuracy: String,
)

data class PermissionAction(
    val action: String,
    val label: String,
    val detail: String,
    val settingsTarget: String? = null,
)

object SetupPresentation {
    private val commonWrites =
        listOf(
            "HKWorkoutTypeIdentifier",
            "HKQuantityTypeIdentifierCyclingPower",
            "HKQuantityTypeIdentifierCyclingCadence",
            "HKQuantityTypeIdentifierActiveEnergyBurned",
            "HKQuantityTypeIdentifierBasalEnergyBurned",
            "HKQuantityTypeIdentifierHeartRate",
        )
    private val outdoorWrites =
        listOf(
            "HKWorkoutRouteTypeIdentifier",
            "HKQuantityTypeIdentifierCyclingSpeed",
            "HKQuantityTypeIdentifierDistanceCycling",
        )
    private val connectCommon =
        listOf("android.permission.health.WRITE_POWER", "android.permission.health.WRITE_DISTANCE")
    private val connectOutdoor =
        listOf("android.permission.health.WRITE_SPEED", "android.permission.health.WRITE_EXERCISE_ROUTE")

    fun permission(status: PermissionStatus?, options: RequestedRideOptions): PermissionAction {
        val healthRequired = options.useWatch != true && options.saveToHealth != false
        val phoneGPS = options.recordGPS ?: !options.indoor
        val none = PermissionAction("none", "", "")
        if (!healthRequired && !phoneGPS) return none
        if (status == null)
            return PermissionAction("check", "Check permissions", "Current permission status is not available yet.")
        if (healthRequired && !status.healthAvailable)
            return PermissionAction("unavailable", "Health unavailable", "Health saving is unavailable on this device.")
        val required =
            if (healthRequired) status.requiredWrites ?: (commonWrites + if (phoneGPS) outdoorWrites else emptyList())
            else emptyList()
        val writes = required.map { status.writeAuthorization[it] ?: "notDetermined" }
        val connectMissing =
            healthRequired &&
                status.healthProvider == "healthConnect" &&
                (required + connectCommon + if (phoneGPS) connectOutdoor else emptyList()).any {
                    status.writeAuthorization[it] != "authorized"
                }
        if ("notDetermined" in writes || connectMissing)
            return PermissionAction(
                "request",
                "Set up permissions",
                "Review the health permissions requested by Power Log.",
            )
        if (phoneGPS && status.location == "notDetermined")
            return PermissionAction(
                "request",
                "Allow location",
                "Your phone needs location access to record GPS measurements.",
            )
        if ("denied" in writes)
            return PermissionAction(
                "settings",
                "Review Health access",
                "Some write access is denied. Review Power Log’s permissions in ${PresentationFormatting.provider(status.healthProvider ?: "appleHealth")}.",
                "health",
            )
        if (phoneGPS && (!status.locationServicesEnabled || status.location in setOf("denied", "restricted"))) {
            val detail =
                when {
                    !status.locationServicesEnabled -> "Turn on Location Services to record the phone route."
                    status.location == "restricted" -> "Location access is restricted by device settings."
                    else -> "Allow location access for Power Log in Settings."
                }
            return PermissionAction("settings", "Location settings", detail, "app")
        }
        if (phoneGPS && status.locationAccuracy == "reduced")
            return PermissionAction(
                "settings",
                "Precise location",
                "Enable Precise Location for a useful cycling route.",
                "app",
            )
        if (
            writes.any { it != "authorized" } ||
                (phoneGPS &&
                    (status.location !in setOf("authorizedAlways", "authorizedWhenInUse") ||
                        status.locationAccuracy == "unknown"))
        )
            return PermissionAction(
                "check",
                "Check permissions",
                "Current permission readiness could not be determined.",
            )
        return none
    }

    @Throws(IllegalArgumentException::class)
    fun health(requested: RequestedRideOptions, capabilities: RideCapabilities): SourcePresentation {
        val effective = RideOptions.resolve(requested, capabilities)
        val provider = PresentationFormatting.provider(capabilities.healthProvider)
        val unavailable =
            requested.saveToHealth != false &&
                !effective.saveToHealth &&
                (capabilities.phoneWorkout || capabilities.watchWorkout)
        return SourcePresentation(
            provider,
            if (unavailable)
                "${if (effective.useWatch) "Watch" else "Phone"}-recorded rides cannot be saved to $provider on this device. This ride will stay in Power Log."
            else "",
            "neutral",
        )
    }
}
