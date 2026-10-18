package app.powerlog.core.health

import app.powerlog.core.analytics.*
import app.powerlog.core.protocol.*

internal object HealthValidation {
    private val originPackagePattern = Regex("[A-Za-z0-9_]+(?:[.][A-Za-z0-9_-]+)+")

    private fun revision(n: Long) {
        require(n in 1..9007199254740991L)
    }

    private fun uuid(s: String) {
        require(HealthIds.uuid(s) == s)
    }

    private fun text(s: String) {
        require(s.isNotEmpty() && s.encodeToByteArray().size <= 1024)
    }

    private fun hash(s: String) {
        require(s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' })
    }

    private fun originPackage(s: String) {
        require(s.length <= 255 && originPackagePattern.matches(s))
    }

    private fun bounds(a: Long, b: Long) {
        require(b > a)
        FixedPoint.subtract(b, a)
    }

    private fun bits(s: String) {
        require(
            s.length == 16 &&
                s.all { it in '0'..'9' || it in 'a'..'f' } &&
                Double.fromBits(s.toULong(16).toLong()).isFinite()
        )
    }

    private fun publication(syncId: String, component: Component, content: NormalizedContent) {
        val parts = HealthIds.syncId(syncId).split(':')
        require(parts[2] == component.wire || component == Component.laps && parts[2] == "lap")
        if (content is NormalizedContent.Present)
            when (val fields = content.fields) {
                is ProviderContent.Quantity -> {
                    require(fields.fields.kind.wire == component.wire)
                    if (fields.fields.kind == HealthKind.cyclingDistance) require(fields.fields.value > 0)
                    else {
                        val n = parts[4].toLong()
                        require(n >= 0 && fields.fields.endUs - fields.fields.startUs <= 1000000)
                    }
                }
                is ProviderContent.Session -> require(component == Component.workout)
                is ProviderContent.Route -> require(component == Component.route)
                is ProviderContent.Lap -> require(component == Component.laps)
            }
    }

    fun validate(v: Any, derived: Boolean = true) {
        try {
            when (v) {
                is MeasurementSource -> {
                    v.dataOrigin?.let(::originPackage)
                }
                is Origin -> {
                    v.dataOrigin?.let(::originPackage)
                    text(v.originEventId)
                    require(v.sequence > 0)
                    v.providerObservationId?.let(::text)
                }
                is Association.Workout -> text(v.identity)
                is Association.RideWindow -> require(v.endUs >= v.startUs)
                is EnergyInterval -> {
                    revision(v.observationRevision)
                    bounds(v.startUs, v.endUs)
                    Timestamps.parse(v.originalStartTimestamp)
                    Timestamps.parse(v.originalEndTimestamp)
                    require(v.valueKcal.isFinite() && v.valueKcal >= 0)
                }
                is EnergyPoint -> {
                    revision(v.observationRevision)
                    Timestamps.parse(v.originalTimestamp)
                    require(v.valueKcal.isFinite() && v.valueKcal >= 0)
                }
                is EnergyEvidence -> require(v.metric in listOf("active", "basal", "total"))
                is HeartRateEvidence -> {
                    revision(v.observationRevision)
                    require(v.memberCount >= 0 && v.endUs >= v.startUs)
                    Timestamps.parse(v.originalStartTimestamp)
                    Timestamps.parse(v.originalEndTimestamp)
                    require(v.valueBpm == null || v.valueBpm.isFinite())
                    require(
                        v.representation in
                            listOf("rawQuantity", "rawSeries", "liveCumulative", "finalWorkoutTotal", "intervalRecord")
                    )
                    text(v.unit)
                }
                is HealthSelection -> {
                    text(v.metric)
                    require(v.unionCoverageUs >= 0)
                    require(v.contributingOrigins.size <= 128)
                }
                is ScanCursor -> {
                    require(v.position >= 0)
                    if (v.syncId.isNotEmpty()) HealthIds.syncId(v.syncId)
                }
                is SecondReading -> {
                    require(Timestamps.parse(v.originalUTC).comparisonUs == v.utcUs)
                    require(v.activeInterval == null || v.activeInterval >= 0)
                }
                is SecondValue -> {
                    require(
                        v.n >= 0 && v.validOriginalCount > 0 && v.coveredUs > 0 && v.value.isFinite() && v.value >= 0
                    )
                    bounds(v.startUs, v.endUs)
                    require(v.coveredUs <= v.endUs - v.startUs && v.integralQUs >= 0)
                }
                is RouteIntent.Replace -> {
                    require(v.projectedOriginKeys.size <= 30000)
                    v.projectedOriginKeys.forEach(HealthIds::originKey)
                    require(v.projectedOriginKeys.distinct().size == v.projectedOriginKeys.size)
                }
                is RouteProjection.Full -> {
                    require(v.encodedBytes >= 0 && v.ids.size <= 30000)
                    v.ids.forEach(HealthIds::originKey)
                }
                is RouteProjection.Reduced -> {
                    require(
                        v.encodedBytes >= 0 &&
                            v.originalCount >= v.eligibleCount &&
                            v.eligibleCount >= v.ids.size &&
                            v.ids.size <= 30000
                    )
                    v.ids.forEach(HealthIds::originKey)
                }
                is PublicationMetadata -> {
                    uuid(v.rideId)
                    uuid(v.ownerEndpointId)
                    uuid(v.publisherEndpointId)
                }
                is QuantityContent -> {
                    bounds(v.startUs, v.endUs)
                    require(v.value.isFinite() && v.value >= 0 && v.unit == v.kind.unit)
                    if (v.kind != HealthKind.cyclingDistance)
                        require(SecondKernel.policy(v.kind).valid(Scalar.Real64(v.value)))
                }
                is BoundaryContent -> {
                    HealthIds.originKey(v.firstOrigin)
                    HealthIds.originKey(v.lastOrigin)
                    bounds(v.startUs, v.endUs)
                }
                is RoutePoint -> {
                    HealthIds.originKey(v.originKey)
                    require(
                        v.latitude.isFinite() &&
                            v.latitude in -90.0..90.0 &&
                            v.longitude.isFinite() &&
                            v.longitude in -180.0..180.0
                    )
                    require(
                        v.horizontalAccuracyM == null || v.horizontalAccuracyM.isFinite() && v.horizontalAccuracyM >= 0
                    )
                    require(v.verticalAccuracyM == null || v.verticalAccuracyM.isFinite() && v.verticalAccuracyM >= 0)
                    require(v.altitudeMeters == null || v.altitudeMeters.isFinite())
                }
                is SessionContent -> {
                    bounds(v.startUs, v.endUs)
                    require(v.route.size <= 30000 && v.laps.size <= 1000 && v.pauses.size <= 1000)
                }
                is ProviderContent.Route -> require(v.points.size <= 30000)
                is ProviderContent.Lap -> {
                    uuid(v.eventId)
                    Timestamps.parse(v.timestamp)
                }
                is RouteEncoding -> {
                    require(v.laps >= 0 && v.pauses >= 0)
                    for (s in
                        listOf(
                            v.providerUUID,
                            v.packageName,
                            v.appName,
                            v.clientRecordId,
                            v.manufacturer,
                            v.model,
                            v.notes,
                            v.title,
                            v.plannedExerciseSessionId,
                        )) if (s != null) require(s.encodeToByteArray().size <= 1024)
                }
                is NormalizedContent.Present ->
                    if (derived && v.projection is AdapterProjection.Apple)
                        require(
                            v.fields === v.projection.content ||
                                HealthCodec.encodeProviderContent(v.fields) ==
                                    HealthCodec.encodeProviderContent(v.projection.content)
                        )
                is AdapterProjection.HealthConnect -> {
                    require(
                        v.encodedBytes >= 0 &&
                            v.startZoneOffsetSeconds in -64800..64800 &&
                            v.endZoneOffsetSeconds in -64800..64800
                    )
                }
                is RecordPlan -> {
                    publication(v.syncId, v.component, v.content)
                    revision(v.sourceRevision)
                    require((v.operation == RecordOperation.delete) == (v.content is NormalizedContent.Absent))
                }
                is PublicationRecord -> {
                    publication(v.syncId, v.component, v.frozenContent)
                    require(v.version > 0 && v.attempts >= 0 && v.providerIds.size <= 1000)
                    revision(v.sourceRevision)
                    hash(v.contentDigest)
                    if (derived) require(v.contentDigest == HealthCodec.contentDigest(v.frozenContent))
                    require((v.operation == RecordOperation.delete) == (v.frozenContent is NormalizedContent.Absent))
                    require(v.providerIds.distinct() == v.providerIds)
                    v.providerIds.forEach(::text)
                }
                is ComponentResult -> {
                    revision(v.sourceRevision)
                    require(v.providerIds.size <= 1000)
                    require(
                        v.reason != null ||
                            v.state !in listOf(ComponentState.failed, ComponentState.unavailable, ComponentState.fenced)
                    )
                }
                is ProviderParent -> {
                    originPackage(v.dataOrigin)
                    uuid(v.providerId)
                    text(v.metric)
                    hash(v.snapshotDigest)
                    require(v.memberCount >= 0)
                    Timestamps.parse(v.originalStart)
                    Timestamps.parse(v.originalEnd)
                }
                is ProviderMember -> {
                    require(
                        v.canonicalIndex >= 0 &&
                            v.canonicalIndex < v.parent.memberCount &&
                            v.occurrenceIndex >= 0 &&
                            v.occurrenceIndex <= v.canonicalIndex
                    )
                    bits(v.valueBits)
                    require(v.logicalKey == ProviderMembers.logicalKey(v.parent, v.canonicalIndex))
                    require(v.eventId == ProviderMembers.eventId(v.logicalKey))
                    Timestamps.parse(v.originalStart)
                    Timestamps.parse(v.originalEnd)
                    text(v.unit)
                }
                is ControllerMetric -> {
                    require(v.contentVersion > 0)
                    revision(v.sourceRevision)
                }
                is ControllerPresent -> require(v.value.isFinite() && v.value >= 0)
                is ControllerSecond -> {
                    require(v.n in 0..2678399L)
                    val start = FixedPoint.product(v.n, 1000000)
                    require(v.endOffsetUs > start && v.endOffsetUs <= FixedPoint.add(start, 1000000))
                    for ((m, max) in listOf(v.power to 5000.0, v.cadence to 300.0)) if (m.content is ControllerPresent)
                        require(m.content.value <= max)
                }
                is PublicationBudget -> {
                    require(v.calls.size <= 10000)
                    v.calls.forEach { validate(it) }
                    require(v.calls.zipWithNext().all { (a, b) -> a.atUs <= b.atUs })
                }
                is BudgetCall -> require(v.bytes >= 0)
                is MemberTuple -> {
                    ProviderMembers.normalized(v)
                    text(v.metric)
                    text(v.unit)
                    require(v.endUs > v.startUs || v.endUs == v.startUs && v.endSubUsNs >= v.startSubUsNs)
                }
                is MemberCursor -> {
                    hash(v.snapshotDigest)
                    require(v.nextIndex >= 0 && v.nextOccurrence >= 0 && v.nextOccurrence <= v.nextIndex)
                    require((v.previous == null) == (v.nextIndex == 0L))
                    v.previous?.let { validate(it) }
                }
                is Continuity -> require(v.interruptionIndex >= 0)
                is AnalysisRange -> require(v.endUs >= v.startUs)
                is Scalar.Real64 -> require(v.value.isFinite())
                is Scalar.Rational -> require(v.denominator > 0)
            }
        } catch (_: IllegalArgumentException) {
            invalid()
        } catch (_: ArithmeticException) {
            invalid()
        }
    }
}
