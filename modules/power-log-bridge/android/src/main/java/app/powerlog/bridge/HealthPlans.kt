package app.powerlog.bridge

import android.content.Context
import app.powerlog.core.analytics.Continuity
import app.powerlog.core.analytics.Scalar
import app.powerlog.core.health.*

internal class HealthProjectionException(val failure: ProjectionFailure) : Exception(failure.name)

internal class HealthPlans(private val context: Context, private val store: RideStore) {
    fun metadata(id: String, m: Payload = store.metadata(id)): PublicationMetadata {
        return PublicationMetadata(
            id,
            EndpointRole.phone,
            m.str("ownerEndpointId"),
            EndpointRole.phone,
            m.str("publisherEndpointId"),
            m.flag("indoor"),
            m.flag("recordGPS"),
        )
    }

    data class RideHealth(val startUs: Long, val cutoffUs: Long?, val recordGPS: Boolean)

    fun ride(id: String, revision: Long, m: Payload = store.metadata(id)): RideHealth =
        RideHealth(timeUs(m.str("startedAt")), (m["endedAt"] as? String)?.let(::timeUs), m.flag("recordGPS"))

    fun encoding(id: String, laps: Int = 0, pauses: Int = 0, providerId: String? = null) =
        RouteEncoding(
            providerId,
            context.packageName,
            context.applicationInfo.loadLabel(context.packageManager).toString(),
            HealthIds.workout(id),
            null,
            null,
            null,
            "Power Log ride",
            null,
            laps,
            pauses,
        )

    fun quantity(
        id: String,
        kind: HealthKind,
        value: SecondValue,
        revision: Long,
        metadata: PublicationMetadata = metadata(id),
        encoding: RouteEncoding = encoding(id),
    ): RecordPlan {
        val fields =
            ProviderContent.Quantity(
                QuantityContent(
                    kind,
                    value.startUs,
                    value.endUs,
                    value.value,
                    kind.unit,
                    value.source,
                    metadata,
                )
            )
        val projection =
            AdapterProjection.HealthConnect(
                fields,
                0,
                0,
                RouteIntent.NotRequested,
                2048,
                encoding,
            )
        return RecordPlan(
            HealthIds.second(id, kind, value.n),
            Component.entries.single { it.wire == kind.wire },
            RecordOperation.upsert,
            NormalizedContent.Present(fields, projection),
            revision,
            null,
        )
    }

