package app.powerlog.application.exports

import app.powerlog.application.PagePolicy
import app.powerlog.application.api.failure
import app.powerlog.application.store.HealthKey
import app.powerlog.application.store.ImportedPage
import app.powerlog.application.store.LocationKey
import app.powerlog.application.store.MemoryMeasurementRepository
import app.powerlog.application.store.StoreResult
import app.powerlog.application.store.TelemetryKey
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.measurement.ControllerSession
import app.powerlog.domain.core.measurement.HealthRepresentation
import app.powerlog.domain.core.measurement.TelemetryRows
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.ride.Platform
import app.powerlog.domain.exports.ExportPrint
import app.powerlog.domain.exports.SecondRow

internal sealed interface ProjectionState {
    data object Taken : ProjectionState

    class Connections(val seen: Set<Int>) : ProjectionState

    class Derived(val pass: DerivedPass) : ProjectionState

    class Planned(val plan: FitPlan) : ProjectionState
}

internal class ExportPages(
    private val session: ExportSession,
    private val read: StoreRead,
    private val states: (ExportSession, String, (ProjectionState?) -> ProjectionState?) -> Unit,
) {
    @Suppress("UNCHECKED_CAST") private suspend fun <T> stored(result: StoreResult<T>): T = read(result) as T

    suspend fun page(projection: String, after: List<Double>?): ExportPage =
        when (projection) {
            TELEMETRY -> if (session.record == null) imported(after) else telemetry(after)
            GPS -> gps(after)
            HEALTH -> health(after)
            LIFECYCLE -> lifecycle(after)
            else -> derived(projection)
        }

    private suspend fun telemetry(after: List<Double>?): ExportPage {
        val range = session.extent?.telemetry ?: return empty()
        val key = after?.let { TelemetryKey(it[0].toLong(), it[1].toInt(), it[2].toLong()) }
        val page = stored(session.store.telemetry(session.rideId, range, key, PagePolicy.rows))
        val rows = page.rows
        val connections = mutableListOf<ExportConnection>()
        var seen: Set<Int> = emptySet()
        states(session, TELEMETRY) { state ->
            seen = (state as? ProjectionState.Connections)?.seen.orEmpty()
            state
        }
        val tokens = seen.toMutableSet()
        for (row in 0 until rows.rows) {
            val number = rows.sessionNo[row]
            if (tokens.add(number)) connections += connection(number, session.sessions[number])
        }
        states(session, TELEMETRY) { ProjectionState.Connections(tokens) }
        val columns = linkedMapOf<String, ExportColumn>()
        columns["elapsedSeconds"] = numbers(rows.rows) { rows.elapsedUs[it] / 1e6 }
        columns["timestamp"] = texts(rows.rows) { ExportPrint.timestamp(rows.utcUs[it].toLong()) }
        columns["activeInterval"] = numbers(rows.rows) { interval(rows.activeInterval[it]) }
        columns["run"] = numbers(rows.rows) { rows.run[it].toDouble() }
        columns["connection"] = texts(rows.rows) { rows.sessionNo[it].toString() }
        for (metric in TELEMETRY_METRICS) columns[metric.name] = numbers(rows.rows) { value(rows, it, metric) }
        val last = rows.rows - 1
        val next = if (last < 0) null else listOf(rows.elapsedUs[last], rows.sessionNo[last].toDouble(), rows.seq[last])
        return ExportPage(rows.rows, next, page.next == null, connections, columns)
    }

    private suspend fun imported(after: List<Double>?): ExportPage {
        val memory = session.store as MemoryMeasurementRepository
        val page = stored(memory.importedRows(session.rideId, after?.single()?.toInt(), PagePolicy.rows))
        val rows = page.rows
        val start = after?.single()?.toInt() ?: 0
        val columns = linkedMapOf<String, ExportColumn>()
        columns["elapsedSeconds"] = numbers(rows.rows) { rows.elapsedSeconds[it] }
        columns["timestamp"] = texts(rows.rows) { timestamp(page, it) }
        columns["activeInterval"] = numbers(rows.rows) { interval(rows.activeInterval[it]) }
        columns["run"] = numbers(rows.rows) { rows.run[it].toDouble() }
        columns["connection"] = texts(rows.rows) { rows.connection[it].takeIf { c -> c != 0 }?.toString() }
        for (metric in TELEMETRY_METRICS) {
            val values = rows.values[metric] ?: continue
            columns[metric.name] = numbers(rows.rows) { values[it] }
        }
        val next = if (rows.rows == 0) null else listOf((start + rows.rows).toDouble())
        return ExportPage(rows.rows, next, page.next == null, emptyList(), columns)
    }

    private suspend fun gps(after: List<Double>?): ExportPage {
        val range = session.extent?.locations ?: return empty()
        val key = after?.let { LocationKey(it[0].toLong(), it[1].toLong()) }
        val page = stored(session.store.locations(session.rideId, range, key, PagePolicy.rows))
        val fixes = page.fixes
        val columns = linkedMapOf<String, ExportColumn>()
        val listed = ExportCatalog.columns(GPS, session.platform)
        fun put(name: String, column: () -> ExportColumn) {
            if (name in listed) columns[name] = column()
        }
        put("elapsedSeconds") { numbers(fixes) { ExportPrint.seconds(it.elapsedUs) } }
        put("timestamp") { texts(fixes) { ExportPrint.timestamp(it.utcUs) } }
        put("activeInterval") { numbers(fixes) { interval(it.activeInterval ?: 0) } }
        put("run") { numbers(fixes) { it.run.toDouble() } }
        put("producer") { texts(fixes) { "phone" } }
        put("latitude") { numbers(fixes) { it.latitude } }
        put("longitude") { numbers(fixes) { it.longitude } }
        put("altitudeMeters") { numbers(fixes) { it.mslM.orNaN() } }
        put("verticalAccuracyM") { numbers(fixes) { it.mslVerticalAccuracyM.orNaN() } }
        put("originalAltitudeMeters") { numbers(fixes) { it.altitudeM.orNaN() } }
        put("originalVerticalAccuracyM") { numbers(fixes) { it.verticalAccuracyM.orNaN() } }
        put("originalAltitudeDatum") { texts(fixes) { it.altitudeDatum.name } }
        put("horizontalAccuracyM") { numbers(fixes) { it.horizontalAccuracyM.orNaN() } }
        put("speedMps") { numbers(fixes) { it.speedMps.orNaN() } }
        put("speedAccuracyMps") { numbers(fixes) { it.speedAccuracyMps.orNaN() } }
        put("courseDegrees") { numbers(fixes) { it.courseDeg.orNaN() } }
        put("courseAccuracyDegrees") { numbers(fixes) { it.courseAccuracyDeg.orNaN() } }
        val next = fixes.lastOrNull()?.let { listOf(it.elapsedUs.toDouble(), it.seq.toDouble()) }
        return ExportPage(fixes.size, next, page.next == null, emptyList(), columns)
    }

    private suspend fun health(after: List<Double>?, limit: Int = PagePolicy.rows): ExportPage {
        val range = session.extent?.health ?: return empty()
        val key = after?.let { HealthKey(it[0].toLong(), it[1].toLong()) }
        val page = stored(session.store.health(session.rideId, range, null, key, limit))
        val samples = page.samples
        val platform = session.platform
        val metrics = samples.map { ExportCatalog.healthMetric(it, platform) }
        val columns = linkedMapOf<String, ExportColumn>()
        val listed = ExportCatalog.columns(HEALTH, platform)
        fun put(name: String, column: () -> ExportColumn) {
            if (name in listed) columns[name] = column()
        }
        put("elapsedSeconds") { numbers(samples) { ExportPrint.seconds(it.elapsedUs) } }
        put("timestamp") {
            texts(samples) {
                if (platform == Platform.android || it.representation in RAW_SAMPLES)
                    ExportPrint.healthTimestamp(it.utcUs, platform)
                else ExportPrint.timestamp(it.utcUs)
            }
        }
        put("activeInterval") { numbers(samples) { interval(it.activeInterval ?: 0) } }
        put("producer") { texts(samples) { it.producer.name } }
        for (column in ExportCatalog.healthValueColumns) put(column) {
            numbers(samples.size) { index -> samples[index].value.takeIf { metrics[index]?.column == column }.orNaN() }
        }
        put("identifier") { texts(samples.size) { metrics[it]?.identifier ?: samples[it].metric?.wire } }
        put("value") { numbers(samples) { it.value.orNaN() } }
        put("unit") { texts(samples.size) { metrics[it]?.unit } }
        put("representation") { texts(samples) { it.representation.name } }
        put("sampleCount") { numbers(samples) { it.sampleCount?.toDouble().orNaN() } }
        put("sampleStart") {
            texts(samples) { sample -> sample.startUtcUs?.let { ExportPrint.healthTimestamp(it, platform) } }
        }
        put("provider") { texts(samples) { ExportPrint.provider(platform)?.name } }
        put("providerParentId") { texts(samples) { it.parentId } }
        put("dataOrigin") { texts(samples) { it.dataOrigin.ifEmpty { null } } }
        put("originalEventId") { texts(samples) { it.sampleKey } }
        put("originalEndTimestamp") {
            texts(samples) { sample -> sample.endUtcUs?.let { ExportPrint.healthTimestamp(it, platform) } }
        }
        put("memberIndex") { texts(samples) { it.member?.toString() } }
        val next = page.next?.let { listOf(it.elapsedUs.toDouble(), it.order.toDouble()) }
        val built = ExportPage(samples.size, next, page.next == null, emptyList(), columns)
        return if (samples.size > 1 && built.encodedBytes() > ExportsPolicy.pageMaxBytes)
            health(after, samples.size / 2)
        else built
    }

    private fun lifecycle(after: List<Double>?): ExportPage {
        val start = after?.single()?.toInt() ?: 0
        val events = session.events.drop(start).take(PagePolicy.rows)
        val platform = session.platform
        val columns = linkedMapOf<String, ExportColumn>()
        columns["elapsedSeconds"] = numbers(events) { ExportPrint.seconds(it.elapsedUs) }
        columns["timestamp"] = texts(events) { ExportPrint.timestamp(it.utcUs) }
        columns["producer"] = texts(events) { ExportPrint.eventProducer(platform).name }
        columns["action"] = texts(events) { it.kind.name }
        columns["interrupted"] = numbers(events) { 0.0 }
        val end = start + events.size
        return ExportPage(
            events.size,
            if (events.isEmpty()) null else listOf(end.toDouble()),
            end >= session.events.size,
            emptyList(),
            columns,
        )
    }

    private suspend fun derived(projection: String): ExportPage {
        var state: ProjectionState? = null
        states(session, projection) { current ->
            if (current == ProjectionState.Taken) throw ExportFailed(failure(FailureCode.unknown))
            state = current
            ProjectionState.Taken
        }
        var result: ProjectionState? = null
        try {
            val pass = (state as? ProjectionState.Derived)?.pass ?: DerivedPass.start(session, projection, read, ::plan)
            val page = pass.page()
            result = ProjectionState.Derived(pass)
            return page
        } finally {
            states(session, projection) { result }
        }
    }

    private suspend fun plan(): FitPlan {
        var known: FitPlan? = null
        states(session, PLAN) { current ->
            known = (current as? ProjectionState.Planned)?.plan
            current
        }
        return known
            ?: FitPlan.of(session, read).also { plan -> states(session, PLAN) { ProjectionState.Planned(plan) } }
    }

    private fun empty(): ExportPage = ExportPage(0, null, true, emptyList(), emptyMap())

    private fun connection(number: Int, controller: ControllerSession?): ExportConnection {
        val identity = controller?.identity
        return ExportConnection(
            number.toString(),
            "controller",
            identity?.model,
            identity?.firmware,
            identity?.protocol,
        )
    }

    private fun value(rows: TelemetryRows, row: Int, metric: MetricId): Double {
        val controller = session.sessions[rows.sessionNo[row]] ?: return Double.NaN
        return rows.value(metric, row, controller) ?: Double.NaN
    }

    private fun timestamp(page: ImportedPage, row: Int): String =
        page.rows.timestamps.decodeToString(page.rows.timestampOffsets[row], page.rows.timestampOffsets[row + 1])

    companion object {
        const val TELEMETRY = "telemetry"
        const val GPS = "gps"
        const val HEALTH = "healthZip"
        const val LIFECYCLE = "lifecycle"
        const val CONSUMPTION = "consumption"
        const val SECONDS = "seconds"
        const val SUMMARY = "summary"
        private const val PLAN = "plan"
        private val RAW_SAMPLES = setOf(HealthRepresentation.rawQuantity, HealthRepresentation.rawSeries)

        val projections: Map<ExportKind, List<String>> =
            mapOf(
                ExportKind.zip to listOf(TELEMETRY, CONSUMPTION, GPS, HEALTH, LIFECYCLE),
                ExportKind.fit to listOf(SECONDS, SUMMARY),
                ExportKind.csv to listOf(SECONDS),
            )

        val TELEMETRY_METRICS =
            listOf(
                MetricId.humanPowerW,
                MetricId.cadenceRpm,
                MetricId.motorInputPowerW,
                MetricId.batteryVoltageV,
                MetricId.batteryCurrentA,
                MetricId.motorCurrentA,
                MetricId.motorRpm,
                MetricId.pedalTorqueNm,
                MetricId.controllerTempC,
                MetricId.motorTempC,
                MetricId.consumedAh,
                MetricId.consumedWh,
                MetricId.throttleVoltageV,
                MetricId.faultCode,
                MetricId.assistLevel,
                MetricId.controllerSpeedMps,
                MetricId.raceMode,
                MetricId.speedRaw,
            )

        suspend fun importedEnd(memory: MemoryMeasurementRepository, importId: RideId, read: StoreRead): Double {
            var after: Int? = null
            var end = 0.0
            while (true) {
                val page = read(memory.importedRows(importId, after, PagePolicy.rows)) as ImportedPage
                if (page.rows.rows > 0) end = page.rows.elapsedSeconds[page.rows.rows - 1]
                after = page.next ?: return end
            }
        }

        fun interval(value: Int): Double = if (value == 0) Double.NaN else value.toDouble()

        fun Double?.orNaN(): Double = this ?: Double.NaN

        fun numbers(rows: Int, value: (Int) -> Double): ExportColumn.Numbers =
            ExportColumn.Numbers(DoubleArray(rows) { value(it) })

        fun <T> numbers(items: List<T>, value: (T) -> Double): ExportColumn.Numbers =
            ExportColumn.Numbers(DoubleArray(items.size) { value(items[it]) })

        fun texts(rows: Int, value: (Int) -> String?): ExportColumn.Texts = ExportColumn.Texts(List(rows) { value(it) })

        fun <T> texts(items: List<T>, value: (T) -> String?): ExportColumn.Texts = ExportColumn.Texts(items.map(value))

        fun secondColumns(rows: List<SecondRow>): Map<String, ExportColumn> {
            val columns = linkedMapOf<String, ExportColumn>()
            columns["kind"] = texts(rows) { it.kind.name }
            columns["second"] = numbers(rows) { it.second?.toDouble().orNaN() }
            columns["elapsedSeconds"] = numbers(rows) { it.elapsedSeconds.orNaN() }
            columns["interval"] = numbers(rows) { it.interval?.toDouble().orNaN() }
            columns["lap"] = numbers(rows) { it.lap?.toDouble().orNaN() }
            for (name in SECOND_VALUES) columns[name] = numbers(rows) { it.values[name].orNaN() }
            return columns
        }

        val SECOND_VALUES =
            listOf(
                "power",
                "cadence",
                "motor",
                "assist",
                "heartRate",
                "latitude",
                "longitude",
                "altitude",
                "speed",
                "distance",
                "motorInputPowerW",
                "batteryVoltageV",
                "batteryCurrentA",
                "motorCurrentA",
                "motorRpm",
                "motorTempC",
                "controllerTempC",
                "pedalTorqueNm",
                "consumedWh",
                "consumedAh",
            )
    }
}
