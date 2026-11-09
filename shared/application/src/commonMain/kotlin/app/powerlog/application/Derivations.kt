package app.powerlog.application

import app.powerlog.domain.core.analysis.battery.BatteryConsumptionReducer
import app.powerlog.domain.core.analysis.battery.BatteryConsumptionState
import app.powerlog.domain.core.analysis.battery.ConsumptionInterval
import app.powerlog.domain.core.analysis.distance.ControllerDistanceReducer
import app.powerlog.domain.core.analysis.distance.ControllerDistanceState
import app.powerlog.domain.core.analysis.distance.DistanceInterval
import app.powerlog.domain.core.analysis.distance.DistanceSource
import app.powerlog.domain.core.analysis.distance.GpsDistanceReducer
import app.powerlog.domain.core.analysis.distance.GpsDistanceState
import app.powerlog.domain.core.measurement.ControllerSession
import app.powerlog.domain.core.measurement.Fact
import app.powerlog.domain.core.measurement.TelemetryRows

internal interface DerivedSink : FactSink {
    override fun append(rows: TelemetryRows, from: Int, to: Int, sessions: Map<Int, ControllerSession>) = Unit

    override fun append(fact: Fact) = Unit

    fun distance(source: DistanceSource, interval: DistanceInterval) = Unit

    fun battery(interval: ConsumptionInterval) = Unit
}

internal class DerivedStates(
    val controller: ControllerDistanceState?,
    val gps: GpsDistanceState?,
    val battery: BatteryConsumptionState?,
)

internal class Derivations(private val sinks: List<DerivedSink>, states: DerivedStates? = null) : FactSink {
    private val controller = ControllerDistanceReducer(states?.controller)
    private val gps = GpsDistanceReducer(states?.gps)
    private val battery = BatteryConsumptionReducer(states?.battery)

    override fun append(rows: TelemetryRows, from: Int, to: Int, sessions: Map<Int, ControllerSession>) {
        val distances = controller.append(rows, from, to, sessions)
        val consumption = battery.append(rows, from, to, sessions)
        for (sink in sinks) {
            var distance = 0
            var energy = 0
            for (row in from until to) {
                val elapsedUs = rows.elapsedUs[row].toLong()
                sink.append(rows, row, row + 1, sessions)
                while (distance < distances.size && distances[distance].range.endUs <= elapsedUs) sink.distance(
                    DistanceSource.controller,
                    distances[distance++],
                )
                while (energy < consumption.size && consumption[energy].range.endUs <= elapsedUs) sink.battery(
                    consumption[energy++]
                )
            }
            while (distance < distances.size) sink.distance(DistanceSource.controller, distances[distance++])
            while (energy < consumption.size) sink.battery(consumption[energy++])
        }
    }

    override fun append(fact: Fact) {
        val edge = (fact as? Fact.Location)?.let { gps.append(it.fix) }
        for (sink in sinks) {
            sink.append(fact)
            if (edge != null) sink.distance(DistanceSource.phoneGps, edge.interval)
        }
    }

    fun states(): DerivedStates = DerivedStates(controller.state(), gps.state(), battery.state())
}
