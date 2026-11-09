package app.powerlog.application.goldens

import app.powerlog.application.store.ArchiveWriter
import app.powerlog.application.store.Commit
import app.powerlog.application.store.Mutation
import app.powerlog.application.store.StoreResult
import app.powerlog.application.store.WriteBatch
import app.powerlog.domain.core.controller.ControllerCapabilities
import app.powerlog.domain.core.health.RideHealth
import app.powerlog.domain.core.ids.EventId
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.measurement.ControllerSession
import app.powerlog.domain.core.measurement.HealthSample
import app.powerlog.domain.core.measurement.LocationFix
import app.powerlog.domain.core.measurement.TelemetryRows
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.ride.Platform
import app.powerlog.domain.core.ride.Ride
import app.powerlog.domain.core.ride.RideEvent
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.serialization.serializer

sealed interface GoldenLoad {
    data class Loaded(
        val rideId: RideId,
        val platform: Platform,
        val exportedAt: String,
        val batches: Int,
        val droppedHealthLines: List<Int>,
    ) : GoldenLoad

    data class Refused(val reason: String) : GoldenLoad

    data class NotCommitted(val batch: Int, val answer: StoreResult<Commit>) : GoldenLoad
}

internal class GoldenRefusal(reason: String) : Exception(reason)

internal fun refuse(reason: String): Nothing = throw GoldenRefusal(reason)

internal const val GOLDEN_BATCH_ROWS = 4_096

internal class TelemetryColumn(val metric: MetricId, val stored: String, val printed: String)

internal val TELEMETRY_COLUMNS: List<TelemetryColumn> =
    listOf(
        TelemetryColumn(MetricId.humanPowerW, "human_power_w", "humanPowerW"),
        TelemetryColumn(MetricId.cadenceRpm, "cadence_rpm", "cadenceRpm"),
        TelemetryColumn(MetricId.motorInputPowerW, "motor_input_power_w", "motorInputPowerW"),
        TelemetryColumn(MetricId.batteryVoltageV, "battery_voltage_v", "batteryVoltageV"),
        TelemetryColumn(MetricId.batteryCurrentA, "battery_current_a", "batteryCurrentA"),
        TelemetryColumn(MetricId.motorCurrentA, "motor_current_a", "motorCurrentA"),
        TelemetryColumn(MetricId.motorRpm, "motor_rpm", "motorRpm"),
        TelemetryColumn(MetricId.pedalTorqueNm, "pedal_torque_nm", "pedalTorqueNm"),
        TelemetryColumn(MetricId.controllerTempC, "controller_temp_c", "controllerTempC"),
        TelemetryColumn(MetricId.motorTempC, "motor_temp_c", "motorTempC"),
        TelemetryColumn(MetricId.consumedAh, "consumed_ah", "consumedAh"),
        TelemetryColumn(MetricId.consumedWh, "consumed_wh", "consumedWh"),
        TelemetryColumn(MetricId.throttleVoltageV, "throttle_voltage_v", "throttleVoltageV"),
        TelemetryColumn(MetricId.faultCode, "fault_code", "faultCode"),
        TelemetryColumn(MetricId.assistLevel, "assist_level", "assistLevel"),
        TelemetryColumn(MetricId.speedRaw, "speed_raw", "controller.speedRaw"),
        TelemetryColumn(MetricId.raceMode, "race_mode", "controller.raceMode"),
    )

internal val UNSTORED_CAPABILITIES = ControllerCapabilities(metrics = emptySet(), controllerSpeed = false)

@OptIn(ExperimentalUuidApi::class) internal fun newEventId(): EventId = EventId(Uuid.random().toString())

internal inline fun <reified T : Enum<T>> serialEnum(name: String, where: String): T {
    val descriptor = serializer<T>().descriptor
    val index = (0 until descriptor.elementsCount).firstOrNull { descriptor.getElementName(it) == name }
    return enumValues<T>()[index ?: refuse("$where is not a ${descriptor.serialName.substringAfterLast('.')}")]
}

internal class TelemetryBuilder {
    var size = 0
        private set

    private var elapsedUs = DoubleArray(INITIAL)
    private var sessionNo = IntArray(INITIAL)
    private var seq = DoubleArray(INITIAL)
    private var utcUs = DoubleArray(INITIAL)
    private var activeInterval = IntArray(INITIAL)
    private var run = IntArray(INITIAL)
    private val quanta = Array(TELEMETRY_COLUMNS.size) { DoubleArray(INITIAL) }

    fun add(
        elapsedUs: Long,
        sessionNo: Int,
        seq: Long,
        utcUs: Long,
        activeInterval: Int?,
        run: Int,
        counts: DoubleArray,
    ) {
        if (size == this.elapsedUs.size) grow()
        this.elapsedUs[size] = elapsedUs.toDouble()
        this.sessionNo[size] = sessionNo
        this.seq[size] = seq.toDouble()
        this.utcUs[size] = utcUs.toDouble()
        this.activeInterval[size] = activeInterval ?: 0
        this.run[size] = run
        for (column in quanta.indices) quanta[column][size] = counts[column]
        size++
    }

    fun elapsedUs(row: Int): Double = elapsedUs[row]

    fun sessionNo(row: Int): Int = sessionNo[row]

    fun seq(row: Int): Double = seq[row]