    fun second(
        id: String,
        kind: HealthKind,
        bounds: SecondBounds,
        revision: Long,
        metadata: PublicationMetadata = metadata(id),
        encoding: RouteEncoding = encoding(id),
    ): RecordPlan {
        val column =
            when (kind) {
                HealthKind.riderPower -> "humanPowerW"
                HealthKind.cyclingCadence -> "cadenceRpm"
                else -> "speedMps"
            }
        val type = if (kind == HealthKind.cyclingSpeed) "location" else "telemetry"
        val gap = SecondKernel.policy(kind).gapUs
        val rows =
            store.select(
                "SELECT o.*,l.action AS phase_action,l.id AS phase_id FROM observations o INDEXED BY observations_health_second LEFT JOIN lifecycle l ON l.id=(SELECT p.id FROM lifecycle p INDEXED BY lifecycle_health_phase WHERE p.ride=o.ride AND p.revision<=? AND p.action!='lap' AND (p.elapsed_us<o.elapsed_us OR p.elapsed_us=o.elapsed_us AND p.action!='stop') ORDER BY p.elapsed_us DESC,p.id DESC LIMIT 1) WHERE o.ride=? AND o.kind=? AND (o.kind!='location' OR o.producer='phone') AND o.revision<=? AND o.query_us BETWEEN ? AND ? ORDER BY o.query_us,o.producer,o.producer_sequence,o.event_id LIMIT 129",
                arrayOf(revision, id, type, revision, bounds.startUs - gap, bounds.endUs + gap),
            ) { c ->
                buildList {
                    while (c.moveToNext()) add(
                        Triple(
                            with(store) { c.observation() },
                            c.getString(c.getColumnIndexOrThrow("phase_action")),
                            c.getLong(c.getColumnIndexOrThrow("phase_id")),
                        )
                    )
                }
            }
        val intervals = mutableMapOf<Pair<Long, Int>, Int>()
        val source =
            if (type == "telemetry") MeasurementSource(Source.controller, Device.controller)
            else MeasurementSource(Source.phone, Device.androidPhone)
        val readings =
            rows
                .map { (row, phase, phaseId) ->
                    val origin = checkNotNull(row.original)
                    SecondReading(
                        app.powerlog.core.health.Origin(
                            null,
                            null,
                            null,
                            origin.eventId,
                            Source.valueOf(origin.producer),
                            origin.sequence,
                        ),
                        row.timestamp,
                        row.queryUs,
                        row.elapsedUs,
                        source,
                        if (row.active && phase in listOf("start", "resume"))
                            intervals.getOrPut(phaseId to row.segment) { intervals.size }
                        else null,
                        Continuity(
                            row.evidence["clockEpoch"] as? String,
                            row.epoch,
                            row.evidence.num("interruptionIndex").toInt(),
                        ),
                        row.values[column]?.let { Scalar.Real64(it) },
                        null,
                        false,
                        row.values["horizontalAccuracyM"],
                        row.values["speedAccuracyMps"],
                    )
                }
                .toTypedArray()
        val aggregated = SecondKernel.aggregate(bounds, kind, readings)
        val outcome =
            if (
                aggregated is SecondOutcome.Sample &&
                    HealthRecords.instant(aggregated.sample.endUs) <= HealthRecords.instant(aggregated.sample.startUs)
            )
                SecondOutcome.Invalid(HealthReason.invalid)
            else aggregated
        return when (outcome) {
            is SecondOutcome.Sample ->
                if (HealthRecords.instant(outcome.sample.endUs) > HealthRecords.instant(outcome.sample.startUs))
                    quantity(id, kind, outcome.sample, revision, metadata, encoding)
                else absent(id, kind, bounds.n, revision, Absence.noCoverage)
            is SecondOutcome.Absent -> absent(id, kind, bounds.n, revision, outcome.reason)
            else -> absent(id, kind, bounds.n, revision, Absence.noCoverage)
        }
    }

    private fun absent(id: String, kind: HealthKind, n: Long, revision: Long, reason: Absence) =
        RecordPlan(
            HealthIds.second(id, kind, n),
            Component.entries.single { it.wire == kind.wire },
            RecordOperation.delete,
            NormalizedContent.Absent(reason),
            revision,
            null,
        )

    data class Page(val records: List<RecordPlan>, val phase: String, val position: Long)

    fun page(id: String, job: AndroidHealthJob, routeGranted: Boolean): Page {
        val ride = ride(id, job.pinRevision)
        val cutoff = requireNotNull(ride.cutoffUs)
        return when (job.phase) {
            "seconds" -> {
                val count = SecondBuckets.count(ride.startUs, cutoff)
                val through = minOf(count, job.position + 32)
                val kinds =
                    listOf(HealthKind.riderPower, HealthKind.cyclingCadence) +
                        if (ride.recordGPS) listOf(HealthKind.cyclingSpeed) else emptyList()
                val records =
                    (job.position until through)
                        .flatMap { n ->
                            kinds.map { kind ->
                                second(id, kind, SecondBuckets.bounds(ride.startUs, cutoff, n), job.pinRevision)
                            }
                        }
                        .filter { it.content is NormalizedContent.Present }
                Page(records, if (through == count) "distance" else "seconds", if (through == count) 0 else through)
            }
            "distance" -> {
                val records = mutableListOf<RecordPlan>()
                val scan =
                    distance(
                        id,
                        job.pinRevision,
                        ScanCursor(Component.cyclingDistance, "", job.position),
                        ride,
                        metadata(id),
                        encoding(id),
                        records::add,
                    )
                Page(records, if (scan.second) "workout" else "distance", if (scan.second) 0 else scan.first.position)
            }
            "workout" -> Page(listOf(session(id, job.pinRevision, routeGranted)), "done", 0)
            else -> Page(emptyList(), "done", 0)
        }
    }

