package app.powerlog.core.health

import app.powerlog.core.analytics.Continuity
import app.powerlog.core.analytics.Quantity
import app.powerlog.core.analytics.Scalar

enum class HealthKind(val wire: String, val unit: String) {
    riderPower("rider-power", "W"),
    cyclingCadence("cycling-cadence", "RPM"),
    cyclingSpeed("cycling-speed", "m/s"),
    cyclingDistance("cycling-distance", "m"),
}

enum class Component(val wire: String) {
    workout("workout"),
    route("route"),
    laps("laps"),
    riderPower("rider-power"),
    cyclingCadence("cycling-cadence"),
    cyclingSpeed("cycling-speed"),
    cyclingDistance("cycling-distance"),
    association("association"),
    readback("readback"),
}

enum class EndpointRole {
    phone,
    watch,
}

enum class Device {
    controller,
    iphone,
    appleWatch,
    androidPhone,
    wearWatch,
    browser,
}

enum class Provider {
    appleHealth,
    healthConnect,
}

enum class Source {
    controller,
    phone,
    watch,
}

enum class Absence {
    noValidReading,
    noCoverage,
    inactive,
}

enum class RecordOperation {
    upsert,
    delete,
}

enum class RecordState {
    notSubmitted,
    builderAccepted,
    outcomeUnknown,
    saved,
    associationPending,
    settled,
    withdrawn,
    blocked,
    failed,
    fenced,
}

enum class ComponentState {
    notRequested,
    pending,
    saved,
    unavailable,
    failed,
    fenced,
}

enum class HealthReason {
    permission,
    locked,
    quota,
    invalid,
    fixedSize,
    integrityConflict,
    ambiguous,
    outcomeAbsent,
    providerDeleted,
    localHistory,
    discard,
    closedSource,
    builderLost,
    readbackUnavailable,
    nativeOwnerLost,
    numericOverflow,
    noValidReading,
    noCoverage,
    inactive,
    transientFailure,
    missingSource,
    clock,
}

enum class ProjectionFailure {
    sizeUnknown,
    minimumRecordTooLarge,
    unrepresentableTime,
    invalidEncoding,
}

data class ScanCursor(val component: Component, val syncId: String, val position: Long)

data class SecondReading(
    val origin: Origin,
    val originalUTC: String,
    val utcUs: Long,
    val collectionUs: Long,
    val source: MeasurementSource,
    val activeInterval: Int?,
    val continuity: Continuity,
    val originalScalar: Scalar?,
    val admittedQ: Long?,
    val barrier: Boolean,
    val horizontalAccuracyM: Double? = null,
    val speedAccuracyMps: Double? = null,
)

data class SecondValue(
    val n: Long,
    val startUs: Long,
    val endUs: Long,
    val validOriginalCount: Long,
    val integralQUs: Long,
    val coveredUs: Long,
    val value: Double,
    val source: MeasurementSource,
)

sealed class SecondOutcome {
    data class Sample(val sample: SecondValue) : SecondOutcome()

    data class Absent(val reason: Absence) : SecondOutcome()

    data class Invalid(val reason: HealthReason) : SecondOutcome()

    data class NumericOverflow(val metric: HealthKind, val quantity: Quantity) : SecondOutcome()
}

sealed class RouteIntent {
    data object NotRequested : RouteIntent()

    data object Preserve : RouteIntent()

    data class Replace(val projectedOriginKeys: List<String>) : RouteIntent()

    data object Remove : RouteIntent()
}

sealed class RouteProjection {
    data class Full(val ids: List<String>, val encodedBytes: Long) : RouteProjection()

    data class Reduced(
        val ids: List<String>,
        val originalCount: Long,
        val eligibleCount: Long,
        val encodedBytes: Long,
    ) : RouteProjection()

    data class Unavailable(val reason: ProjectionFailure) : RouteProjection()
}

data class PublicationMetadata(
    val rideId: String,
    val recordingOwner: EndpointRole,
    val ownerEndpointId: String,
    val healthPublisher: EndpointRole,
    val publisherEndpointId: String,
    val indoor: Boolean,
    val recordGPS: Boolean,
)

data class QuantityContent(
    val kind: HealthKind,
    val startUs: Long,
    val endUs: Long,
    val value: Double,
    val unit: String,
    val source: MeasurementSource,
    val metadata: PublicationMetadata,
)

data class BoundaryContent(val firstOrigin: String, val lastOrigin: String, val startUs: Long, val endUs: Long)

data class RoutePoint(
    val originKey: String,
    val timeUs: Long,
    val latitude: Double,
    val longitude: Double,
    val horizontalAccuracyM: Double?,
    val verticalAccuracyM: Double?,
    val altitudeMeters: Double?,
)

data class SessionContent(
    val startUs: Long,
    val endUs: Long,
    val metadata: PublicationMetadata,
    val laps: List<BoundaryContent>,
    val pauses: List<BoundaryContent>,
    val route: List<RoutePoint>,
)

sealed class ProviderContent {
    data class Quantity(val fields: QuantityContent) : ProviderContent()

    data class Session(val fields: SessionContent) : ProviderContent()

    data class Route(val points: List<RoutePoint>, val metadata: PublicationMetadata) : ProviderContent()

    data class Lap(val eventId: String, val timestamp: String, val metadata: PublicationMetadata) : ProviderContent()
}

sealed class AdapterProjection {
    data class Apple(val content: ProviderContent) : AdapterProjection()

    data class HealthConnect(
        val content: ProviderContent,
        val startZoneOffsetSeconds: Int,
        val endZoneOffsetSeconds: Int,
        val routeIntent: RouteIntent,
        val encodedBytes: Long,
        val encoding: RouteEncoding,
    ) : AdapterProjection()
}

sealed class NormalizedContent {
    data class Present(val fields: ProviderContent, val projection: AdapterProjection) : NormalizedContent()

    data class Absent(val reason: Absence) : NormalizedContent()
}

data class RecordPlan(
    val syncId: String,
    val component: Component,
    val operation: RecordOperation,
    val content: NormalizedContent,
    val sourceRevision: Long,
    val routeIntent: RouteIntent?,
)

data class PublicationRecord(
    val syncId: String,
    val component: Component,
    val operation: RecordOperation,
    val version: Long,
    val frozenContent: NormalizedContent,
    val contentDigest: String,
    val sourceRevision: Long,
    val providerIds: List<String>,
    val state: RecordState,
    val associatedWorkoutId: String?,
    val retryAtUs: Long?,
    val attempts: Int,
    val routeIntent: RouteIntent? = null,
    val reason: HealthReason? = null,
)

data class ComponentResult(
    val component: Component,
    val sourceRevision: Long,
    val required: Boolean,
    val state: ComponentState,
    val reason: HealthReason?,
    val providerIds: List<String>,
)

data class ProviderParent(
    val provider: Provider,
    val dataOrigin: String,
    val providerId: String,
    val metric: String,
    val originalStart: String,
    val originalEnd: String,
    val nativeRevision: String?,
    val snapshotDigest: String,
    val memberCount: Long,
)

data class ProviderMember(
    val parent: ProviderParent,
    val canonicalIndex: Long,
    val occurrenceIndex: Long,
    val originalStart: String,
    val originalEnd: String,
    val valueBits: String,
    val unit: String,
    val logicalKey: String,
    val eventId: String,
)
