package app.powerlog.bridge

import android.location.Location
import android.util.Base64
import androidx.core.location.LocationCompat
import app.powerlog.core.telemetry.ExportCatalog
import expo.modules.kotlin.Promise
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

internal class Rejection(val code: String?, message: String?) : Exception(message)

internal class Reply : Promise {
    private val future = CompletableFuture<Any?>()

    val settled
        get() = future.isDone

    override fun resolve(value: Any?) {
        future.complete(value)
    }

    override fun reject(code: String?, message: String?, cause: Throwable?) {
        future.completeExceptionally(Rejection(code, message))
    }

    fun get(): Any? =
        try {
            future.get(20, TimeUnit.SECONDS)
        } catch (error: ExecutionException) {
            throw checkNotNull(error.cause)
        }
}

@Suppress("UNCHECKED_CAST")
internal fun openedExport(store: RideStore, distance: RideDistance, id: String, kind: String): Payload {
    val source = ExportSource(store, distance, CountingExecutor())
    val reply = Reply()
    val context = mapOf("exportedAt" to "2026-10-02T00:00:00.000Z", "platform" to "android")
    source.open(mapOf("rideId" to id, "kind" to kind, "context" to context), reply)
    val opened = reply.get() as Payload
    source.close(opened["session"] as String)
    return opened
}

internal class CountingExecutor : Executor {
    var tasks = 0

    override fun execute(command: Runnable) {
        tasks++
        command.run()
    }
}

internal class QueueExecutor : Executor {
    val queue = ArrayDeque<Runnable>()

    override fun execute(command: Runnable) {
        queue.addLast(command)
    }

    fun runNext() = queue.removeFirst().run()

    fun drain() {
        while (queue.isNotEmpty()) runNext()
    }
}

internal fun rejection(block: () -> Unit): String? =
    try {
        block()
        null
    } catch (error: Rejection) {
        error.code
    }

private class FakeConversion(private val failure: (Location) -> Exception? = { null }) : MslConversion {
    val threads = mutableListOf<String>()