    private fun distance(
        id: String,
        revision: Long,
        cursor: ScanCursor,
        ride: RideHealth,
        metadata: PublicationMetadata,
        encoding: RouteEncoding,
        append: (RecordPlan) -> Unit,
    ): Pair<ScanCursor, Boolean> {
        var next = cursor
        var count = 0
        store.select(
            "SELECT d.*,a.event_id AS first_event,b.event_id AS last_event,a.query_us AS first_us,b.query_us AS last_us FROM distance_intervals d JOIN observations a ON a.id=d.from_id JOIN observations b ON b.id=d.to_id WHERE d.ride=? AND d.source='gps:phone' AND a.producer='phone' AND b.producer='phone' AND d.revision<=? AND d.meters_q>0 AND d.to_id>? ORDER BY d.to_id LIMIT 128",
            arrayOf(id, revision, cursor.position),
        ) { c ->
            while (c.moveToNext()) {
                count++
                next =
                    ScanCursor(
                        Component.cyclingDistance,
                        HealthIds.distance(
                            id,
                            HealthIds.event(c.text("first_event")),
                            HealthIds.event(c.text("last_event")),
                        ),
                        c.long("to_id"),
                    )
                val a = c.long("first_us")
                val b = c.long("last_us")
                if (a < ride.startUs || b > (ride.cutoffUs ?: Long.MAX_VALUE)) continue
                if (HealthRecords.instant(b) <= HealthRecords.instant(a)) {
                    continue
                }
                val fields =
                    ProviderContent.Quantity(
                        QuantityContent(
                            HealthKind.cyclingDistance,
                            a,
                            b,
                            c.long("meters_q") / 1000000.0,
                            "m",
                            MeasurementSource(Source.phone, Device.androidPhone),
                            metadata,
                        )
                    )
                append(
                    RecordPlan(
                        HealthIds.distance(
                            id,
                            HealthIds.event(c.text("first_event")),
                            HealthIds.event(c.text("last_event")),
                        ),
                        Component.cyclingDistance,
                        RecordOperation.upsert,
                        NormalizedContent.Present(
                            fields,
                            AdapterProjection.HealthConnect(
                                fields,
                                0,
                                0,
                                RouteIntent.NotRequested,
                                2048,
                                encoding,
                            ),
                        ),
                        revision,
                        null,
                    )
                )
            }
        }
        return next to (count < 128)
    }

