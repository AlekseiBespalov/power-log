package app.powerlog.bridge

import java.util.Random
import java.util.UUID
import org.junit.Assert.assertEquals
import org.robolectric.RuntimeEnvironment

internal class EnergyRide(val store: RideStore, val id: String, val gps: Boolean, seed: Long) {
    val distance = RideDistance(store)
    val random = Random(seed)
    var time = 0.0
    private var energy = 10.0
    private var charge = 1.0
    private var longitude = 0.0

    fun sample(at: Double, epoch: String = "first") {
        energy += 0.02 * random.nextInt(5)
        charge += 0.0004 * random.nextInt(5)
        val values =
            mapOf(
                "humanPowerW" to 120.0 + random.nextInt(120),
                "motorInputPowerW" to 200.0 + random.nextInt(400),
                "controllerSpeedMps" to 3.0 + random.nextInt(8),
                "consumedWh" to energy,
                "consumedAh" to charge,
                "batteryCurrentA" to 4.0 + random.nextInt(10),
                "batteryVoltageV" to 50.0,
            )
        val row =
            store.insert(
                id,
                at,
                iso(START_MILLIS + (at * 1000).toLong()),
                "telemetry",
                true,
                0,
                values,
                "X6|energy|5.3",
                epoch,
            )
        distance.append(id, row, at, values, true, 0, epoch, "X6|energy|5.3", false)
    }

    fun fix(at: Double) {
        longitude += 0.00001 * (1 + random.nextInt(6))
        val values =
            mapOf(
                "latitude" to 0.0,
                "longitude" to longitude,
                "horizontalAccuracyM" to 3.0,
                "speedMps" to 3.0 + random.nextInt(6),
                "speedAccuracyMps" to 0.5,
            )
        val row = store.insert(id, at, iso(START_MILLIS + (at * 1000).toLong()), "location", true, 0, values)
        distance.append(id, row, at, values, true, 0, "", "", true)
    }

    fun advance(seconds: Double) = store.transaction {
        val until = time + seconds
        while (time < until) {
            time += 0.5
            sample(time)
            if (gps && time % 1.0 == 0.0) fix(time)
        }
    }

    fun energy(request: Payload): Payload =
        RideMonitor(store, distance)
            .query(
                id,
                BridgeInputs.monitor(
                    MonitorOperation.EnergyPerDistance,
                    mapOf(
                        "source" to "workout",
                        "id" to id,
                        "generation" to 0,
                        "expectedRevision" to store.readRevision(id).toString(),
                        "metrics" to emptyList<String>(),
                    ) + request,
                ),
            )
            .also { assertEquals("ok", it["status"]) }

    private companion object {
        const val START_MILLIS = 1767225600000L
    }
}

internal fun withEnergyRide(gps: Boolean, battery: String, body: (EnergyRide) -> Unit) =
    RideStore(RuntimeEnvironment.getApplication(), "energy-${UUID.randomUUID()}.sqlite").use { store ->
        val id =
            store.create(
                RideOptions(
                    !gps,
                    saveToHealth = false,
                    recordGPS = gps,
                    distanceSource = if (gps) "gps:phone" else "controller",
                    batteryConsumptionSource = battery,
                ),
                RecordingClockReading("2026-01-01T00:00:00.000Z", 0),
            )
        body(EnergyRide(store, id, gps, battery.length + if (gps) 7L else 0L))
    }
