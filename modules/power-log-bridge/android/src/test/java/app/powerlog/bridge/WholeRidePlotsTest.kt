package app.powerlog.bridge

import app.powerlog.core.analytics.AnalysisRange
import app.powerlog.core.inspection.InspectionPlotReducer
import app.powerlog.core.inspection.InspectionPlotSlots
import app.powerlog.core.telemetry.MetricCatalog
import java.util.Random
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class WholeRidePlotsTest {
    private val startMillis = 1767225600000L

    private fun withRide(indoor: Boolean = true, body: (RideStore, String) -> Unit) =
        RideStore(RuntimeEnvironment.getApplication(), "whole-${UUID.randomUUID()}.sqlite").use { store ->
            val id =
                store.create(
                    RideOptions(indoor, saveToHealth = false, recordGPS = !indoor),
                    RecordingClockReading("2026-01-01T00:00:00.000Z", 0),
                )
            body(store, id)
        }

    private fun telemetry(store: RideStore, id: String, time: Double, values: Map<String, Double>, epoch: String) =
        store.insert(id, time, iso(startMillis + (time * 1000).toLong()), "telemetry", true, 0, values, epoch = epoch)

    private fun plot(
        monitor: RideMonitor,
        store: RideStore,
        id: String,
        metrics: List<String>,
        buckets: Int,
        extra: Payload = emptyMap(),
    ): Map<*, *> {
        val result =
            monitor.query(
                id,
                BridgeInputs.monitor(
                    MonitorOperation.Plot,
                    mapOf(
                        "source" to "workout",
                        "id" to id,
                        "generation" to 0,
                        "expectedRevision" to store.readRevision(id).toString(),
                        "metrics" to metrics,
                        "buckets" to buckets,
                    ) + extra,
                ),
            )
        assertEquals("ok", result["status"])
        return result["series"] as Map<*, *>
    }

    private fun reference(store: RideStore, id: String, column: String, metrics: Int, requested: Int): List<Payload> =
        store.withReadPin(id) {
            val end = maxOf(10.0, store.timing(id).elapsed)
            val buckets = InspectionPlotSlots.buckets(requested, metrics)
            store.analytics.plotRange(
                id,
                column,
                0.0,
                end,
                buckets,
                InspectionPlotSlots.pointLimit(metrics),
                InspectionPlotSlots.alignedSlotUs(AnalysisRange(0, elapsedUs(end)), buckets),
            )
        }

    private fun intervalReference(store: RideStore, id: String, column: String, metrics: Int, requested: Int) =
        store.withReadPin(id) {
            val range = AnalysisRange(0, elapsedUs(maxOf(10.0, store.timing(id).elapsed)))
            val buckets = InspectionPlotSlots.buckets(requested, metrics)
            edgeReduction(
                store,
                id,
                column,
                range,
                buckets,
                InspectionPlotSlots.pointLimit(metrics),
                InspectionPlotSlots.alignedSlotUs(range, buckets),
            )
        }

    private fun edgeReduction(
        store: RideStore,
        id: String,
        column: String,
        range: AnalysisRange,
        buckets: Int,
        pointLimit: Int,
        slotUs: Long?,
    ): List<Payload> {
        val reducer =
            InspectionPlotReducer(
                if (column.endsWith("DistanceMeters")) "distanceMeters" else column,
                range,
                buckets,
                pointLimit,
                alignedSlotUs = slotUs,
            )
        RideDistanceInspection(store)
            .boundaries(id, column, Long.MIN_VALUE / 4, Long.MAX_VALUE / 4)
            .forEach(reducer::appendDistance)
        var segment: Int? = null
        return reducer.result().map { point ->
            val original = point.point.original
            mapOf(
                    "observationId" to original.observationId,
                    "elapsedSeconds" to original.elapsedSeconds,
                    "timestamp" to original.timestamp,
                    "value" to original.value.real(),
                    "startsSegment" to (segment != point.segment),
                )
                .also { segment = point.segment }
        }
    }

    @Test
    fun growingRideReadsMatchAFreshAlignedReductionAndFeedOnlyNewRows() {
        for (buckets in listOf(8, 400)) withRide { store, id ->
            val monitor = RideMonitor(store, RideDistance(store))
            val summaries = store.analytics.summaries
            val random = Random(buckets.toLong())
            val metrics = listOf("humanPowerW", "cadenceRpm")
            var time = 0.0
            var epoch = 0
            store.transaction {
                while (time < buckets + 20.0) {
                    telemetry(store, id, time, mapOf("humanPowerW" to 150.0 + time, "cadenceRpm" to 70.0), "epoch-0")
                    time += 1.0
                }
            }
            for (step in 0 until 96) {
                val rebuilds = summaries.rebuilds.get()
                val fed = summaries.fed.get()
                val scanned = summaries.scanned.get()
                val previous = time
                var rows = 0
                var rebuilt = false
                store.transaction {
                    repeat(1 + random.nextInt(12)) {
                        when (random.nextInt(40)) {
                            0 -> time += 7.0 + random.nextInt(20)
                            1 -> epoch++
                        }
                        time += 0.25
                        val values =
                            when (random.nextInt(12)) {
                                0 -> mapOf("cadenceRpm" to 80.0)
                                1 -> mapOf("humanPowerW" to -1.0, "cadenceRpm" to 60.0)
                                else ->
                                    mapOf(
                                        "humanPowerW" to 100.0 + random.nextInt(300),
                                        "cadenceRpm" to 50.0 + random.nextInt(60),
                                    )
                            }
                        telemetry(store, id, time, values, "epoch-$epoch")
                        rows++
                    }
                }
                when (step % 37) {
                    17 -> {
                        telemetry(store, id, previous * random.nextDouble(), mapOf("humanPowerW" to 999.0), "late")
                        rebuilt = true
                    }
                    29 -> {
                        telemetry(store, id, previous, mapOf("humanPowerW" to 1.0, "cadenceRpm" to 1.0), "equal")
                        rebuilt = true
                    }
                    33 -> {
                        store.lifecycle(id, RideTiming(time + 0.0625, time, iso()), "pause")
                        store.lifecycle(id, RideTiming(time + 0.0625, time, iso()), "resume")
                    }
                    35 -> {
                        val cutoff = RideTiming(previous, previous, iso())
                        store.lifecycle(id, cutoff, "pause", true, evidence = mapOf("controllerSequence" to "0"))
                        store.lifecycle(id, cutoff, "resume")
                        rebuilt = true
                    }
                }
                val series = plot(monitor, store, id, metrics, buckets)
                for (metric in metrics) assertEquals(
                    "step $step $metric",
                    reference(store, id, metric, metrics.size, buckets),
                    series[metric],
                )
                if (rebuilt) assertEquals("step $step", rebuilds + metrics.size, summaries.rebuilds.get())
                else if (rebuilds > 0) {
                    assertEquals("step $step", rebuilds, summaries.rebuilds.get())
                    assertEquals("step $step", fed + rows * metrics.size, summaries.fed.get())
                    assertEquals("step $step", scanned + rows, summaries.scanned.get())
                }
            }
            assertTrue(summaries.merges.get() > 0)
            assertEquals(0L, summaries.fallbacks.get())
        }
    }

    @Test
    fun growingDistanceAndBatteryReadsMatchAFreshAlignedReduction() =
        withRide(indoor = false) { store, id ->
            val monitor = RideMonitor(store, RideDistance(store))
            val distance = RideDistance(store)
            val summaries = store.analytics.summaries
            val random = Random(3)
            val battery = listOf("controllerChargeAh", "controllerEnergyWh", "calculatedChargeAh", "calculatedEnergyWh")
            val metrics =
                listOf("distanceMeters", "gpsDistanceMeters", "controllerDistanceMeters", "speedMps") + battery
            var time = 0.0
            var charge = 1.0
            var energy = 20.0
            var longitude = 0.0
            fun sample(at: Double) {
                charge += 0.001 * random.nextInt(4)
                energy += 0.05 * random.nextInt(4)
                val values =
                    mapOf(
                        "controllerSpeedMps" to 4.0 + random.nextInt(6),
                        "consumedAh" to charge,
                        "consumedWh" to energy,
                        "batteryCurrentA" to 5.0 + random.nextInt(20),
                        "batteryVoltageV" to 50.0,
                    )
                val row =
                    store.insert(
                        id,
                        at,
                        iso(startMillis + (at * 1000).toLong()),
                        "telemetry",
                        true,
                        0,
                        values,
                        "X6|plot|5.3",
                        "first",
                    )
                distance.append(id, row, at, values, true, 0, "first", "X6|plot|5.3", false)
            }
            fun fix(at: Double) {
                longitude += 0.00002 * random.nextInt(4)
                val values =
                    mapOf("latitude" to 0.0, "longitude" to longitude, "horizontalAccuracyM" to 3.0, "speedMps" to 5.0)
                val row = store.insert(id, at, iso(startMillis + (at * 1000).toLong()), "location", true, 0, values)
                distance.append(id, row, at, values, true, 0, "", "", true)
            }
            store.transaction {
                while (time < 90.0) {
                    sample(time)
                    if (time % 1.0 == 0.0) fix(time)
                    time += 0.5
                }
            }
            for (step in 0 until 60) {
                val rebuilds = summaries.rebuilds.get()
                var paused = false
                store.transaction {
                    repeat(1 + random.nextInt(8)) {
                        if (random.nextInt(25) == 0) time += 12.0 + random.nextInt(8)
                        time += 0.5
                        sample(time)
                        if (time % 1.0 == 0.0) fix(time)
                    }
                }
                if (step % 13 == 7) {
                    store.lifecycle(id, RideTiming(time, time, iso()), "pause")
                    store.lifecycle(id, RideTiming(time, time, iso()), "resume")
                    paused = true
                }
                if (step % 13 == 4) {
                    val cutoff = RideTiming(time - 20.0, time - 20.0, iso())
                    store.lifecycle(id, cutoff, "pause", true, evidence = mapOf("controllerSequence" to "0"))
                    store.lifecycle(id, RideTiming(time - 10.0, time - 10.0, iso()), "resume")
                    paused = true
                }
                if (step % 13 == 10) store.transaction { fix(time - 3.5) }
                val late = step % 13 in 10..11 || step % 13 == 4
                val series = plot(monitor, store, id, metrics, 64)
                val selected =
                    if (distance.selected(id, "auto") == "controller") "controllerDistanceMeters"
                    else "gpsDistanceMeters"
                for (metric in metrics) {
                    val column = if (metric == "distanceMeters") selected else metric
                    val expected =
                        if (projectedMetric(column)) intervalReference(store, id, column, metrics.size, 64)
                        else reference(store, id, column, metrics.size, 64)
                    assertEquals("step $step $metric", expected, series[metric])
                }
                if (step > 0 && !late)
                    assertEquals("step $step", rebuilds + if (paused) battery.size else 0, summaries.rebuilds.get())
            }
            assertEquals(0L, summaries.fallbacks.get())
        }

    private fun heart(
        store: RideStore,
        id: String,
        origin: String,
        times: List<Double>,
        page: String,
    ): List<HealthOriginal> {
        var sequence = store.producerHighWater(id, "phone")
        return times.chunked(128).flatMapIndexed { index, chunk ->
            val originals = chunk.map { time ->
                HealthOriginal(
                    Origin(
                        "healthConnect",
                        origin,
                        UUID.randomUUID().toString(),
                        UUID.randomUUID().toString(),
                        "phone",
                        ++sequence,
                    ),
                    "heartRate",
                    "bpm",
                    "rawSeries",
                    iso(startMillis + (time * 1000).toLong()),
                    iso(startMillis + (time * 1000).toLong() + 1000),
                    elapsedUs(time),
                    elapsedUs(time) + 1_000_000,
                    90.0 + time % 40,
                    Association.RideWindow(0, elapsedUs(store.timing(id).elapsed)),
                )
            }
            store.appendHealthPage(
                id,
                originals,
                HealthQueryProgress("healthConnect", "heartRate", "window:$page", "$page-$index"),
            )
            originals
        }
    }

    private fun delete(store: RideStore, id: String, target: HealthOriginal, collectionUs: Long) {
        val tombstone =
            target.copy(
                origin =
                    target.origin.copy(
                        originEventId = UUID.randomUUID().toString(),
                        sequence = store.producerHighWater(id, "phone") + 1,
                    ),
                measure = "tombstone",
                representation = "healthTombstone",
                value = null,
                targetEventId = target.origin.originEventId,
                collectionUs = collectionUs,
                endUs = collectionUs,
            )
        store.appendHealthPage(
            id,
            listOf(tombstone),
            HealthQueryProgress("healthConnect", "heartRate", "window:delete", tombstone.origin.originEventId),
            listOf(HealthChange(target.origin.originEventId, tombstone.origin.originEventId, deleted = true)),
        )
    }

    @Test
    fun heartRateSummariesRebuildForLateSamplesDeletionsAndOriginChanges() = withRide { store, id ->
        val monitor = RideMonitor(store, RideDistance(store))
        val summaries = store.analytics.summaries
        val metrics = listOf("heartRateBpm", "humanPowerW")
        fun ride(from: Int, until: Int) = store.transaction {
            for (second in from..until) telemetry(
                store,
                id,
                second.toDouble(),
                mapOf("humanPowerW" to 100.0 + second % 50),
                "one",
            )
        }
        fun read(label: String, rebuilt: Int) {
            val rebuilds = summaries.rebuilds.get()
            val series = plot(monitor, store, id, metrics, 32)
            for (metric in metrics) assertEquals(label, reference(store, id, metric, metrics.size, 32), series[metric])
            assertEquals(label, rebuilds + rebuilt, summaries.rebuilds.get())
        }
        ride(0, 300)
        val first = heart(store, id, "a.generated", (0 until 200).map { it.toDouble() }, "a1")
        read("first", 2)
        ride(301, 400)
        heart(store, id, "a.generated", (0 until 99).map { 300.5 + it }, "a2")
        read("appended", 0)
        heart(store, id, "a.generated", (200 until 300).map { it.toDouble() }, "a3")
        read("late", 1)
        delete(store, id, first[48], first[48].collectionUs)
        read("deleted at its time", 1)
        ride(401, 420)
        delete(store, id, first[64], elapsedUs(425.0))
        read("deleted after the data end", 1)
        heart(store, id, "b.generated", (0 until 420).map { it.toDouble() }, "b1")
        assertEquals("b.generated", HealthReadings(store).origins(id)["heartRate"])
        read("origin", 1)
        assertEquals(0L, summaries.fallbacks.get())
    }

    @Test
    fun readsPinnedBeforeTheSummaryRevisionUseTheFullPathAndKeepTheSummary() = withRide { store, id ->
        val monitor = RideMonitor(store, RideDistance(store))
        val summaries = store.analytics.summaries
        val metrics = listOf("humanPowerW", "cadenceRpm")
        fun rows(from: Int, until: Int) = store.transaction {
            for (second in from..until) telemetry(
                store,
                id,
                second.toDouble(),
                mapOf("humanPowerW" to 100.0 + second % 70, "cadenceRpm" to 60.0 + second % 30),
                "one",
            )
        }
        rows(0, 300)
        val pin = store.pin(id)
        rows(301, 400)
        plot(monitor, store, id, metrics, 32)
        val rebuilds = summaries.rebuilds.get()
        val fed = summaries.fed.get()
        store.withPin(pin) {
            val series = plot(monitor, store, id, metrics, 32)
            for (metric in metrics) assertEquals(reference(store, id, metric, metrics.size, 32), series[metric])
        }
        assertEquals(metrics.size.toLong(), summaries.fallbacks.get())
        val series = plot(monitor, store, id, metrics, 32)
        for (metric in metrics) assertEquals(reference(store, id, metric, metrics.size, 32), series[metric])
        assertEquals(rebuilds, summaries.rebuilds.get())
        assertEquals(fed, summaries.fed.get())
    }

    @Test
    fun previewWholeReadsUseTheAlignedRuleWithoutSummaries() {
        RideStore(RuntimeEnvironment.getApplication(), null).use { store ->
            val id = store.replaceLive(RecordingClockReading("2026-01-01T00:00:00.000Z", 0))
            store.transaction {
                for (second in 0..120) telemetry(
                    store,
                    id,
                    second.toDouble(),
                    mapOf("humanPowerW" to 100.0 + second % 30),
                    "one",
                )
            }
            val series = plot(RideMonitor(store, RideDistance(store)), store, id, listOf("humanPowerW"), 16)
            assertEquals(reference(store, id, "humanPowerW", 1, 16), series["humanPowerW"])
            assertEquals(0L, store.analytics.summaries.rebuilds.get())
            assertEquals(0L, store.analytics.summaries.merges.get())
        }
    }

    @Test
    fun wholeRideReadsFollowTheSharedSlotRuleAndBudgetFixtures() {
        val fixture = JSONObject(contractFixture("inspection/aligned-slots.json").readText())
        val slots = fixture.getJSONArray("slots")
        for (index in 0 until slots.length()) {
            val case = slots.getJSONObject(index)
            val endUs = case.getString("endUs").toLong()
            if (case.optBoolean("invalid") || case.getString("startUs") != "0" || endUs < 10_000_000) continue
            var end = endUs / 1e6
            while (elapsedUs(end) < endUs) end = Math.nextUp(end)
            while (elapsedUs(end) > endUs) end = Math.nextDown(end)
            withRide { store, id ->
                store.transaction {
                    for (row in 0..1199) telemetry(
                        store,
                        id,
                        if (row == 1199) end else end * row / 1199,
                        mapOf("humanPowerW" to 100.0 + row * 37 % 200),
                        "one",
                    )
                }
                assertEquals(endUs, elapsedUs(store.timing(id).elapsed))
                val buckets = case.getInt("buckets")
                val slot = if (case.isNull("slotUs")) null else case.getString("slotUs").toLong()
                val expected =
                    store.withReadPin(id) {
                        store.analytics.plotRange(id, "humanPowerW", 0.0, end, buckets, 16384, slot)
                    }
                val series = plot(RideMonitor(store, RideDistance(store)), store, id, listOf("humanPowerW"), buckets)
                assertEquals(case.getString("id"), expected, series["humanPowerW"])
            }
        }
        val catalog = listOf("humanPowerW") + MetricCatalog.all.map { it.id }.filter { it != "humanPowerW" }
        withRide { store, id ->
            store.transaction {
                for (second in 0..3600) telemetry(
                    store,
                    id,
                    second.toDouble(),
                    mapOf("humanPowerW" to 100.0 + second * 37 % 200),
                    "one",
                )
            }
            val budgets = fixture.getJSONArray("budgets")
            for (index in 0 until budgets.length()) {
                val case = budgets.getJSONObject(index)
                val metrics = case.getInt("metrics")
                if (case.optBoolean("invalid") || metrics !in 1..32) continue
                val buckets = case.getInt("buckets")
                val expected =
                    store.withReadPin(id) {
                        store.analytics.plotRange(
                            id,
                            "humanPowerW",
                            0.0,
                            3600.0,
                            buckets,
                            case.getInt("pointLimit"),
                            InspectionPlotSlots.alignedSlotUs(AnalysisRange(0, 3_600_000_000), buckets),
                        )
                    }
                for (extra in listOf(emptyMap(), mapOf("startSeconds" to 0.0, "endSeconds" to 3600.75))) {
                    val series =
                        plot(
                            RideMonitor(store, RideDistance(store)),
                            store,
                            id,
                            catalog.take(metrics),
                            case.getInt("requested"),
                            extra,
                        )
                    assertEquals(case.getString("id"), expected, series["humanPowerW"])
                }
            }
        }
    }

    @Test
    fun zoomedReadsKeepSixteenSecondSlotsWithTheMetricBudget() = withRide { store, id ->
        store.transaction {
            for (second in 0..3600) telemetry(
                store,
                id,
                second.toDouble(),
                mapOf("humanPowerW" to 100.0 + second * 37 % 200),
                "one",
            )
        }
        val metrics = listOf("humanPowerW") + MetricCatalog.all.map { it.id }.filter { it != "humanPowerW" }.take(24)
        val monitor = RideMonitor(store, RideDistance(store))
        for ((window, slot) in listOf((100.0 to 3000.0) to 32_000_000L, (0.0 to 1800.0) to null)) {
            val series =
                plot(
                    monitor,
                    store,
                    id,
                    metrics,
                    400,
                    mapOf("startSeconds" to window.first, "endSeconds" to window.second),
                )
            val expected =
                store.withReadPin(id) {
                    store.analytics.plotRange(id, "humanPowerW", window.first, window.second, 161, 655, slot)
                }
            assertEquals("$window", expected, series["humanPowerW"])
        }
        assertEquals(0L, store.analytics.summaries.rebuilds.get())
    }

    @Test
    fun steadyStateReadsOfEveryChannelScanOnlyRowsAfterTheirSummaries() = withRide { store, id ->
        val monitor = RideMonitor(store, RideDistance(store))
        val summaries = store.analytics.summaries
        val channels = MetricCatalog.all.map { it.id }.filter { it in telemetryMetrics }
        fun rows(from: Int, until: Int) = store.transaction {
            for (row in from until until) telemetry(
                store,
                id,
                row / 8.0,
                channels.associateWith { 10.0 + (row * 7 + it.length) % 90 },
                "one",
            )
        }
        rows(0, 8 * 600)
        plot(monitor, store, id, channels, 400)
        var end = elapsedUs(store.timing(id).elapsed)
        for (second in 600 until 605) {
            rows(second * 8, second * 8 + 8)
            val rebuilds = summaries.rebuilds.get()
            val fed = summaries.fed.get()
            val scanned = summaries.scanned.get()
            val scans = mutableListOf<Long>()
            val blocks = mutableListOf<String>()
            store.queryObserver = { sql, args ->
                if ("elapsed_us>=? AND elapsed_us<?" in sql) scans.add(args[1] as Long)
                if ("bucket>=? AND bucket<=?" in sql) blocks.add(sql)
            }
            try {
                plot(monitor, store, id, channels, 400)
            } finally {
                store.queryObserver = null
            }
            assertEquals(rebuilds, summaries.rebuilds.get())
            assertEquals(fed + 8L * channels.size, summaries.fed.get())
            assertEquals(scanned + 8L, summaries.scanned.get())
            assertEquals(emptyList<String>(), blocks)
            assertTrue("$scans", scans.isNotEmpty() && scans.all { it > end })
            end = elapsedUs(store.timing(id).elapsed)
        }
        assertTrue(channels.size >= 12)
        assertEquals(0L, summaries.fallbacks.get())
    }

    @Test
    fun summariesStayWithinTheirPointAndChannelBudgets() {
        RideStore(RuntimeEnvironment.getApplication(), "whole-budget-${UUID.randomUUID()}.sqlite").use { store ->
            val monitor = RideMonitor(store, RideDistance(store))
            val summaries = store.analytics.summaries
            val channels = MetricCatalog.all.map { it.id }.filter { it in telemetryMetrics }
            fun ride(rows: Int, hz: Int, metrics: List<String> = channels): String {
                val id =
                    store.create(
                        RideOptions(true, saveToHealth = false, recordGPS = false),
                        RecordingClockReading("2026-01-01T00:00:00.000Z", 0),
                    )
                store.transaction {
                    for (row in 0 until rows) telemetry(
                        store,
                        id,
                        row.toDouble() / hz,
                        metrics.associateWith { 1.0 + (row * 13 + it.length * 7) % 200 },
                        "one",
                    )
                }
                return id
            }
            val long = ride(2000, 4)
            for (pair in channels.chunked(2) + listOf(channels.take(2))) {
                val series = plot(monitor, store, long, pair, 512)
                for (metric in pair) assertEquals(reference(store, long, metric, pair.size, 512), series[metric])
                assertTrue("${summaries.retainedPoints}", summaries.retainedPoints <= 65_536)
            }
            repeat(70) { plot(monitor, store, ride(24, 1, listOf("humanPowerW")), listOf("humanPowerW"), 8) }
            assertTrue("${summaries.channels}", summaries.channels <= 64)
        }
    }

    @Test
    fun completedRidesKeepTheBlockPathWithTheAlignedRule() = withRide { store, id ->
        store.transaction {
            for (second in 0..900) telemetry(
                store,
                id,
                second.toDouble(),
                mapOf("humanPowerW" to 100.0 + second * 37 % 200),
                "one",
            )
        }
        store.seal(id, RideTiming(900.0, 900.0, iso(startMillis + 900_000)))
        assertEquals("completed", store.metadata(id)["phase"])
        val monitor = RideMonitor(store, RideDistance(store))
        val queries = mutableListOf<String>()
        store.queryObserver = { sql, _ -> if ("bucket>=? AND bucket<=?" in sql) queries.add(sql) }
        val series =
            try {
                plot(monitor, store, id, listOf("humanPowerW"), 64)
            } finally {
                store.queryObserver = null
            }
        assertEquals(reference(store, id, "humanPowerW", 1, 64), series["humanPowerW"])
        assertTrue(queries.isNotEmpty())
        assertEquals(0L, store.analytics.summaries.rebuilds.get())
    }

    @Test
    fun distanceSummariesRebuildWhenAnIntervalStartsBeforeTheirCut() = withRide { store, id ->
        val monitor = RideMonitor(store, RideDistance(store))
        val distance = RideDistance(store)
        val summaries = store.analytics.summaries
        fun sample(at: Double) =
            store.insert(
                id,
                at,
                iso(startMillis + (at * 1000).toLong()),
                "telemetry",
                true,
                0,
                mapOf("controllerSpeedMps" to 5.0),
                "X6|plot|5.3",
                "first",
            )
        store.transaction {
            for (step in 10..240) {
                val at = step / 2.0
                distance.append(
                    id,
                    sample(at),
                    at,
                    mapOf("controllerSpeedMps" to 5.0),
                    true,
                    0,
                    "first",
                    "X6|plot|5.3",
                    false,
                )
            }
        }
        val metrics = listOf("controllerDistanceMeters")
        assertEquals(intervalReference(store, id, metrics[0], 1, 16), plot(monitor, store, id, metrics, 16)[metrics[0]])
        val rebuilds = summaries.rebuilds.get()
        store.transaction {
            val from = sample(1.0)
            val to = sample(2.0)
            store.writableDatabase.execSQL(
                "INSERT INTO distance_intervals(ride,source,start_us,end_us,meters_q,cumulative_q,covered_us,original_meters,from_id,to_id,segment,plot_segment,indivisible,revision,start_anchor,end_anchor) VALUES(?,'controller',1000000,2000000,5000000,5000000,1000000,5.0,?,?,0,0,0,?,'{}','{}')",
                arrayOf(id, from, to, store.revision(id)),
            )
        }
        assertEquals(intervalReference(store, id, metrics[0], 1, 16), plot(monitor, store, id, metrics, 16)[metrics[0]])
        assertEquals(rebuilds + 1, summaries.rebuilds.get())
    }

    @Test
    fun zoomedAndCompletedReadsKeepStartEdgesOfIntervalsThatCrossIntoTheNextBlock() =
        withRide(indoor = false) { store, id ->
            val monitor = RideMonitor(store, RideDistance(store))
            val distance = RideDistance(store)
            val metrics = listOf("gpsDistanceMeters", "controllerDistanceMeters") + consumptionMetrics
            var longitude = 0.0
            fun sample(at: Double) {
                val values =
                    mapOf(
                        "controllerSpeedMps" to 4.0 + at % 3,
                        "consumedAh" to at / 1000,
                        "consumedWh" to at / 20,
                        "batteryCurrentA" to 5.0 + at % 7,
                        "batteryVoltageV" to 50.0,
                    )
                val row =
                    store.insert(
                        id,
                        at,
                        iso(startMillis + (at * 1000).toLong()),
                        "telemetry",
                        true,
                        0,
                        values,
                        "X6|plot|5.3",
                        "first",
                    )
                distance.append(id, row, at, values, true, 0, "first", "X6|plot|5.3", false)
            }
            fun fix(at: Double) {
                longitude += 0.00004
                val values =
                    mapOf("latitude" to 0.0, "longitude" to longitude, "horizontalAccuracyM" to 3.0, "speedMps" to 5.0)
                val row = store.insert(id, at, iso(startMillis + (at * 1000).toLong()), "location", true, 0, values)
                distance.append(id, row, at, values, true, 0, "", "", true)
            }
            for ((from, until) in listOf(0.0 to 291.0, 303.5 to 340.5, 383.5 to 420.5)) store.transaction {
                var second = from
                while (second < until) {
                    sample(second)
                    fix(second)
                    sample(second + 0.5)
                    second += 1.0
                }
            }
            val pointLimit = InspectionPlotSlots.pointLimit(metrics.size)
            for (requested in listOf(64, 400)) {
                val buckets = InspectionPlotSlots.buckets(requested, metrics.size)
                val series =
                    plot(monitor, store, id, metrics, requested, mapOf("startSeconds" to 256.0, "endSeconds" to 420.0))
                val window = AnalysisRange(elapsedUs(256.0), elapsedUs(420.0))
                for (metric in metrics) assertEquals(
                    "zoomed $requested $metric",
                    store.withReadPin(id) { edgeReduction(store, id, metric, window, buckets, pointLimit, null) },
                    series[metric],
                )
            }
            store.seal(id, RideTiming(420.0, 420.0, iso(startMillis + 420_000)))
            for (requested in listOf(64, 400)) {
                val series = plot(monitor, store, id, metrics, requested)
                for (metric in metrics) assertEquals(
                    "completed $requested $metric",
                    intervalReference(store, id, metric, metrics.size, requested),
                    series[metric],
                )
            }
            assertEquals(0L, store.analytics.summaries.rebuilds.get())
        }

    @Test
    fun growingBucketBudgetsMergeTheSameSummaries() = withRide { store, id ->
        store.transaction {
            for (second in 0..3600) telemetry(
                store,
                id,
                second.toDouble(),
                mapOf("humanPowerW" to 100.0 + second * 37 % 200),
                "one",
            )
        }
        val monitor = RideMonitor(store, RideDistance(store))
        val summaries = store.analytics.summaries
        val others = MetricCatalog.all.map { it.id }.filter { it != "humanPowerW" }
        var rebuilds: Long? = null
        for ((count, requested, budget) in listOf(Triple(25, 400, 161), Triple(12, 400, 339), Triple(2, 512, 512))) {
            val metrics = listOf("humanPowerW") + others.take(count - 1)
            assertEquals(budget, InspectionPlotSlots.buckets(requested, metrics.size))
            val series = plot(monitor, store, id, metrics, requested)
            assertEquals("$budget", reference(store, id, "humanPowerW", metrics.size, requested), series["humanPowerW"])
            rebuilds?.let { assertEquals("$budget", it, summaries.rebuilds.get()) }
            rebuilds = summaries.rebuilds.get()
        }
    }

    @Test
    fun everyTelemetryChannelKeepsItsFullSummaryWithinThePointBudget() = withRide { store, id ->
        val monitor = RideMonitor(store, RideDistance(store))
        val summaries = store.analytics.summaries
        val channels = MetricCatalog.all.map { it.id }.filter { it in telemetryMetrics }
        fun rows(from: Int, until: Int) = store.transaction {
            for (row in from until until) telemetry(
                store,
                id,
                row / 4.0,
                channels.associateWith { 10.0 + (row * 37 + it.length) % 90 },
                "one",
            )
        }
        rows(0, 4 * 1020)
        plot(monitor, store, id, channels, 400)
        val built = summaries.rebuilds.get()
        assertTrue("${summaries.retainedPoints}", summaries.retainedPoints > 32_768)
        rows(4 * 1020, 4 * 1022)
        val series = plot(monitor, store, id, channels, 400)
        assertEquals(reference(store, id, channels[0], channels.size, 400), series[channels[0]])
        assertEquals(built, summaries.rebuilds.get())
        assertEquals(channels.size, summaries.channels)
    }

    @Test
    fun overflowingSummariesStayOnTheFullPathUntilTheirProofFails() = withRide { store, id ->
        fun rows(from: Int, until: Int) = store.transaction {
            for (second in from until until) telemetry(
                store,
                id,
                second.toDouble(),
                mapOf("humanPowerW" to 100.0 + second * 37 % 200),
                "one",
            )
        }
        rows(0, 600)
        val monitor = RideMonitor(store, RideDistance(store))
        val summaries = store.analytics.summaries
        summaries.summaryPoints = 64
        fun read(label: String) =
            assertEquals(
                label,
                reference(store, id, "humanPowerW", 1, 64),
                plot(monitor, store, id, listOf("humanPowerW"), 64)["humanPowerW"],
            )
        read("first")
        assertEquals(1L, summaries.rebuilds.get())
        val scanned = summaries.scanned.get()
        for (step in 0 until 3) {
            rows(600 + step * 5, 605 + step * 5)
            read("step $step")
        }
        assertEquals(1L, summaries.rebuilds.get())
        assertEquals(scanned, summaries.scanned.get())
        store.transaction { telemetry(store, id, 100.5, mapOf("humanPowerW" to 400.0), "one") }
        read("late")
        assertEquals(2L, summaries.rebuilds.get())
        rows(615, 620)
        read("after the late row")
        assertEquals(2L, summaries.rebuilds.get())
    }
}