    fun rows(from: Int, to: Int): TelemetryRows =
        TelemetryRows(
            rows = to - from,
            elapsedUs = elapsedUs.copyOfRange(from, to),
            sessionNo = sessionNo.copyOfRange(from, to),
            seq = seq.copyOfRange(from, to),
            utcUs = utcUs.copyOfRange(from, to),
            activeInterval = activeInterval.copyOfRange(from, to),
            run = run.copyOfRange(from, to),
            quanta =
                TELEMETRY_COLUMNS.indices
                    .filter { column -> (from until to).any { !quanta[column][it].isNaN() } }
                    .associate { TELEMETRY_COLUMNS[it].metric to quanta[it].copyOfRange(from, to) },
        )

    private fun grow() {
        val capacity = elapsedUs.size * 2
        elapsedUs = elapsedUs.copyOf(capacity)
        sessionNo = sessionNo.copyOf(capacity)
        seq = seq.copyOf(capacity)
        utcUs = utcUs.copyOf(capacity)
        activeInterval = activeInterval.copyOf(capacity)
        run = run.copyOf(capacity)
        for (column in quanta.indices) quanta[column] = quanta[column].copyOf(capacity)
    }

    private companion object {
        const val INITIAL = 256
    }
}

internal class GoldenRide(
    val platform: Platform,
    val exportedAt: String,
    val ride: Ride,
    val health: RideHealth,
    val sessions: List<ControllerSession>,
    val telemetry: TelemetryBuilder,
    val fixes: List<LocationFix>,
    val samples: List<HealthSample>,
    val events: List<RideEvent>,
    val droppedHealthLines: List<Int> = emptyList(),
) {
    fun checkKeys() {
        sessions.forEachIndexed { index, session ->
            if (session.sessionNo != index + 1)
                refuse("controller session ${session.sessionNo} is not number ${index + 1}")
        }
        for (row in 0 until telemetry.size) {
            if (telemetry.sessionNo(row) !in 1..sessions.size)
                refuse(
                    "telemetry row ${row + 1} names controller session ${telemetry.sessionNo(row)}, which the ride lacks"
                )
            if (row > 0 && !telemetryFollows(row)) refuse("telemetry row ${row + 1} is not in key order")
        }
        fixes.zipWithNext().forEach { (previous, fix) ->
            if (fix.elapsedUs < previous.elapsedUs || (fix.elapsedUs == previous.elapsedUs && fix.seq <= previous.seq))
                refuse("location seq ${fix.seq} is not in key order")
        }
        samples.zipWithNext().forEachIndexed { index, (previous, sample) ->
            if (sample.elapsedUs < previous.elapsedUs)
                refuse("Health sample ${index + 2} is earlier than the one before it")
        }
        if (samples.distinctBy { it.sampleKey }.size != samples.size) refuse("two Health samples share a key")
        events.zipWithNext().forEachIndexed { index, (previous, event) ->
            if (event.elapsedUs < previous.elapsedUs) refuse("event ${index + 2} is earlier than the one before it")
        }
    }

    fun batches(): List<WriteBatch> {
        val id = ride.id
        val batches = mutableListOf<WriteBatch>()
        batches += WriteBatch(listOf(Mutation.CreateRide(ride, health)) + sessions.map(Mutation::OpenControllerSession))
        for (from in 0 until telemetry.size step GOLDEN_BATCH_ROWS) {
            val rows = telemetry.rows(from, minOf(from + GOLDEN_BATCH_ROWS, telemetry.size))
            batches += WriteBatch(listOf(Mutation.AppendTelemetry(id, rows)))
        }
        fixes.chunked(GOLDEN_BATCH_ROWS).forEach { batches += WriteBatch(listOf(Mutation.AppendLocations(id, it))) }
        samples.chunked(GOLDEN_BATCH_ROWS).forEach {
            batches += WriteBatch(listOf(Mutation.AppendHealthSamples(id, it)))
        }
        events.chunked(GOLDEN_BATCH_ROWS).forEach { batches += WriteBatch(listOf(Mutation.AppendEvents(id, it))) }
        return batches
    }

    private fun telemetryFollows(row: Int): Boolean {
        val elapsed = telemetry.elapsedUs(row).compareTo(telemetry.elapsedUs(row - 1))
        if (elapsed != 0) return elapsed > 0
        val session = telemetry.sessionNo(row).compareTo(telemetry.sessionNo(row - 1))
        if (session != 0) return session > 0
        return telemetry.seq(row) > telemetry.seq(row - 1)
    }
}

internal suspend fun loadGolden(writer: ArchiveWriter, read: () -> GoldenRide): GoldenLoad {
    val golden =
        try {
            read().also { it.checkKeys() }
        } catch (refusal: GoldenRefusal) {
            return GoldenLoad.Refused(refusal.message.orEmpty())
        }
    val batches = golden.batches()
    batches.forEachIndexed { index, batch ->
        val answer = writer.commit(batch)
        if ((answer as? StoreResult.Ok)?.value !is Commit.Committed) return GoldenLoad.NotCommitted(index, answer)
    }
    return GoldenLoad.Loaded(
        golden.ride.id,
        golden.platform,
        golden.exportedAt,
        batches.size,
        golden.droppedHealthLines,
    )
}