    override fun convert(location: Location) {
        threads.add(Thread.currentThread().name)
        failure(location)?.let { throw it }
        LocationCompat.setMslAltitudeMeters(location, location.altitude - 50)
        if (location.hasVerticalAccuracy())
            LocationCompat.setMslAltitudeAccuracyMeters(location, location.verticalAccuracyMeters + 1f)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ExportSourceTest {
    private lateinit var store: RideStore
    private lateinit var distance: RideDistance
    private lateinit var id: String
    private val database = CountingExecutor()
    private val start = 1767225600000L
    private val context = mapOf("exportedAt" to "2026-10-02T00:00:00.000Z", "platform" to "android")

    @Before
    fun setup() {
        store = RideStore(RuntimeEnvironment.getApplication(), "export-source-${UUID.randomUUID()}.sqlite")
        distance = RideDistance(store)
        id = ride()
    }

    @After
    fun close() {
        store.close()
    }

    private fun ride(indoor: Boolean = false): String {
        val ride =
            store.create(
                RideOptions(indoor = indoor, saveToHealth = false, recordGPS = !indoor, sampleHz = 4),
                RecordingClockReading(iso(start), 0),
            )
        return ride
    }

    private fun stamp(time: Double) = iso(start + (time * 1000).roundToLong())

    private fun telemetry(
        time: Double,
        values: Map<String, Double> = mapOf("humanPowerW" to 100.0, "controllerSpeedMps" to 5.0),
        epoch: String = "epoch-1",
        identity: String = "X6|000000|5.3",
        active: Boolean = true,
        ride: String = id,
    ): Long = store.transaction {
        val row = store.insert(ride, time, stamp(time), "telemetry", active, 1, values, identity, epoch)
        distance.append(ride, row, time, values, active, 1, epoch, identity, false)
        row
    }

    private fun location(time: Double, altitude: Double? = 120.0, vertical: Double? = 4.0) = buildMap {
        put("latitude", 0.0)
        put("longitude", time * 0.00005)
        put("horizontalAccuracyM", 5.0)
        put("speedMps", 5.5)
        put("speedAccuracyMps", 0.5)
        put("courseDegrees", 90.0)
        if (altitude != null) put("altitudeMeters", altitude)
        if (vertical != null) put("verticalAccuracyM", vertical)
    }

    private fun fix(time: Double, values: Map<String, Double> = location(time), ride: String = id): Long =
        store.transaction {
            val row = store.insert(ride, time, stamp(time), "location", true, 1, values, "phone", "gps")
            distance.append(ride, row, time, values, true, 1, "gps", "phone", true)
            row
        }

    private fun seal(elapsed: Double, interrupted: Boolean = false, ride: String = id) =
        store.fixtureOriginalEnd(ride, RideTiming(elapsed, elapsed - 1, stamp(elapsed)), interrupted)

    private fun source(
        pageRows: Int = ExportSource.PAGE_ROWS,
        jobRows: Int = ExportSource.JOB_ROWS,
        database: Executor = this.database,
    ) = ExportSource(store, distance, database, pageRows, jobRows)

    private fun request(kind: String, ride: String = id, distanceSource: String? = null) = buildMap {
        put("rideId", ride)
        put("kind", kind)
        put("context", context)
        if (distanceSource != null) put("distanceSource", distanceSource)
    }

    @Suppress("UNCHECKED_CAST")
    private fun ExportSource.opened(kind: String, ride: String = id, distanceSource: String? = null): Payload {
        val reply = Reply()
        open(request(kind, ride, distanceSource), reply)
        return reply.get() as Payload
    }

    @Suppress("UNCHECKED_CAST")
    private fun ExportSource.paged(session: Any?, projection: String, after: List<Double>? = null): Payload {
        val reply = Reply()
        page(mapOf("session" to session, "projection" to projection, "after" to after), reply)
        return reply.get() as Payload
    }

    @Suppress("UNCHECKED_CAST")
    private fun ExportSource.pass(session: Any?, projection: String): List<Payload> {
        val pages = mutableListOf<Payload>()
        var after: List<Double>? = null
        while (true) {
            val page = paged(session, projection, after)
            pages.add(page)
            if (page["done"] == true) return pages
            after = page["last"] as List<Double>
        }
    }

    @Test
    fun closingExportRetainsItsLeaseUntilTheAdmittedPageDrains() {
        telemetry(0.0)
        seal(2.0)
        val queue = QueueExecutor()
        val source = source(database = queue)
        val open = Reply()
        source.open(request("zip"), open)
        queue.drain()
        val session = (open.get() as Payload).str("session")
        val page = Reply()
        source.page(mapOf("session" to session, "projection" to "telemetry"), page)
        source.close(session)
        assertTrue(store.hasReaders(id))
        assertThrows(IllegalStateException::class.java) { store.deleteHistorical(id, store.lifecycleState()) }
        queue.drain()
        assertEquals("cancelled", rejection { page.get() })
        assertFalse(store.hasReaders(id))
        store.deleteHistorical(id, store.lifecycleState())
        assertFalse(store.exists(id))
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun interruptedFitProjectionsRetainClosingMembership() {
        val cases = JSONObject()
        for (resume in listOf(false, true)) {
            val ride = ride(indoor = true)
            telemetry(0.0, mapOf("humanPowerW" to 100.0), ride = ride)
            telemetry(1.0, mapOf("humanPowerW" to 100.0), ride = ride)
            store.update(ride, "running", RideTiming(1.0, 1.0, stamp(1.0)), emptyMap())
            store.lifecycle(
                ride,
                RideTiming(1.0, 1.0, stamp(1.0)),
                "pause",
                true,
                evidence = mapOf("controllerSequence" to "2"),
            )
            store.update(ride, "paused", RideTiming(1.0, 1.0, stamp(1.0)), mapOf("interrupted" to true))
            if (resume) {
                store.lifecycle(ride, RideTiming(1.0, 1.0, stamp(1.0)), "resume")
                store.update(ride, "running", RideTiming(1.0, 1.0, stamp(1.0)))
                telemetry(1.0, mapOf("humanPowerW" to 300.0), epoch = "epoch-2", ride = ride)
                telemetry(2.0, mapOf("humanPowerW" to 300.0), epoch = "epoch-2", ride = ride)
            }
            store.fixtureOriginalEnd(ride, RideTiming(3.0, if (resume) 3.0 else 1.0, stamp(3.0)), true)
            val source = source()
            val opened = source.opened("fit", ride)
            assertEquals(if (resume) 4 else 3, store.events(ride).size)
            assertTrue(store.events(ride).last().interrupted)
            val pages = JSONObject()
            for (projection in listOf("telemetry", "gps", "gpsDiscovery", "healthFit", "lifecycle", "distance")) {
                val page = source.paged(opened["session"], projection)
                if (projection == "lifecycle") {
                    assertEquals(
                        listOf(2.0),
                        page.numbers("controllerSequence").filter { it.isFinite() },
                    )
                    assertEquals(
                        if (resume) listOf(1.0, 2.0, 3.0, 4.0) else listOf(1.0, 2.0, 3.0),
                        page.numbers("producerSequence"),
                    )
                }
                if (projection == "telemetry")
                    assertEquals(
                        if (resume) listOf(1.0, 2.0, 3.0, 4.0) else listOf(1.0, 2.0),
                        page.numbers("producerSequence"),
                    )
                pages.put(
                    projection,
                    JSONObject(
                        page +
                            mapOf(
                                "columns" to
                                    page.columns().mapValues { (_, value) ->
                                        if (value is ByteArray)
                                            mapOf("base64" to java.util.Base64.getEncoder().encodeToString(value))
                                        else value
                                    }
                            )
                    ),
                )
            }
            cases.put(
                if (resume) "resume" else "stop",
                JSONObject(mapOf("open" to opened + mapOf("session" to "captured-native"), "pages" to pages)),
            )
            source.close(opened["session"] as String)
        }
        val root =
            generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
                .first { File(it, "tests/fixtures/contract/export").isDirectory }
        val fixture = File(root, "tests/fixtures/contract/export/interruption-android.json")
        if (System.getenv("POWER_LOG_UPDATE_FIT_NATIVE_FIXTURES") == "1") fixture.writeText(cases.toString(2) + "\n")
        assertEquals(JSONObject(fixture.readText()).toString(), cases.toString())
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun fractionalFitBoundaryRetainsOriginalElapsed() {
        val ride = ride(indoor = true)
        telemetry(0.0, mapOf("humanPowerW" to 100.0), ride = ride)
        telemetry(0.51250005, mapOf("humanPowerW" to 200.0), ride = ride)
        store.fixtureOriginalEnd(ride, RideTiming(0.512500125, 0.512500125, stamp(0.512500125)), true)
        val source = source()
        val opened = source.opened("fit", ride)
        assertTrue(store.events(ride).last().interrupted)
        val pages = JSONObject()
        for (projection in listOf("telemetry", "gps", "gpsDiscovery", "healthFit", "lifecycle", "distance")) {
            val page = source.paged(opened["session"], projection)
            if (projection == "telemetry") assertEquals(listOf(0.0, 0.51250005), page.numbers("elapsedSeconds"))
            pages.put(
                projection,
                JSONObject(
                    page +
                        mapOf(
                            "columns" to
                                page.columns().mapValues { (_, value) ->
                                    if (value is ByteArray)
                                        mapOf("base64" to java.util.Base64.getEncoder().encodeToString(value))
                                    else value
                                }
                        )
                ),
            )
        }
        source.close(opened["session"] as String)
        val cases =
            JSONObject()
                .put(
                    "integer-boundary",
                    JSONObject(mapOf("open" to opened + mapOf("session" to "captured-native"), "pages" to pages)),
                )
        val root =
            generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
                .first { File(it, "tests/fixtures/contract/export").isDirectory }
        val fixture = File(root, "tests/fixtures/contract/export/review-android.json")
        if (System.getenv("POWER_LOG_UPDATE_FIT_NATIVE_FIXTURES") == "1") fixture.writeText(cases.toString(2) + "\n")
        assertEquals(JSONObject(fixture.readText()).toString(), cases.toString())
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun intervalEnergyFitProjectionsRetainUnclippedBounds() {
        val cases = JSONObject()
        for (name in listOf("energy-n5", "energy-spanning", "overlapping-energy", "energy-after-bound")) {
            val ride =
                store.create(
                    RideOptions(true, saveToHealth = true, recordGPS = false),
                    RecordingClockReading(iso(start), 0),
                )
            telemetry(0.0, mapOf("humanPowerW" to 100.0), ride = ride)
            store.fixtureOriginalEnd(ride, RideTiming(10.0, 10.0, stamp(10.0)))
            val readback = HealthReadback(store, FakeHealthConnectAccess())
            val instant = java.time.Instant.ofEpochMilli(start)
            val spans =
                if (name == "energy-n5") listOf(Triple(-2L, 2L, 4.0), Triple(8L, 12L, 6.0))
                else if (name == "overlapping-energy") listOf(Triple(2L, 4L, 10.0), Triple(0L, 10L, 100.0))
                else if (name == "energy-after-bound") listOf(Triple(0L, 2678400L, 20.0))
                else listOf(Triple(-5L, 15L, 20.0))
            for ((index, span) in spans.withIndex()) {
                val (a, b, value) = span
                fun metadata(n: Int) =
                    HealthMetadata.received(
                        "00000000-0000-4000-8000-${n.toString().padStart(12, '0')}",
                        "generated:$n",
                        1,
                        null,
                        "app.generated",
                        instant.plusSeconds(1),
                        androidx.health.connect.client.records.metadata.Metadata.RECORDING_METHOD_ACTIVELY_RECORDED,
                    )
                readback.import(
                    ride,
                    androidx.health.connect.client.records.ActiveCaloriesBurnedRecord(
                        instant.plusSeconds(a),
                        java.time.ZoneOffset.UTC,
                        instant.plusSeconds(b).plusNanos(if (name == "energy-after-bound") 1000 else 0),
                        java.time.ZoneOffset.UTC,
                        androidx.health.connect.client.units.Energy.kilocalories(value),
                        metadata(200 + index * 2),
                    ),
                )
            }
            if (name == "overlapping-energy")
                store.writableDatabase.execSQL(
                    "UPDATE observations SET time=CAST(json_extract(evidence,'$.endUs') AS REAL)/1000000.0 WHERE ride=? AND kind='health'",
                    arrayOf(ride),
                )
            store.savedHealthEvidence(ride)
            val summary = RideDetail(store, distance).detail(ride, "auto")["summary"] as Payload
            assertEquals(
                when (name) {
                    "energy-n5" -> 5.0
                    "energy-spanning" -> 10.0
                    "overlapping-energy" -> 110.0
                    else -> null
                },
                summary["activeEnergyKcal"],
            )
            assertNull(summary["basalEnergyKcal"])
            val source = if (name == "overlapping-energy") source(pageRows = 1, jobRows = 1) else source()
            val opened = source.opened("fit", ride)
            val pages = JSONObject()
            for (projection in listOf("telemetry", "gps", "gpsDiscovery", "healthFit", "lifecycle", "distance")) {
                val stream =
                    if (name == "overlapping-energy") source.pass(opened["session"], projection)
                    else listOf(source.paged(opened["session"], projection))
                for (page in stream) {
                    if (projection == "healthFit" && name !in listOf("overlapping-energy", "energy-after-bound")) {
                        assertEquals(spans.map { it.third }, page.numbers("activeEnergyKcal").filter { it.isFinite() })
                        assertEquals(
                            spans.map { it.first.toDouble() },
                            page.numbers("elapsedSeconds"),
                        )
                    }
                }
                val captured = stream.map { page ->
                    JSONObject(
                        page +
                            mapOf(
                                "columns" to
                                    page.columns().mapValues { (_, value) ->
                                        if (value is ByteArray)
                                            mapOf("base64" to java.util.Base64.getEncoder().encodeToString(value))
                                        else value
                                    }
                            )
                    )
                }
                pages.put(
                    projection,
                    if (name == "overlapping-energy") org.json.JSONArray(captured) else captured.single(),
                )
            }
            source.close(opened["session"] as String)
            cases.put(
                name,
                JSONObject(
                    mapOf(
                        "open" to opened + mapOf("session" to "captured-native"),
                        "pages" to pages,
                        "history" to summary.filterKeys { it in listOf("activeEnergyKcal", "basalEnergyKcal") },
                    )
                ),
            )
        }
        val root =
            generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
                .first { File(it, "tests/fixtures/contract/export").isDirectory }
        val fixture = File(root, "tests/fixtures/contract/export/energy-android.json")
        val update = System.getenv("POWER_LOG_UPDATE_FIT_NATIVE_FIXTURES") == "1"
        val process =
            ProcessBuilder(
                    "node",
                    File(root, "tests/fixtures/export/native.ts").path,
                    if (update) "write" else "read",
                    fixture.path,
                )
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
        process.outputStream.use { if (update) it.write(cases.toString().toByteArray()) }
        val expected = process.inputStream.bufferedReader().readText()
        assertEquals("native fixture preparation succeeds", 0, process.waitFor())
        assertEquals(JSONObject(expected).toString(), cases.toString())
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun integerDistanceFitProjectionDoesNotConvertToSeconds() {
        val ride = ride(indoor = true)
        telemetry(0.0, mapOf("humanPowerW" to 100.0, "controllerSpeedMps" to 1.0), ride = ride)
        telemetry(0.512500125, mapOf("humanPowerW" to 100.0, "controllerSpeedMps" to 1.0), ride = ride)
        store.transaction { store.lifecycle(ride, RideTiming(0.512499125, 0.512499125, stamp(0.512499125)), "lap") }
        store.fixtureOriginalEnd(ride, RideTiming(1.0, 1.0, stamp(1.0)))
        val source = source()
        val opened = source.opened("fit", ride, "controller")
        store.transaction {
            store.writableDatabase.execSQL(
                "UPDATE distance_intervals SET meters_q=1000000,cumulative_q=1000000 WHERE ride=? AND source='controller'",
                arrayOf(ride),
            )
        }
        val pages = JSONObject()
        for (projection in listOf("telemetry", "gps", "gpsDiscovery", "healthFit", "lifecycle", "distance")) {
            val page = source.paged(opened["session"], projection)
            if (projection == "distance") {
                assertEquals(listOf("0"), page.texts("startUs"))
                assertEquals(listOf("512500"), page.texts("endUs"))
            }
            pages.put(
                projection,
                JSONObject(
                    page +
                        mapOf(
                            "columns" to
                                page.columns().mapValues { (_, value) ->
                                    if (value is ByteArray)
                                        mapOf("base64" to java.util.Base64.getEncoder().encodeToString(value))
                                    else value
                                }
                        )
                ),
            )
        }
        source.close(opened["session"] as String)
        val captured =
            JSONObject()
                .put(
                    "distance-endpoints",
                    JSONObject(mapOf("open" to opened + mapOf("session" to "captured-native"), "pages" to pages)),
                )
        val root =
            generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
                .first { File(it, "tests/fixtures/contract/export").isDirectory }
        val fixture = File(root, "tests/fixtures/contract/export/distance-android.json")
        if (System.getenv("POWER_LOG_UPDATE_FIT_NATIVE_FIXTURES") == "1") fixture.writeText(captured.toString(2) + "\n")
        assertEquals(JSONObject(fixture.readText()).toString(), captured.toString())
    }

    private fun Payload.columns() = this["columns"] as Map<*, *>

    private fun Payload.numbers(name: String): List<Double> {
        val bytes = columns()[name] as ByteArray
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return List(bytes.size / 8) { buffer.getDouble(it * 8) }
    }

    private fun Payload.texts(name: String) = (columns()[name] as List<*>).map { it as String? }

    private fun List<Payload>.numbers(name: String) = flatMap { it.numbers(name) }

    private fun List<Payload>.texts(name: String) = flatMap { it.texts(name) }

    private fun assertBits(expected: List<Double>, actual: List<Double>) =
        assertEquals(expected.map { it.toRawBits() }, actual.map { it.toRawBits() })

    @Suppress("UNCHECKED_CAST")
    private fun assertCursors(pages: List<Payload>) {
        val cursors = pages.mapNotNull { it["last"] as List<Double>? }
        cursors.zipWithNext().forEach { (a, b) ->
            val order = a.indices.firstNotNullOfOrNull { i -> a[i].compareTo(b[i]).takeIf { it != 0 } } ?: 0
            assertTrue("$a then $b", order < 0)
        }
        pages.forEach { page ->
            val rows = page["rows"] as Int
            assertTrue(rows > 0 || page["done"] == true && page["last"] == null)
        }
        assertEquals(true, pages.last()["done"])
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun openFreezesTheRideMetadataElapsedEndAndProducers() {
        telemetry(1.0)
        telemetry(1.5)
        fix(1.0)
        store.transition(id, RideTiming(4.0, 3.0, stamp(4.0)), "pause")
        seal(9.5)
        store.healthStatus(id, HealthExportResult("notSaved", written = 3, omitted = 2, reason = "Health is off."))
        val timing = store.timing(id)
        val opened = source().opened("zip")
        assertEquals(9.5, opened["elapsedEnd"])
        assertNull(opened["distanceProfile"])
        assertEquals(mapOf("gps" to listOf("phone"), "health" to emptyList<String>()), opened["producers"])
        val metadata = opened["metadata"] as Payload
        assertEquals(
            mapOf(
                "startedAt" to iso(start),
                "endedAt" to stamp(9.5),
                "ownerTiming" to
                    mapOf("timestamp" to timing.timestamp, "elapsedSeconds" to 9.5, "timerSeconds" to timing.timer),
                "indoor" to false,
                "interrupted" to false,
                "watchEnabled" to false,
                "saveToHealth" to false,
                "recordGPS" to true,
                "health" to
                    mapOf(
                        "provider" to "healthConnect",
                        "components" to emptyList<Any>(),
                        "projectionFailures" to null,
                        "physiologyProvenance" to "backfilled",
                        "readback" to
                            mapOf(
                                "heartRateCoveredSeconds" to 0.0,
                                "healthOrigins" to mapOf("heartRate" to null, "activeEnergy" to null),
                                "physiologyProvenance" to "backfilled",
                            ),
                        "state" to "notRequested",
                        "workoutUUID" to null,
                        "export" to null,
                    ),
                "collectionRevision" to store.metadata(id)["collectionRevision"],
                "example" to false,
                "sampleHz" to 4,
                "batteryConsumptionSource" to "controller",
            ),
            metadata,
        )
        val indoor = ride(indoor = true)
        telemetry(1.0, ride = indoor)
        seal(2.0, interrupted = true, ride = indoor)
        val interrupted = source().opened("fit", indoor)
        assertEquals(mapOf("gps" to emptyList<String>(), "health" to emptyList<String>()), interrupted["producers"])
        assertEquals(true, (interrupted["metadata"] as Payload)["interrupted"])
        assertNull(((interrupted["metadata"] as Payload)["health"] as Payload)["export"])
    }

    @Test
    fun fitProfileFollowsRideDistanceSourceChoice() {
        for (time in 0..20) {
            telemetry(time * 0.5)
            fix(time.toDouble())
        }
        seal(21.0)
        val indoor = ride(indoor = true)
        for (time in 0..8) telemetry(time * 0.5, ride = indoor)
        for (time in 0..8) fix(time.toDouble(), ride = indoor)
        seal(9.0, ride = indoor)
        val profiles =
            mapOf(
                "gps:phone" to mapOf("source" to "gps:phone", "kind" to "gps", "producer" to "phone"),
                "controller" to mapOf("source" to "controller", "kind" to "controller"),
            )
        for (ride in listOf(id, indoor)) for (choice in DistanceSource.entries) {
            val opened = source().opened("fit", ride, choice.wire)
            assertEquals(
                "$ride ${choice.wire}",
                profiles[distance.selected(ride, choice.wire)],
                opened["distanceProfile"],
            )
        }
        assertEquals(profiles["gps:phone"], source().opened("fit")["distanceProfile"])
        assertEquals(profiles["controller"], source().opened("fit", indoor)["distanceProfile"])
        assertEquals(profiles["gps:phone"], source().opened("fit", distanceSource = "gps:watch")["distanceProfile"])
        assertNull(source().opened("zip", distanceSource = "controller")["distanceProfile"])
        val bare = ride()
        seal(1.0, ride = bare)
        assertNull(source().opened("fit", bare)["distanceProfile"])
    }

    @Test
    fun gateAdmitsCompletedLocalRidesAndLeavesNoProtectionBehind() {
        telemetry(1.0)
        assertEquals("gate", rejection { source().opened("zip") })
        seal(2.0)
        val source = source()
        val opened = source.opened("fit")
        source.close(opened["session"] as String)
        assertEquals("deleted", rejection { source().opened("zip", "missing") })
        store.remove(id)
        assertEquals("deleted", rejection { source().opened("zip") })
    }

    @Test
    fun requestsOutsideTheContractAreRejected() {
        telemetry(1.0)
        seal(2.0)
        val source = source()
        fun open(request: Map<String, Any?>) = rejection {
            val reply = Reply()
            source.open(request, reply)
            reply.get()
        }
        assertEquals("unsupported", open(request("csv")))
        assertEquals("unsupported", open(request("zip") - "rideId"))
        assertEquals("unsupported", open(request("zip", distanceSource = "gps:bike")))
        assertEquals("unsupported", open(request("zip") + mapOf("context" to context + ("platform" to "ios"))))
        assertEquals("unsupported", open(request("zip") - "context"))
        val zip = source.opened("zip")["session"]
        val fit = source.opened("fit")["session"]
        assertEquals("cancelled", rejection { source.paged("unknown", "telemetry") })
        assertEquals("unsupported", rejection { source.paged(zip, "events") })
        for (projection in listOf("gpsDiscovery", "healthFit", "distance")) assertEquals(
            projection,
            "unsupported",
            rejection { source.paged(zip, projection) },
        )
        assertEquals("unsupported", rejection { source.paged(fit, "healthZip") })
        val safe = RideStore.MAX_SAFE_REVISION.toDouble()
        for (after in
            listOf(
                listOf(1.0),
                listOf(1.0, 2.0, 3.0),
                listOf(Double.NaN, 1.0),
                listOf(1.0, Double.POSITIVE_INFINITY),
                listOf(1.0, 1.5),
                listOf(1.0, -1.0),
                listOf(1.0, safe * 2),
            )) assertEquals("$after", "unsupported", rejection { source.paged(zip, "telemetry", after) })
        for (after in listOf(listOf(1.0), listOf(1.5, 2.0), listOf(safe * 2, 2.0))) assertEquals(
            "unsupported",
            rejection { source.paged(fit, "distance", after) },
        )
        assertEquals(
            "unsupported",
            rejection {
                val reply = Reply()
                source.page(mapOf("session" to zip, "projection" to "lifecycle", "after" to listOf("a", "b")), reply)
                reply.get()
            },
        )
        val queue = QueueExecutor()
        val queued = source(database = queue)
        val opened = Reply()
        queued.open(request("zip"), opened)
        queue.drain()
        val session = (opened.get() as Map<*, *>)["session"]
        val first = Reply()
        val second = Reply()
        queued.page(mapOf("session" to session, "projection" to "telemetry", "after" to null), first)
        queued.page(mapOf("session" to session, "projection" to "telemetry", "after" to null), second)
        assertEquals("unsupported", rejection { second.get() })
        queue.drain()
        assertEquals(1, (first.get() as Map<*, *>)["rows"])
    }

    @Test
    fun observationPagesFollowTimeThenRowOrderAcrossTiesAndPageBoundaries() {
        val times = listOf(3.0, 1.0, 1.0, 2.0, 1.0, 5.0, 5.0, 5.0, 5.0, 5.0, 5.0, 5.0, 4.0, 1.0, 6.0, 0.0)
        times.forEachIndexed { index, time ->
            telemetry(time, mapOf("humanPowerW" to index.toDouble()))
            store.transaction {
                store.insert(
                    id,
                    time,
                    stamp(time),
                    "location",
                    true,
                    1,
                    location(time) + ("speedMps" to index.toDouble()),
                )
            }
        }
        seal(7.0)
        fun reference(kind: String, column: String) =
            store.readableDatabase
                .rawQuery(
                    "SELECT $column FROM observations WHERE ride=? AND kind=? ORDER BY time,id",
                    arrayOf(id, kind),
                )
                .use { c -> buildList { while (c.moveToNext()) add(c.getDouble(0)) } }
        val power = reference("telemetry", "humanPowerW")
        val speeds = reference("location", "speedMps")
        assertEquals(times.size, power.size)
        for ((pageRows, jobRows) in listOf(1 to 1, 2 to 1, 3 to 2, 17 to 2, 4096 to 1024)) {
            val source = source(pageRows, jobRows)
            for (kind in listOf("zip", "fit")) {
                val session = source.opened(kind)["session"]
                val telemetry = source.pass(session, "telemetry")
                assertCursors(telemetry)
                assertTrue(telemetry.all { (it["rows"] as Int) <= pageRows })
                assertBits(power, telemetry.numbers("humanPowerW"))
                assertBits(times.sorted(), telemetry.numbers("elapsedSeconds"))
                val gps = source.pass(session, "gps")
                assertCursors(gps)
                assertBits(speeds, gps.numbers("speedMps"))
                if (kind == "fit") {
                    val discovery = source.pass(session, "gpsDiscovery")
                    assertCursors(discovery)
                    assertEquals(setOf("producer", "horizontalAccuracyM"), discovery.first().columns().keys)
                    assertEquals(times.size, discovery.numbers("horizontalAccuracyM").size)
                    assertEquals(List(times.size) { "phone" }, discovery.texts("producer"))
                }
                if (kind == "zip") assertEquals(times.sorted().map(::stamp), telemetry.texts("timestamp"))
                source.close(session as String)
            }
        }
    }

    @Test
    fun pagesUseTheStreamIndexSeeks() {
        val observations = "SEARCH observations USING INDEX observations_kind (ride=? AND kind=? AND "
        val lifecycle = "SEARCH lifecycle USING INDEX lifecycle_time (ride=? AND "
        val expected =
            mapOf(
                "telemetry" to listOf("${observations}time>?)", "${observations}time=? AND id>?)"),
                "gps" to listOf("${observations}time>?)", "${observations}time=? AND id>?)"),
                "gpsDiscovery" to listOf("${observations}time>?)", "${observations}time=? AND id>?)"),
                "lifecycle" to listOf("${lifecycle}time>?)", "${lifecycle}time=? AND id>?)"),
                "distance" to
                    listOf(
                        "SEARCH distance_intervals USING PRIMARY KEY (ride=? AND source=? AND (end_us,to_id)>(?,?))"
                    ),
            )
        for ((projection, plans) in expected) {
            val queries = ExportSource.sql(projection)
            val actual = queries.map { sql ->
                assertTrue(sql, sql.endsWith(" LIMIT ?"))
                val args: Array<Any> =
                    when (sql.count { it == '?' }) {
                        3 -> arrayOf(id, 1.0, 10L)
                        4 -> arrayOf(id, 1.0, 2L, 10L)
                        5 -> arrayOf(id, "controller", 1L, 2L, 10L)
                        else -> throw AssertionError(sql)
                    }
                store.select("EXPLAIN QUERY PLAN $sql", args) { c ->
                    buildList { while (c.moveToNext()) add(c.getString(3)) }.joinToString()
                }
            }
            assertEquals(projection, plans, actual)
        }
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun sparseExhaustedAndTiedStreamsUseBoundedJobs() {
        for (time in 0 until 400) telemetry(time * 0.25)
        fix(10.0)
        fix(20.0)
        fix(30.0)
        seal(100.0)
        val database = CountingExecutor()
        val wide = source(pageRows = 8, jobRows = 4, database = database)
        val wideSession = wide.opened("fit")["session"].also { database.tasks = 0 }
        val sparse = wide.paged(wideSession, "gps")
        assertEquals(listOf(3, true), listOf(sparse["rows"], sparse["done"]))
        assertEquals(1, database.tasks)
        database.tasks = 0
        val exhausted = wide.paged(wideSession, "gps", sparse["last"] as List<Double>)
        assertEquals(listOf(0, true, null), listOf(exhausted["rows"], exhausted["done"], exhausted["last"]))
        assertEquals(2, database.tasks)
        val source = source(pageRows = 4, jobRows = 2, database = database)
        val session = source.opened("fit")["session"].also { database.tasks = 0 }
        val page = source.paged(session, "telemetry")
        assertEquals(listOf(4, false), listOf(page["rows"], page["done"]))
        assertEquals(3, database.tasks)
        val tied = ride()
        val rows = (0 until 9).map { telemetry(2.0, ride = tied) }
        telemetry(3.0, ride = tied)
        seal(4.0, ride = tied)
        val tiedSession = source.opened("zip", tied)["session"]
        database.tasks = 0
        val pages = source.pass(tiedSession, "telemetry")
        assertCursors(pages)
        assertEquals(listOf(4, 4, 2), pages.map { it["rows"] })
        assertEquals(
            rows.drop(3).take(1).map { listOf(2.0, it.toDouble()) } + listOf(listOf(2.0, rows[7].toDouble())),
            pages.take(2).map { it["last"] },
        )
        assertEquals(2 + 2 + 2, database.tasks)
    }

    @Test
    fun databaseJobsYieldTheExecutorBetweenQueries() {
        for (time in 0 until 10) telemetry(time.toDouble())
        seal(10.0)
        val queue = QueueExecutor()
        val source = source(pageRows = 10, jobRows = 3, database = queue)
        val reply = Reply()
        source.open(request("zip"), reply)
        queue.drain()
        val session = (reply.get() as Map<*, *>)["session"]
        val page = Reply()
        source.page(mapOf("session" to session, "projection" to "telemetry", "after" to null), page)
        assertEquals(1, queue.queue.size)
        queue.runNext()
        val other = Runnable {}
        queue.execute(other)
        queue.runNext()
        assertSame(other, queue.queue.first())
        assertEquals(2, queue.queue.size)
        assertFalse(page.settled)
        queue.drain()
        assertEquals(10, (page.get() as Map<*, *>)["rows"])
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun telemetryCarriesStoredValuesAndEachConnectionOncePerPass() {
        val full =
            mapOf(
                "humanPowerW" to 150.0,
                "cadenceRpm" to 80.25,
                "motorInputPowerW" to 416.8,
                "batteryVoltageV" to 52.1,
                "batteryCurrentA" to 8.0,
                "motorCurrentA" to 15.0,
                "motorRpm" to 1800.0,
                "pedalTorqueNm" to 25.0,
                "controllerTempC" to 35.5,
                "motorTempC" to 45.0,
                "consumedAh" to 1.25,
                "consumedWh" to 60.0,
                "throttleVoltageV" to 1.2,
                "faultCode" to 0.0,
                "assistLevel" to 3.0,
                "raceMode" to 1.0,
                "speedRaw" to 21.6,
                "controllerSpeedMps" to 6.0,
            )
        telemetry(1.0, full, "epoch-1", "X6|000000|5.3")
        telemetry(1.25, mapOf("humanPowerW" to 0.0), "epoch-1", "X6|000000|5.3", active = false)
        telemetry(1.5, mapOf("cadenceRpm" to 60.0), "epoch-2", "X12|123456AB|5.4")
        telemetry(1.75, mapOf("humanPowerW" to 90.0), "", "")
        telemetry(2.0, mapOf("humanPowerW" to 95.0), "epoch-1", "X6|000000|5.3")
        seal(3.0)
        val source = source(pageRows = 2, jobRows = 1)
        val zip = source.opened("zip")["session"]
        repeat(2) {
            val pages = source.pass(zip, "telemetry")
            assertEquals(
                listOf(
                    listOf(
                        mapOf(
                            "token" to "epoch-1",
                            "vendor" to "controller",
                            "model" to "X6",
                            "firmware" to "000000",
                            "protocol" to "5.3",
                        )
                    ),
                    listOf(
                        mapOf(
                            "token" to "epoch-2",
                            "vendor" to "controller",
                            "model" to "X12",
                            "firmware" to "123456AB",
                            "protocol" to "5.4",
                        )
                    ),
                    emptyList(),
                ),
                pages.map { it["connections"] },
            )
            assertEquals(listOf("epoch-1", "epoch-1", "epoch-2", null, "epoch-1"), pages.texts("connection"))
            assertBits(listOf(1.0, 0.0, 1.0, 1.0, 1.0), pages.numbers("active"))
            assertBits(listOf(1.0, 1.25, 1.5, 1.75, 2.0), pages.numbers("elapsedSeconds"))
            for ((metric, value) in full) assertBits(
                listOf(value, if (metric == "humanPowerW") 0.0 else Double.NaN) +
                    listOf(if (metric == "cadenceRpm") 60.0 else Double.NaN) +
                    listOf(90.0, 95.0).map { if (metric == "humanPowerW") it else Double.NaN },
                pages.numbers(metric),
            )
        }
        val fit = source.opened("fit")["session"]
        val fitPages = source.pass(fit, "telemetry")
        assertEquals(listOf("epoch-1"), (fitPages.first()["connections"] as List<Payload>).map { it["token"] })
        assertFalse(fitPages.first().columns().containsKey("timestamp"))
        assertFalse(fitPages.first().columns().containsKey("speedRaw"))
    }

    @Test
    fun everyProjectionDeliversTheCatalogColumnsOfItsKind() {
        val projections = ExportCatalog.projections
        assertEquals(
            setOf("telemetry", "gps", "gpsDiscovery", "healthZip", "healthFit", "lifecycle", "distance"),
            projections.map { it.name }.toSet(),
        )
        for (time in 0..10) {
            telemetry(time * 0.5)
            fix(time.toDouble())
        }
        seal(11.0)
        val source = source()
        for (kind in listOf("zip", "fit")) {
            val session = source.opened(kind)["session"]
            for (projection in projections) {
                val name = projection.name
                if (kind !in projection.kinds) {
                    assertEquals("$kind $name", "unsupported", rejection { source.paged(session, name) })
                    continue
                }
                val expected = projection.columnsFor("android", kind)
                val page = source.paged(session, name)
                assertEquals("$kind $name", expected.map { it.name }.toSet(), page.columns().keys)
                val rows = page["rows"] as Int
                assertTrue("$kind $name", rows > 0 || name.startsWith("health"))
                for (declaration in expected) {
                    val column = declaration.name
                    val type = declaration.type
                    val value = page.columns()[column]
                    if (type == "number") assertEquals("$kind $name $column", rows * 8, (value as ByteArray).size)
                    else assertEquals("$kind $name $column", rows, (value as List<*>).size)
                }
                assertEquals(name == "telemetry", page.containsKey("connections"))
            }
        }
    }

    @Test
    fun everyNativeRejectionCodeIsAnExportErrorCode() {
        val types = File("../../../src/core/export/types.ts").readText()
        val codes =
            Regex("'(\\w+)'")
                .findAll(types.substringAfter("EXPORT_ERROR_CODES = [").substringBefore("]"))
                .map { it.groupValues[1] }
                .toSet()
        val native =
            listOf("ExportSource.kt", "ExportSink.kt").flatMap { name ->
                val text = File("src/main/java/app/powerlog/bridge/$name").readText()
                Regex("""(?:ExportException|exportFailure)\(\s*(?:error,\s*)?"(\w+)"""")
                    .findAll(text)
                    .map { it.groupValues[1] }
                    .toList()
            }
        assertEquals(
            setOf("gate", "changed", "deleted", "limit", "cancelled", "sink", "unsupported"),
            native.toSet(),
        )
        assertTrue("$native", codes.containsAll(native))
    }

    private fun capturedFix(
        time: Double,
        height: Double?,
        vertical: Float?,
        converter: MslConversion,
    ): AltitudeEvidence {
        val fix =
            Location("").apply {
                latitude = 0.0
                longitude = time * 0.00005
                height?.let { altitude = it }
                vertical?.let { verticalAccuracyMeters = it }
            }
        val evidence = AltitudeEvidence.capture(fix, converter)
        val values = location(time, null, null) + evidence.readings()
        store.transaction {
            val row =
                store.insert(id, time, stamp(time), "location", true, 1, values, "phone", "gps", altitude = evidence)
            distance.append(id, row, time, values, true, 1, "gps", "phone", true)
        }
        return evidence
    }

    @Test
    fun gpsPagesCarryCommittedOriginalAndMslPairsWithoutExportConversion() {
        val conversion = FakeConversion()
        capturedFix(1.0, 120.0, 4f, conversion)
        capturedFix(2.0, 130.5, null, conversion)
        capturedFix(3.0, null, 3f, conversion)
        capturedFix(4.0, -20.25, 2.5f, conversion)
        seal(5.0)
        val source = source(pageRows = 3, jobRows = 2)
        val zip = source.pass(source.opened("zip")["session"], "gps")
        assertBits(listOf(120.0, 130.5, Double.NaN, -20.25), zip.numbers("originalAltitudeMeters"))
        assertBits(listOf(4.0, Double.NaN, 3.0, 2.5), zip.numbers("originalVerticalAccuracyM"))
        assertEquals(List(4) { "wgs84Ellipsoid" }, zip.texts("originalAltitudeDatum"))
        assertBits(listOf(70.0, 80.5, Double.NaN, -70.25), zip.numbers("altitudeMeters"))
        assertBits(listOf(5.0, Double.NaN, Double.NaN, 3.5), zip.numbers("verticalAccuracyM"))
        val fit = source.pass(source.opened("fit")["session"], "gps")
        assertBits(listOf(70.0, 80.5, Double.NaN, -70.25), fit.numbers("altitudeMeters"))
        assertEquals(3, conversion.threads.size)
        assertFalse(fit.first().columns().containsKey("originalAltitudeMeters"))
    }

    @Test
    fun captureConversionFailuresKeepOriginalsAndNeverReuseAStaleMslPair() {
        val originalFix =
            Location("").apply {
                altitude = 100.0
                verticalAccuracyMeters = -1f
            }
        LocationCompat.setMslAltitudeMeters(originalFix, 90.0)
        LocationCompat.setMslAltitudeAccuracyMeters(originalFix, 2f)
        var staleAltitude = true
        var staleAccuracy = true
        val partial =
            AltitudeEvidence.capture(
                originalFix,
                MslConversion {
                    staleAltitude = LocationCompat.hasMslAltitude(it)
                    staleAccuracy = LocationCompat.hasMslAltitudeAccuracy(it)
                    LocationCompat.setMslAltitudeMeters(it, 95.0)
                    throw java.io.IOException("partial conversion")
                },
            )
        assertFalse(staleAltitude)
        assertFalse(staleAccuracy)
        assertNull(partial.altitudeMeters)
        assertNull(partial.verticalAccuracyM)
        assertEquals(90.0, LocationCompat.getMslAltitudeMeters(originalFix), 0.0)
        val failure = MslConversion { throw java.io.IOException("missing map") }
        capturedFix(1.0, 130.0, -1f, failure)
        capturedFix(2.0, 136.0, 21f, failure)
        seal(3.0)
        val source = source()
        val zip = source.pass(source.opened("zip")["session"], "gps")
        assertBits(listOf(130.0, 136.0), zip.numbers("originalAltitudeMeters"))
        assertBits(listOf(-1.0, 21.0), zip.numbers("originalVerticalAccuracyM"))
        assertBits(List(2) { Double.NaN }, zip.numbers("altitudeMeters"))
        assertBits(List(2) { Double.NaN }, zip.numbers("verticalAccuracyM"))
    }

    @Test
    @Config(sdk = [35])
    fun androidXConverterRetainsZeroAccuracyAndProducesFiniteMsl() {
        val location =
            Location("").apply {
                latitude = 0.0
                longitude = 0.0
                altitude = 60.0
                verticalAccuracyMeters = 0f
            }
        val result = AltitudeEvidence.capture(location, mslConversion(RuntimeEnvironment.getApplication()))
        assertEquals(60.0, result.originalAltitudeMeters!!, 0.0)
        assertEquals(0.0, result.originalVerticalAccuracyM!!, 0.0)
        assertNotNull(result.altitudeMeters)
        assertTrue(result.verticalAccuracyM!! >= 0)
    }

    @Test
    fun everyExportPassReadsTheCommittedConversionOnTheDatabaseExecutor() {
        val conversion = FakeConversion()
        for (time in 1..5) capturedFix(time.toDouble(), 120.0, 4f, conversion)
        seal(6.0)
        val source = source(pageRows = 2, jobRows = 1)
        val session = source.opened("fit")["session"]
        source.pass(session, "gpsDiscovery")
        source.pass(session, "gps")
        source.pass(session, "gps")
        source.pass(source.opened("zip")["session"], "gps")
        assertEquals(5, conversion.threads.size)
    }

    @Test
    fun missingAltitudeRetainsAccuracyWithoutCreatingAMslValue() {
        val converter = FakeConversion()
        val result = capturedFix(1.0, null, -2f, converter)
        assertNull(result.originalAltitudeMeters)
        assertEquals(-2.0, result.originalVerticalAccuracyM!!, 0.0)
        assertNull(result.altitudeMeters)
        assertTrue(converter.threads.isEmpty())
    }

    @Test
    fun lifecycleCarriesActionsInStoredOrderAndFlagsTheTerminalStopOfAnInterruptedRide() {
        store.transition(id, RideTiming(10.0, 10.0, stamp(10.0)), "pause")
        store.transition(id, RideTiming(20.0, 10.0, stamp(20.0)), "resume")
        store.transaction { store.lifecycle(id, RideTiming(25.0, 15.0, stamp(25.0)), "lap") }
        store.transition(id, RideTiming(30.0, 20.0, stamp(30.0)), "pause")
        store.transition(id, RideTiming(30.0, 20.0, stamp(30.0)), "resume")
        seal(40.0)
        val interrupted = ride()
        seal(5.0, interrupted = true, ride = interrupted)
        val source = source(pageRows = 2, jobRows = 1)
        val pages = source.pass(source.opened("zip")["session"], "lifecycle")
        assertCursors(pages)
        assertEquals(listOf("start", "pause", "resume", "lap", "pause", "resume", "stop"), pages.texts("action"))
        assertBits(listOf(0.0, 10.0, 20.0, 25.0, 30.0, 30.0, 40.0), pages.numbers("elapsedSeconds"))
        assertEquals(store.events(id).map { it.timestamp }, pages.texts("timestamp"))
        assertEquals(stamp(30.0), pages.texts("timestamp")[5])
        assertBits(List(7) { 0.0 }, pages.numbers("interrupted"))
        assertEquals(List(7) { "phone" }, pages.texts("producer"))
        val stopped = source.pass(source.opened("fit", interrupted)["session"], "lifecycle")
        assertEquals(listOf("start", "stop"), stopped.texts("action"))
        assertBits(listOf(0.0, 1.0), stopped.numbers("interrupted"))
        assertFalse(stopped.first().columns().containsKey("timestamp"))
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun fractionalDistancePagesRetainIntegerMicrosecondsAndObservationIds() {
        id = ride(indoor = true)
        telemetry(3.0)
        val firstId = telemetry(Math.nextUp(4.038999))
        val secondId = telemetry(5.5)
        seal(6.0)
        store.select("SELECT end_us FROM distance_intervals WHERE ride=? AND to_id=?", arrayOf(id, firstId)) { c ->
            assertTrue(c.moveToFirst())
            assertEquals(4038999L, c.getLong(0))
        }
        val source = source(pageRows = 1, jobRows = 1)
        val session = source.opened("fit", distanceSource = "controller")["session"]
        val first = source.paged(session, "distance")
        assertEquals(listOf(4038999.0, firstId.toDouble()), first["last"])
        val second = source.paged(session, "distance", first["last"] as List<Double>)
        assertEquals(listOf(5500000.0, secondId.toDouble()), second["last"])
        val last = source.paged(session, "distance", second["last"] as List<Double>)
        assertEquals(listOf(0, true, null), listOf(last["rows"], last["done"], last["last"]))
        val pages = listOf(first, second, last)
        assertCursors(pages)
        assertEquals(listOf("4038999", "5500000"), pages.texts("endUs"))
        val artifact =
            json(
                mapOf(
                    "pages" to
                        pages.map { page ->
                            page +
                                mapOf(
                                    "columns" to
                                        page.columns().entries.associate { (name, value) ->
                                            name.toString() to
                                                (if (value is ByteArray) Base64.encodeToString(value, Base64.NO_WRAP)
                                                else value)
                                        }
                                )
                        }
                )
            )
        val fixture = File("src/test/resources/distance-export-pages.json")
        if (System.getenv("UPDATE_ANDROID_DISTANCE_EXPORT_FIXTURE") == "1") {
            checkNotNull(fixture.parentFile).mkdirs()
            fixture.writeText(artifact.toString(2) + "\n")
        }
        assertEquals(JSONObject(fixture.readText()).map(), JSONObject(artifact.toString()).map())
    }

    @Test
    fun distanceDeliversTheSelectedProfileIntervalsInEndOrder() {
        for (step in 0..12) telemetry(step * 0.5, mapOf("controllerSpeedMps" to 4.0 + step * 0.25))
        telemetry(9.0, mapOf("controllerSpeedMps" to 7.0))
        telemetry(9.5, mapOf("controllerSpeedMps" to 7.5))
        for (second in 0..6) fix(second.toDouble())
        seal(10.0)
        fun reference(source: String) =
            store.readableDatabase
                .rawQuery(
                    "SELECT start_us,end_us,meters_q/1000000.0,u,v,end_us,to_id FROM distance_intervals WHERE ride=? AND source=? ORDER BY end_us,to_id",
                    arrayOf(id, source),
                )
                .use { c ->
                    buildList {
                        while (c.moveToNext()) add(List(7) { if (c.isNull(it)) Double.NaN else c.getDouble(it) })
                    }
                }
        val source = source(pageRows = 3, jobRows = 2)
        for ((choice, stored) in listOf("controller" to "gps:phone", "auto" to "gps:phone")) {
            val pages = source.pass(source.opened("fit", distanceSource = choice)["session"], "distance")
            assertCursors(pages)
            val expected = reference(stored)
            assertTrue(expected.size > 3)
            val columns = listOf("meters", "startSpeed", "endSpeed")
            assertEquals(expected.map { it[0].toLong().toString() }, pages.texts("startUs"))
            assertEquals(expected.map { it[1].toLong().toString() }, pages.texts("endUs"))
            columns.forEachIndexed { i, column -> assertBits(expected.map { it[i + 2] }, pages.numbers(column)) }
            assertEquals(
                expected.chunked(3).map { it.last().takeLast(2) },
                pages.filter { (it["rows"] as Int) > 0 }.map { it["last"] },
            )
            assertEquals((columns + listOf("startUs", "endUs")).toSet(), pages.first().columns().keys)
        }
        val bare = ride()
        seal(1.0, ride = bare)
        val empty = source.paged(source.opened("fit", bare)["session"], "distance")
        assertEquals(listOf(0, true, null), listOf(empty["rows"], empty["done"], empty["last"]))
    }

    @Test
    fun healthProjectionsExposeTypedEmptyColumnsWithoutInventedSamples() {
        telemetry(1.0)
        seal(2.0)
        val source = source()
        for ((kind, projection) in listOf("zip" to "healthZip", "fit" to "healthFit")) {
            val page = source.paged(source.opened(kind)["session"], projection)
            assertEquals(
                listOf(0, true, null),
                listOf(page["rows"], page["done"], page["last"]),
            )
        }
    }

    @Test
    fun deletionIsBlockedWhileASessionIsOpenAndCloseIsIdempotent() {
        telemetry(1.0)
        seal(2.0)
        val source = source()
        val first = source.opened("zip")["session"] as String
        val second = source.opened("fit")["session"] as String
        assertThrows(IllegalStateException::class.java) { store.remove(id) }
        val reply = Reply()
        source.close(first, reply)
        assertNull(reply.get())
        source.close(first)
        source.close(first)
        assertThrows(IllegalStateException::class.java) { store.remove(id) }
        assertEquals("cancelled", rejection { source.paged(first, "telemetry") })
        assertEquals(1, source.paged(second, "telemetry")["rows"])
        source.close(second)
        source.close("never-opened")
        store.remove(id)
        assertEquals("deleted", rejection { source.opened("zip") })
    }

    @Test
    fun closingDuringAPageCancelsItAndReleasesTheRide() {
        for (time in 0 until 10) telemetry(time.toDouble())
        seal(10.0)
        val queue = QueueExecutor()
        val source = source(pageRows = 10, jobRows = 2, database = queue)
        val opened = Reply()
        source.open(request("zip"), opened)
        queue.drain()
        val session = (opened.get() as Map<*, *>)["session"] as String
        val page = Reply()
        source.page(mapOf("session" to session, "projection" to "telemetry", "after" to null), page)
        queue.runNext()
        source.close(session)
        assertThrows(IllegalStateException::class.java) { store.remove(id) }
        queue.drain()
        assertEquals("cancelled", rejection { page.get() })
        store.remove(id)
        source.closeAll()
    }

    @Test
    fun teardownClosesOpenSessionsAndCancelsOpensStillBeingAdmitted() {
        telemetry(1.0)
        seal(2.0)
        val queue = QueueExecutor()
        val source = source(database = queue)
        val admitted = Reply()
        source.open(request("zip"), admitted)
        queue.drain()
        val session = (admitted.get() as Map<*, *>)["session"]
        val pending = Reply()
        source.open(request("fit"), pending)
        source.closeAll()
        queue.drain()
        assertEquals("cancelled", rejection { pending.get() })
        assertEquals("cancelled", rejection { source.paged(session, "telemetry") })
        store.remove(id)
    }

    @Test
    fun changedHealthMetadataOrOriginalsInvalidateAnExportSnapshot() {
        telemetry(1.0)
        seal(2.0)
        val source = source()
        val before = source.opened("zip")["session"]
        store.savedHealthEvidence(id)
        assertEquals("changed", rejection { source.paged(before, "telemetry") })
        source.close(before as String)
        val session = source.opened("zip")["session"]
        assertEquals(1, source.paged(session, "telemetry")["rows"])
        telemetry(1.5)
        assertEquals("changed", rejection { source.paged(session, "telemetry") })
        source.close(session as String)
    }

    @Test
    fun undecodableStoredDataFailsWithGateAtOpenAndDuringPages() {
        telemetry(1.0)
        seal(2.0)
        val source = source()
        val session = source.opened("zip")["session"]
        val metadata =
            store.select("SELECT metadata FROM rides WHERE id=?", arrayOf(id)) {
                it.moveToFirst()
                JSONObject(it.getString(0)).map()
            }
        store.writableDatabase.execSQL("UPDATE rides SET metadata=? WHERE id=?", arrayOf("{not json", id))
        assertEquals("gate", rejection { source.paged(session, "telemetry") })
        assertEquals("gate", rejection { source.opened("fit") })
        store.writableDatabase.execSQL(
            "UPDATE rides SET metadata=? WHERE id=?",
            arrayOf(JSONObject(metadata).toString(), id),
        )
        assertEquals(1, source.paged(session, "telemetry")["rows"])
    }

    @Test
    fun aRideRemovedBehindTheSessionFailsWithDeleted() {
        telemetry(1.0)
        seal(2.0)
        val source = source()
        val session = source.opened("zip")["session"]
        store.writableDatabase.execSQL("DELETE FROM rides WHERE id=?", arrayOf(id))
        assertEquals("deleted", rejection { source.paged(session, "telemetry") })
        assertEquals("deleted", rejection { source.paged(session, "healthZip") })
        source.close(session as String)
    }

    @Test
    fun pagesStayWithinTheByteCeilingAndRowKeysWithinTheSafeIntegerRange() {
        val long = "x".repeat(400_000)
        for (time in 0 until 6) store.transaction {
            val row =
                store.insert(
                    id,
                    time.toDouble(),
                    stamp(time.toDouble()),
                    "telemetry",
                    true,
                    1,
                    mapOf("humanPowerW" to 1.0),
                )
            store.writableDatabase.execSQL("UPDATE observations SET timestamp=? WHERE id=?", arrayOf(long, row))
        }
        seal(7.0)
        val source = source()
        val pages = source.pass(source.opened("zip")["session"], "telemetry")
        assertEquals(listOf(3, 3), pages.map { it["rows"] })
        pages.forEach { page ->
            val strings =
                page.texts("timestamp").sumOf { it!!.toByteArray().size } +
                    page.texts("connection").sumOf { it?.toByteArray()?.size ?: 0 }
            assertTrue(
                page.columns().values.filterIsInstance<ByteArray>().sumOf { it.size } + strings <=
                    ExportSource.PAGE_BYTES
            )
        }
        val huge = ride()
        store.transaction {
            val row = store.insert(huge, 0.0, stamp(0.0), "telemetry", true, 1, mapOf("humanPowerW" to 1.0))
            store.writableDatabase.execSQL(
                "UPDATE observations SET timestamp=? WHERE id=?",
                arrayOf("x".repeat(1_500_000), row),
            )
        }
        seal(1.0, ride = huge)
        assertEquals("limit", rejection { source.paged(source.opened("zip", huge)["session"], "telemetry") })
        val far = ride()
        store.lifecycle(far, RideTiming(0.5, 0.5, stamp(0.5)), "lap")
        store.writableDatabase.execSQL(
            "UPDATE lifecycle SET id=? WHERE ride=? AND action='lap'",
            arrayOf<Any>(RideStore.MAX_SAFE_REVISION + 1, far),
        )
        seal(1.0, ride = far)
        assertEquals("limit", rejection { source.paged(source.opened("zip", far)["session"], "lifecycle") })
    }
}
