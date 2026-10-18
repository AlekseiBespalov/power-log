package app.powerlog.bridge

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class RideConsumptionTest {
    private fun withRide(body: (RideStore, String) -> Unit) {
        RideStore(RuntimeEnvironment.getApplication(), "battery-${UUID.randomUUID()}.sqlite").use { store ->
            val id =
                store.create(
                    RideOptions(
                        false,
                        saveToHealth = false,
                        recordGPS = true,
                        batteryConsumptionSource = "calculated",
                    ),
                    RecordingClockReading("2026-01-01T00:00:00Z", 0),
                )
            body(store, id)
        }
    }

    private fun sample(
        store: RideStore,
        id: String,
        time: Double,
        charge: Double = 10.0,
        energy: Double = 200.0,
        current: Double = 36.0,
        active: Boolean = true,
        segment: Int = 0,
        epoch: String = "first",
        values: Map<String, Double>? = null,
    ): Long =
        store.insert(
            id,
            time,
            iso(1767225600000L + (time * 1000).toLong()),
            "telemetry",
            active,
            segment,
            values
                ?: mapOf(
                    "consumedAh" to charge,
                    "consumedWh" to energy,
                    "batteryCurrentA" to current,
                    "batteryVoltageV" to 50.0,
                    "controllerSpeedMps" to 5.0,
                ),
            "X6|fixture|5.3",
            epoch,
        )

    private fun query(
        store: RideStore,
        id: String,
        kind: MonitorOperation,
        metrics: List<String>,
        extra: Payload = emptyMap(),
    ): Payload =
        RideMonitor(store, RideDistance(store))
            .query(
                id,
                BridgeInputs.monitor(
                    kind,
                    mapOf(
                        "source" to "workout",
                        "id" to id,
                        "generation" to 0,
                        "expectedRevision" to store.readRevision(id).toString(),
                        "metrics" to metrics,
                    ) + extra,
                ),
            )

    @Test
    fun countersStartAtZeroAndAllMonitorOperationsExposeBothMethods() = withRide { store, id ->
        sample(store, id, 0.0)
        val metrics = consumptionMetrics.toList()
        val first = query(store, id, MonitorOperation.Latest, metrics)["points"] as Map<*, *>
        assertTrue(metrics.all { (first[it] as Map<*, *>)["value"] == 0.0 })
        sample(store, id, 1.0, charge = 10.02, energy = 201.0)
        sample(store, id, 2.0, charge = 10.04, energy = 202.0)
        val describe = query(store, id, MonitorOperation.Describe, metrics)
        assertTrue((describe["availableMetrics"] as List<*>).containsAll(metrics))
        assertTrue((describe["metricSources"] as Map<*, *>).keys.containsAll(metrics))
        val range =
            query(store, id, MonitorOperation.Stats, metrics, mapOf("startSeconds" to 0.0, "endSeconds" to 2.0))[
                "statistics"]
                as Map<*, *>
        assertEquals(2.0, (range["controllerEnergyWh"] as Map<*, *>)["amount"])
        assertEquals(1.0, (range["calculatedEnergyWh"] as Map<*, *>)["amount"])
        val plot =
            query(
                store,
                id,
                MonitorOperation.Plot,
                metrics,
                mapOf("startSeconds" to 0.0, "endSeconds" to 2.0, "buckets" to 10),
            )["series"]
                as Map<*, *>
        assertTrue(metrics.all { (plot[it] as List<*>).isNotEmpty() })
        val inspect =
            query(store, id, MonitorOperation.Inspect, metrics, mapOf("seconds" to 1.0))["points"] as Map<*, *>
        assertEquals(1.0, (inspect["controllerEnergyWh"] as Map<*, *>)["value"])
        assertEquals(
            "ok",
            query(store, id, MonitorOperation.Changes, emptyList(), mapOf("sinceRevision" to "0"))["status"],
        )
        assertEquals("calculated", store.metadata(id)["batteryConsumptionSource"])
        val summary = RideConsumption(store).summary(id)
        assertEquals(1.0, summary["energyWh"])
        assertEquals(0.02, summary["chargeAh"])
        val second =
            store.create(RideOptions(true, saveToHealth = false), RecordingClockReading("2026-01-02T00:00:00Z", 0))
        sample(store, second, 0.0, charge = 99.0, energy = 999.0)
        assertEquals(0.0, RideConsumption(store).summary(second)["energyWh"])
    }

    @Test
    fun resetsPausesReconnectsAndClippedCountersNeverInventConsumption() = withRide { store, id ->
        sample(store, id, 0.0)
        sample(store, id, 1.0, charge = 11.0, energy = 201.0)
        sample(store, id, 2.0, charge = 1.0, energy = 1.0)
        sample(store, id, 3.0, charge = 2.0, energy = 2.0)
        sample(store, id, 4.0, charge = 20.0, energy = 20.0, active = false)
        sample(store, id, 5.0, charge = 30.0, energy = 30.0, segment = 1)
        sample(store, id, 6.0, charge = 31.0, energy = 31.0, segment = 1)
        sample(store, id, 7.0, charge = 50.0, energy = 50.0, segment = 1, epoch = "second")
        sample(store, id, 8.0, charge = 51.0, energy = 51.0, segment = 1, epoch = "second")
        val consumption = RideConsumption(store)
        assertEquals(4.0, consumption.range(id, "controllerEnergyWh", 0.0, 8.0).amount, 0.0)
        val clipped = consumption.range(id, "controllerEnergyWh", 0.5, 0.75)
        assertTrue(clipped.unresolvedBoundary)
        assertEquals(0.0, clipped.amount, 0.0)
        assertEquals(0.125, consumption.range(id, "calculatedEnergyWh", 0.5, 0.75).amount, 1e-10)
        sample(store, id, 20.0, charge = 100.0, energy = 100.0, segment = 1, epoch = "second")
        assertEquals(4.0, consumption.range(id, "controllerEnergyWh", 0.0, 20.0).amount, 0.0)
    }

    @Test
    fun sourceSpecificDistancesRemainAvailableIndependentlyOfMainChoice() = withRide { store, id ->
        val distance = RideDistance(store)
        for (time in listOf(0.0, 1.0)) store.transaction {
            val values = mapOf("controllerSpeedMps" to 5.0)
            val row = sample(store, id, time, values = values)
            distance.append(id, row, time, values, true, 0, "first", "X6|fixture|5.3", false)
            val gps = mapOf("latitude" to 0.0, "longitude" to time * 0.00001, "horizontalAccuracyM" to 1.0)
            val location =
                store.insert(
                    id,
                    time,
                    iso(1767225600000L + (time * 1000).toLong()),
                    "location",
                    true,
                    0,
                    gps,
                    epoch = "gps",
                )
            distance.append(id, location, time, gps, true, 0, "gps", "", true)
        }
        val metrics = listOf("distanceMeters", "gpsDistanceMeters", "controllerDistanceMeters")
        val latest = query(store, id, MonitorOperation.Latest, metrics)["points"] as Map<*, *>
        assertEquals(
            (latest["gpsDistanceMeters"] as Map<*, *>)["value"],
            (latest["distanceMeters"] as Map<*, *>)["value"],
        )
        assertEquals(5.0, (latest["controllerDistanceMeters"] as Map<*, *>)["value"])
        val inspected =
            query(store, id, MonitorOperation.Inspect, metrics, mapOf("seconds" to 0.0))["points"] as Map<*, *>
        assertTrue(metrics.all { (inspected[it] as Map<*, *>)["value"] == 0.0 })
        assertTrue(
            (query(store, id, MonitorOperation.Describe, metrics)["availableMetrics"] as List<*>).containsAll(metrics)
        )
        val stats =
            query(store, id, MonitorOperation.Stats, metrics, mapOf("startSeconds" to 0.0, "endSeconds" to 1.0))[
                "statistics"]
                as Map<*, *>
        assertEquals(5.0, (stats["controllerDistanceMeters"] as Map<*, *>)["distance"])
    }

    @Test
    fun checkpointRollbackAndDiscardedPlotCachesKeepTheSameTotals() = withRide { store, id ->
        sample(store, id, 0.0)
        assertThrows(IllegalStateException::class.java) {
            store.transaction {
                sample(store, id, 1.0, charge = 11.0, energy = 201.0)
                error("Simulated failed transaction")
            }
        }
        sample(store, id, 2.0, charge = 12.0, energy = 202.0)
        val metrics = consumptionMetrics.toList()
        val request = mapOf("startSeconds" to 0.0, "endSeconds" to 2.0, "buckets" to 10)
        val before = query(store, id, MonitorOperation.Plot, metrics, request)["series"]
        store.writableDatabase.delete("analytics", "ride=?", arrayOf(id))
        store.writableDatabase.delete("analytics_plot_blocks", "ride=?", arrayOf(id))
        assertEquals(before, query(store, id, MonitorOperation.Plot, metrics, request)["series"])
        assertEquals(2.0, RideConsumption(store).range(id, "controllerEnergyWh", 0.0, 2.0).amount, 0.0)
        assertEquals(1.0, RideConsumption(store).range(id, "calculatedEnergyWh", 0.0, 2.0).amount, 0.0)
        assertEquals(202.0, store.page(id).last().values["consumedWh"])
    }

    @Test
    fun lifecycleBoundaryReplaysTheTailAndPreservesAlreadyPinnedReads() {
        val name = "battery-boundary-${UUID.randomUUID()}.sqlite"
        val context = RuntimeEnvironment.getApplication()
        val id =
            RideStore(context, name).use { store ->
                val id =
                    store.create(
                        RideOptions(false, saveToHealth = false),
                        RecordingClockReading("2026-01-01T00:00:00Z", 0),
                    )
                for (time in 0..2) sample(store, id, time.toDouble(), energy = 100.0 + time)
                val metrics = listOf("controllerEnergyWh")
                val plotRequest = mapOf("startSeconds" to 0.0, "endSeconds" to 2.0, "buckets" to 10)
                val before = query(store, id, MonitorOperation.Plot, metrics, plotRequest)["series"]
                val pin = store.pin(id)
                store.lifecycle(id, RideTiming(2.0, 2.0, "2026-01-01T00:00:02Z"), "pause")
                assertEquals(1.0, RideConsumption(store).range(id, "controllerEnergyWh", 0.0, 2.0).amount, 0.0)
                store.withPin(pin) {
                    assertEquals(2.0, RideConsumption(store).range(id, "controllerEnergyWh", 0.0, 2.0).amount, 0.0)
                    assertEquals(before, query(store, id, MonitorOperation.Plot, metrics, plotRequest)["series"])
                    assertEquals(
                        2.0,
                        ((query(store, id, MonitorOperation.Latest, metrics)["points"] as Map<*, *>)[
                            "controllerEnergyWh"]
                            as Map<*, *>)["value"],
                    )
                }
                store.lifecycle(id, RideTiming(4.0, 2.0, "2026-01-01T00:00:04Z"), "resume")
                sample(store, id, 4.0, energy = 110.0, segment = 1)
                sample(store, id, 5.0, energy = 111.0, segment = 1)
                store.lifecycle(id, RideTiming(5.0, 3.0, "2026-01-01T00:00:05Z"), "stop")
                assertEquals(2.0, RideConsumption(store).range(id, "controllerEnergyWh", 0.0, 5.0).amount, 0.0)
                id
            }
        RideStore(context, name).use { store ->
            sample(store, id, 6.0, energy = 112.0, segment = 1)
            assertEquals(3.0, RideConsumption(store).range(id, "controllerEnergyWh", 0.0, 6.0).amount, 0.0)
        }
    }

    @Test
    fun interruptedCloseRetainsOnlyTheCommittedSequenceAtItsExactTimestamp() = withRide { store, id ->
        for (time in 0..2) sample(store, id, time.toDouble(), energy = 100.0 + time)
        val retained = requireNotNull(store.page(id).last().original).sequence
        sample(store, id, 2.0, energy = 103.0)
        store.lifecycle(
            id,
            RideTiming(2.0, 2.0, "2026-01-01T00:00:02Z"),
            "pause",
            interrupted = true,
            evidence = mapOf("clockEpoch" to "first", "controllerSequence" to retained.toString()),
        )
        assertEquals(2.0, RideConsumption(store).range(id, "controllerEnergyWh", 0.0, 2.0).amount, 0.0)
        store.lifecycle(id, RideTiming(4.0, 2.0, "2026-01-01T00:00:04Z"), "resume")
        sample(store, id, 4.0, energy = 110.0, segment = 1)
        sample(store, id, 5.0, energy = 111.0, segment = 1)
        assertEquals(3.0, RideConsumption(store).range(id, "controllerEnergyWh", 0.0, 5.0).amount, 0.0)
    }

    @Test
    fun pauseAtTheFirstBaselineRemovesDerivedAvailability() = withRide { store, id ->
        sample(store, id, 0.0)
        store.lifecycle(id, RideTiming(0.0, 0.0, "2026-01-01T00:00:00Z"), "pause")
        assertFalse(store.available(id).any { it in consumptionMetrics })
        assertFalse(RideConsumption(store).range(id, "controllerEnergyWh", 0.0, 0.0).available)
    }

    @Test
    fun lifecycleReplayStartsFromTheAffectedCheckpointInsteadOfTheRideStart() = withRide { store, id ->
        for (time in 0..65) sample(store, id, time.toDouble(), energy = 100.0 + time)
        val replayAfter = mutableListOf<Long>()
        store.queryObserver = { sql, args ->
            if (sql.contains("kind='telemetry' AND id>? ORDER BY id LIMIT 128"))
                replayAfter.add((args[1] as Number).toLong())
        }
        store.lifecycle(id, RideTiming(65.0, 65.0, "2026-01-01T00:01:05Z"), "pause")
        store.queryObserver = null
        assertTrue(replayAfter.isNotEmpty())
        assertTrue(replayAfter.all { it >= 64 })
        assertEquals(64.0, RideConsumption(store).range(id, "controllerEnergyWh", 0.0, 65.0).amount, 0.0)
    }

    @Test
    fun captureBatchPreparesLifecycleMembershipOnce() = withRide { store, id ->
        var lifecycleReads = 0
        store.queryObserver = { sql, _ ->
            if (sql.startsWith("SELECT id,elapsed_us,producer,producer_sequence,evidence FROM lifecycle"))
                lifecycleReads++
        }
        store.transaction {
            for (index in 0..63) sample(store, id, index / 8.0, energy = 100.0 + index)
            assertEquals(1, lifecycleReads)
        }
        store.queryObserver = null
        assertEquals(63.0, RideConsumption(store).range(id, "controllerEnergyWh", 0.0, 8.0).amount, 0.0)
    }

    @Test
    fun absentInputsStayUnavailableAndPreviewDoesNotAccumulateRideConsumption() = withRide { store, id ->
        sample(store, id, 0.0, values = mapOf("humanPowerW" to 100.0))
        assertNull(RideConsumption(store).summary(id)["energyWh"])
        val preview =
            store.create(
                RideOptions(true, saveToHealth = false),
                RecordingClockReading("2026-01-01T00:00:00Z", 0),
                live = true,
            )
        sample(store, preview, 0.0)
        sample(store, preview, 1.0)
        assertFalse(store.available(preview).any { it in consumptionMetrics })
        assertFalse(HealthReadback.types.any { it.simpleName == "TotalCaloriesBurnedRecord" })
        assertFalse(store.available(id).contains("basalEnergyKcal"))
    }
}