    fun session(
        id: String,
        revision: Long,
        routeGranted: Boolean,
        ride: RideHealth = ride(id, revision),
        metadata: PublicationMetadata = metadata(id),
        baseEncoding: RouteEncoding = encoding(id),
    ): RecordPlan {
        val end = checkNotNull(ride.cutoffUs)
        if (HealthRecords.instant(end) <= HealthRecords.instant(ride.startUs))
            throw HealthProjectionException(ProjectionFailure.unrepresentableTime)
        fun boundary(a: Triple<String, Long, String>, b: Triple<String, Long, String>) =
            BoundaryContent(a.third, b.third, a.second, b.second)
        fun valid(a: Long, b: Long) =
            a >= ride.startUs && b <= end && HealthRecords.instant(b) > HealthRecords.instant(a)
        val laps = mutableListOf<BoundaryContent>()
        val pauses = mutableListOf<BoundaryContent>()
        var lap: Triple<String, Long, String>? = null
        var pause: Triple<String, Long, String>? = null
        store.select(
            "SELECT action,query_us,event_id FROM lifecycle INDEXED BY lifecycle_health_order WHERE ride=? AND revision<=? ORDER BY elapsed_us,id",
            arrayOf(id, revision),
        ) { c ->
            while (c.moveToNext()) {
                val e =
                    Triple(
                        c.text("action"),
                        c.long("query_us"),
                        HealthIds.event(c.text("event_id")),
                    )
                if (e.first == "start") lap = e
                val previous = lap
                if (previous != null && e.first in listOf("lap", "stop") && valid(previous.second, e.second)) {
                    laps.add(boundary(previous, e))
                    lap = e
                }
                if (e.first == "pause") pause = e
                if (e.first in listOf("resume", "stop")) {
                    pause?.let {
                        if (valid(it.second, e.second) && (pauses.lastOrNull()?.endUs ?: Long.MIN_VALUE) <= it.second)
                            pauses.add(boundary(it, e))
                    }
                    pause = null
                }
                if (laps.size > 1000 || pauses.size > 1000)
                    throw HealthProjectionException(ProjectionFailure.minimumRecordTooLarge)
            }
        }
        val encoding = baseEncoding.copy(laps = laps.size, pauses = pauses.size)
        var count = 0L
        fun routes(body: (Array<RoutePoint>) -> Unit) {
            val eligibility = RouteEligibilityCursor(Provider.healthConnect, ride.startUs, end)
            store.select(
                "SELECT * FROM observations INDEXED BY observations_health_route WHERE ride=? AND kind='location' AND producer='phone' AND revision<=? AND active=1 AND horizontalAccuracyM BETWEEN 0 AND 50 AND latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180 ORDER BY query_us,event_id",
                arrayOf(id, revision),
            ) { c ->
                val page = mutableListOf<RoutePoint>()
                fun flush() {
                    if (page.isNotEmpty()) {
                        body(eligibility.append(page.toTypedArray()))
                        page.clear()
                    }
                }
                while (c.moveToNext()) {
                    page.add(
                        RoutePoint(
                            HealthIds.event(c.text("event_id")),
                            c.long("query_us"),
                            c.double("latitude"),
                            c.double("longitude"),
                            c.double("horizontalAccuracyM"),
                            if (c.isNull(c.getColumnIndex("verticalAccuracyM"))) null
                            else c.double("verticalAccuracyM"),
                            if (c.isNull(c.getColumnIndex("altitudeMeters"))) null else c.double("altitudeMeters"),
                        )
                    )
                    if (page.size == 128) flush()
                }
                flush()
                count = eligibility.count
            }
        }
        val route = mutableListOf<RoutePoint>()
        if (ride.recordGPS) {
            routes {}
            require(count <= Int.MAX_VALUE)
            val indices = HealthRoutes.indices(count.toInt(), HealthRoutes.baseBytes(encoding))
            if (count > 0 && indices == null) throw HealthProjectionException(ProjectionFailure.minimumRecordTooLarge)
            if (indices != null && indices.isNotEmpty()) {
                val retain = RouteRetentionCursor(indices)
                routes { route.addAll(retain.append(it)) }
                check(retain.exhausted())
            }
        }
        val intent = if (!ride.recordGPS) RouteIntent.NotRequested else RouteIntent.Replace(route.map { it.originKey })
        val permission = Publication.routeProjection(intent, routeGranted, null, route, ride.recordGPS)
        val fields = ProviderContent.Session(SessionContent(ride.startUs, end, metadata, laps, pauses, route))
        val native = ProviderContent.Session((fields as ProviderContent.Session).fields.copy(route = permission.route))
        val projection =
            AdapterProjection.HealthConnect(
                native,
                0,
                0,
                permission.intent,
                HealthRoutes.baseBytes(encoding) + 48 * permission.route.size,
                encoding,
            )
        if (projection.encodedBytes > 900000) throw HealthProjectionException(ProjectionFailure.minimumRecordTooLarge)
        return RecordPlan(
            HealthIds.workout(id),
            Component.workout,
            RecordOperation.upsert,
            NormalizedContent.Present(fields, projection),
            revision,
            permission.intent,
        )
    }
}
