package app.powerlog.bridge

import android.location.Location
import android.os.Build
import expo.modules.kotlin.Promise
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
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
import org.robolectric.util.ReflectionHelpers

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
    val source = ExportSource(store, distance, CountingExecutor(), CountingExecutor(), conversion = { null })
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
        location.mslAltitudeMeters = location.altitude - 50
        if (location.hasVerticalAccuracy()) location.mslAltitudeAccuracyMeters = location.verticalAccuracyMeters + 1f
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ExportSourceTest {
    private lateinit var store: RideStore
    private lateinit var distance: RideDistance
    private lateinit var id: String
    private val database = CountingExecutor()
    private val work = CountingExecutor()
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
                SystemRecordingClock.read(),
            )
        store.update(ride, "running", RideTiming(0.0, 0.0, iso(start)), mapOf("startedAt" to iso(start)))
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
        store.seal(ride, RideTiming(elapsed, elapsed - 1, stamp(elapsed)), interrupted)

    private fun source(
        pageRows: Int = ExportSource.PAGE_ROWS,
        jobRows: Int = ExportSource.JOB_ROWS,
        database: Executor = this.database,
        work: Executor = this.work,
        conversion: () -> MslConversion? = { null },
    ) = ExportSource(store, distance, database, work, pageRows, jobRows, conversion = conversion)

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
                        "state" to "notSaved",
                        "workoutUUID" to null,
                        "export" to mapOf("written" to 3, "omitted" to 2, "reason" to "Health is off."),
                    ),
                "watchSyncState" to "notRequired",
                "finalizationState" to "complete",
                "example" to false,
                "sampleHz" to 4,
            ),
            metadata,
        )
        val indoor = ride(indoor = true)
        telemetry(1.0, ride = indoor)
        seal(2.0, interrupted = true, ride = indoor)
        val interrupted = source().opened("fit", indoor)
        assertEquals(mapOf("gps" to emptyList<String>(), "health" to emptyList<String>()), interrupted["producers"])
        assertEquals(true, (interrupted["metadata"] as Payload)["interrupted"])
        assertEquals("partial", (interrupted["metadata"] as Payload)["finalizationState"])
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
        assertNull(source().opened("fit", distanceSource = "gps:watch")["distanceProfile"])
        assertNull(source().opened("zip", distanceSource = "controller")["distanceProfile"])
        val bare = ride()
        seal(1.0, ride = bare)
        assertNull(source().opened("fit", bare)["distanceProfile"])
    }

    @Test
    fun gateAdmitsOnlyCompletedVerifiedRidesAndLeavesNoProtectionBehind() {
        telemetry(1.0)
        assertEquals("gate", rejection { source().opened("zip") })
        seal(2.0)
        val metadata = store.metadata(id)
        store.writableDatabase.execSQL(
            "UPDATE rides SET metadata=? WHERE id=?",
            arrayOf(JSONObject(metadata + mapOf("verifiedSealRevision" to 1)).toString(), id),
        )
        assertEquals("gate", rejection { source().opened("fit") })
        store.writableDatabase.execSQL(
            "UPDATE rides SET metadata=? WHERE id=?",
            arrayOf(JSONObject(metadata - "sealRevision" - "verifiedSealRevision").toString(), id),
        )
        assertEquals("gate", rejection { source().opened("zip") })
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
        assertEquals("unsupported", rejection { source.paged(fit, "distance", listOf(1.0, 2.0)) })
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
                    assertBits(times.sorted(), discovery.numbers("elapsedSeconds"))
                    assertEquals(times.sorted().map(::stamp), discovery.texts("timestamp"))
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
                "distance" to listOf("SEARCH distance_intervals USING PRIMARY KEY (ride=? AND source=? AND end>?)"),
            )
        for ((projection, plans) in expected) {
            val queries = ExportSource.sql(projection)
            val actual = queries.map { sql ->
                assertTrue(sql, sql.endsWith(" LIMIT ?"))
                val args: Array<Any> =
                    when (sql.count { it == '?' }) {
                        3 -> arrayOf(id, 1.0, 10L)
                        4 ->
                            if (projection == "distance") arrayOf(id, "controller", 1.0, 10L)
                            else arrayOf(id, 1.0, 2L, 10L)
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
                            "vendor" to "cyc",
                            "model" to "X6",
                            "firmware" to "000000",
                            "protocol" to "5.3",
                        )
                    ),
                    listOf(
                        mapOf(
                            "token" to "epoch-2",
                            "vendor" to "cyc",
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
        val catalog = File("../../../src/core/export/catalog.ts").readText()
        val groups =
            Regex("""const (\w+) = \[([^\]]*)] as const;""").findAll(catalog).associate { match ->
                match.groupValues[1] to
                    Regex("'(\\w+)'").findAll(match.groupValues[2]).map { it.groupValues[1] }.toSet()
            }
        val body = catalog.substringAfter("export const PROJECTIONS = {").substringBefore("} as const satisfies")
        val projections =
            Regex("""(\w+): \{\s*kinds: (\w+),\s*columns: \[(.*?)],\s*},""", RegexOption.DOT_MATCHES_ALL)
                .findAll(body)
                .map { match ->
                    val columns =
                        Regex("""\{ name: '(\w+)', type: '(\w+)', platforms: (\w+), consumers: (\w+) }""")
                            .findAll(match.groupValues[3])
                            .map {
                                Triple(
                                    it.groupValues[1],
                                    it.groupValues[2],
                                    groups.getValue(it.groupValues[3]) to groups.getValue(it.groupValues[4]),
                                )
                            }
                            .toList()
                    Triple(match.groupValues[1], groups.getValue(match.groupValues[2]), columns)
                }
                .toList()
        assertEquals(
            setOf("telemetry", "gps", "gpsDiscovery", "healthZip", "healthFit", "lifecycle", "distance"),
            projections.map { it.first }.toSet(),
        )
        for (time in 0..10) {
            telemetry(time * 0.5)
            fix(time.toDouble())
        }
        seal(11.0)
        val source = source()
        for (kind in listOf("zip", "fit")) {
            val session = source.opened(kind)["session"]
            val consumers = if (kind == "zip") setOf("zip") else setOf("fit", "discovery")
            for ((name, kinds, columns) in projections) {
                if (kind !in kinds) {
                    assertEquals("$kind $name", "unsupported", rejection { source.paged(session, name) })
                    continue
                }
                val expected = columns.filter { (_, _, scope) ->
                    "android" in scope.first && scope.second.any { it in consumers }
                }
                val page = source.paged(session, name)
                assertEquals("$kind $name", expected.map { it.first }.toSet(), page.columns().keys)
                val rows = page["rows"] as Int
                assertTrue("$kind $name", rows > 0 || name.startsWith("health"))
                for ((column, type) in expected) {
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
                Regex("""(?:ExportException|exportFailure)\((?:error, )?"(\w+)"""")
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

    @Test
    fun gpsPagesCarryTheStoredEllipsoidalPairAndTheConvertedSeaLevelPair() {
        fix(1.0, location(1.0, altitude = 120.0, vertical = 4.0))
        fix(2.0, location(2.0, altitude = 130.5, vertical = null))
        fix(3.0, location(3.0, altitude = null, vertical = 3.0))
        fix(4.0, location(4.0, altitude = -20.25, vertical = 2.5))
        seal(5.0)
        val conversion = FakeConversion()
        val source = source(pageRows = 3, jobRows = 2) { conversion }
        val zip = source.pass(source.opened("zip")["session"], "gps")
        assertBits(listOf(120.0, 130.5, Double.NaN, -20.25), zip.numbers("ellipsoidalAltitudeMeters"))
        assertBits(listOf(4.0, Double.NaN, 3.0, 2.5), zip.numbers("ellipsoidalVerticalAccuracyM"))
        assertBits(listOf(70.0, 80.5, Double.NaN, -70.25), zip.numbers("altitudeMeters"))
        assertBits(listOf(5.0, Double.NaN, Double.NaN, 3.5), zip.numbers("verticalAccuracyM"))
        assertEquals(List(4) { "phone" }, zip.texts("producer"))
        assertEquals(3, conversion.threads.size)
        val fit = source.pass(source.opened("fit")["session"], "gps")
        assertBits(listOf(70.0, 80.5, Double.NaN, -70.25), fit.numbers("altitudeMeters"))
        assertBits(listOf(5.0, Double.NaN, Double.NaN, 3.5), fit.numbers("verticalAccuracyM"))
        assertFalse(fit.first().columns().containsKey("ellipsoidalAltitudeMeters"))
    }

    @Test
    fun conversionFailuresAndMissingConverterLeaveTheSeaLevelPairEmpty() {
        for (time in 1..4) fix(time.toDouble(), location(time.toDouble(), altitude = 100.0 + time, vertical = 2.0))
        seal(5.0)
        val invalid = FakeConversion { if (it.altitude == 102.0) IllegalArgumentException("invalid") else null }
        val first = source { invalid }
        val partial = first.paged(first.opened("zip")["session"], "gps")
        assertBits(listOf(51.0, Double.NaN, 53.0, 54.0), partial.numbers("altitudeMeters"))
        assertBits(listOf(3.0, Double.NaN, 3.0, 3.0), partial.numbers("verticalAccuracyM"))
        assertBits(listOf(101.0, 102.0, 103.0, 104.0), partial.numbers("ellipsoidalAltitudeMeters"))
        val broken = FakeConversion { if (it.altitude >= 102.0) IOException("missing map") else null }
        val stopping = source(pageRows = 2, jobRows = 2) { broken }
        val session = stopping.opened("zip")["session"]
        val pages = stopping.pass(session, "gps")
        assertBits(listOf(51.0, Double.NaN, Double.NaN, Double.NaN), pages.numbers("altitudeMeters"))
        assertBits(listOf(101.0, 102.0, 103.0, 104.0), pages.numbers("ellipsoidalAltitudeMeters"))
        assertEquals(2, broken.threads.size)
        stopping.pass(session, "gps")
        assertEquals(2, broken.threads.size)
        val none = source { null }
        val empty = none.paged(none.opened("zip")["session"], "gps")
        assertBits(List(4) { Double.NaN }, empty.numbers("altitudeMeters"))
        assertBits(List(4) { Double.NaN }, empty.numbers("verticalAccuracyM"))
        assertBits(listOf(2.0, 2.0, 2.0, 2.0), empty.numbers("ellipsoidalVerticalAccuracyM"))
    }

    @Test
    fun platformConverterExistsOnlyFromApi34() {
        val context = RuntimeEnvironment.getApplication()
        assertNotNull(mslConversion(context))
        val sdk = Build.VERSION.SDK_INT
        try {
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", 33)
            assertNull(mslConversion(context))
        } finally {
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", sdk)
        }
    }

    @Test
    fun conversionRunsOncePerSessionOnTheExportExecutorAndNeverForDiscovery() {
        for (time in 1..5) fix(time.toDouble())
        seal(6.0)
        val database = Executors.newSingleThreadExecutor { Thread(it, "test-database") }
        val work = Executors.newSingleThreadExecutor { Thread(it, "test-export") }
        try {
            val conversion = FakeConversion()
            var created = 0
            val source =
                source(pageRows = 2, jobRows = 1, database = database, work = work) {
                    created++
                    conversion
                }
            val session = source.opened("fit")["session"]
            val discovery = source.pass(session, "gpsDiscovery")
            assertEquals(5, discovery.sumOf { it["rows"] as Int })
            assertEquals(0, created)
            assertTrue(conversion.threads.isEmpty())
            source.pass(session, "gps")
            source.pass(session, "gps")
            assertEquals(1, created)
            assertEquals(List(10) { "test-export" }, conversion.threads)
            source.pass(source.opened("zip")["session"], "gps")
            assertEquals(2, created)
        } finally {
            database.shutdownNow()
            work.shutdownNow()
        }
    }

    @Test
    fun platformConversionCostIsMeasuredColdAndWarm() {
        val conversion = checkNotNull(mslConversion(RuntimeEnvironment.getApplication()))
        fun point(index: Int) =
            Location("").apply {
                latitude = 0.001 * index
                longitude = 0.0001 * index
                altitude = 60.0
                verticalAccuracyMeters = 3f
            }
        val cold = point(0)
        val coldStart = System.nanoTime()
        conversion.convert(cold)
        val coldNanos = System.nanoTime() - coldStart
        val warm = (1..2000).map(::point)
        val warmStart = System.nanoTime()
        warm.forEach(conversion::convert)
        val warmNanos = System.nanoTime() - warmStart
        assertTrue(cold.hasMslAltitude() && cold.mslAltitudeMeters.isFinite())
        assertTrue(cold.hasMslAltitudeAccuracy() && cold.mslAltitudeAccuracyMeters >= 3f)
        assertTrue(warm.all { it.hasMslAltitude() && it.hasMslAltitudeAccuracy() })
        println("Robolectric AltitudeConverter: cold ${coldNanos / 1000} µs, warm ${warmNanos / warm.size} ns per fix")
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
    fun distanceDeliversTheSelectedProfileIntervalsInEndOrder() {
        for (step in 0..12) telemetry(step * 0.5, mapOf("controllerSpeedMps" to 4.0 + step * 0.25))
        telemetry(9.0, mapOf("controllerSpeedMps" to 7.0))
        telemetry(9.5, mapOf("controllerSpeedMps" to 7.5))
        for (second in 0..6) fix(second.toDouble())
        seal(10.0)
        fun reference(source: String) =
            store.readableDatabase
                .rawQuery(
                    "SELECT start,end,meters,u,v FROM distance_intervals WHERE ride=? AND source=? ORDER BY end",
                    arrayOf(id, source),
                )
                .use { c ->
                    buildList {
                        while (c.moveToNext()) add(List(5) { if (c.isNull(it)) Double.NaN else c.getDouble(it) })
                    }
                }
        val source = source(pageRows = 3, jobRows = 2)
        for ((choice, stored) in listOf("controller" to "controller", "auto" to "gps:phone")) {
            val pages = source.pass(source.opened("fit", distanceSource = choice)["session"], "distance")
            assertCursors(pages)
            val expected = reference(stored)
            assertTrue(expected.size > 3)
            val columns = listOf("start", "end", "meters", "startSpeed", "endSpeed")
            columns.forEachIndexed { i, column -> assertBits(expected.map { it[i] }, pages.numbers(column)) }
            pages
                .filter { (it["rows"] as Int) > 0 }
                .forEach { assertEquals(listOf(it.numbers("end").last()), it["last"]) }
            assertEquals(columns.toSet(), pages.first().columns().keys)
        }
        val bare = ride()
        seal(1.0, ride = bare)
        val empty = source.paged(source.opened("fit", bare)["session"], "distance")
        assertEquals(listOf(0, true, null), listOf(empty["rows"], empty["done"], empty["last"]))
    }

    @Test
    fun healthProjectionsAreEmptyOnAndroid() {
        telemetry(1.0)
        seal(2.0)
        val source = source()
        for ((kind, projection) in listOf("zip" to "healthZip", "fit" to "healthFit")) {
            val page = source.paged(source.opened(kind)["session"], projection)
            assertEquals(
                listOf(0, true, null, emptyMap<String, Any>()),
                listOf(page["rows"], page["done"], page["last"], page["columns"]),
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
        store.remove(id)
        queue.drain()
        assertEquals("cancelled", rejection { page.get() })
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
    fun changedSealRevisionOrDataFailsTheNextPageButMetadataUpdatesDoNot() {
        telemetry(1.0)
        seal(2.0)
        val source = source()
        val session = source.opened("zip")["session"]
        store.healthStatus(id, HealthExportResult("saved", written = 1))
        assertEquals(1, source.paged(session, "telemetry")["rows"])
        val metadata = store.metadata(id)
        store.writableDatabase.execSQL(
            "UPDATE rides SET metadata=? WHERE id=?",
            arrayOf(JSONObject(metadata + ("sealRevision" to metadata.num("sealRevision") + 1)).toString(), id),
        )
        assertEquals("changed", rejection { source.paged(session, "lifecycle") })
        store.writableDatabase.execSQL(
            "UPDATE rides SET metadata=? WHERE id=?",
            arrayOf(JSONObject(metadata).toString(), id),
        )
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
        val metadata = store.metadata(id)
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
            store.insert(id, time.toDouble(), long, "telemetry", true, 1, mapOf("humanPowerW" to 1.0))
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
            store.insert(huge, 0.0, "x".repeat(1_500_000), "telemetry", true, 1, mapOf("humanPowerW" to 1.0))
        }
        seal(1.0, ride = huge)
        assertEquals("limit", rejection { source.paged(source.opened("zip", huge)["session"], "telemetry") })
        val far = ride()
        store.writableDatabase.execSQL(
            "INSERT INTO lifecycle(id,ride,time,action,timestamp) VALUES(?,?,?,?,?)",
            arrayOf<Any>(RideStore.MAX_SAFE_REVISION + 1, far, 0.5, "lap", stamp(0.5)),
        )
        seal(1.0, ride = far)
        assertEquals("limit", rejection { source.paged(source.opened("zip", far)["session"], "lifecycle") })
    }
}
