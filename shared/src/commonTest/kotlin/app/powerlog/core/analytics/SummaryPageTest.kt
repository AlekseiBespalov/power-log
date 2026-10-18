package app.powerlog.core.analytics

import kotlin.test.*

class SummaryPageTest {
    private fun item(index: Int): AnalysisItem {
        val time = index * 125000L
        val power =
            when (index % 17) {
                3 -> Scalar.Real64(-1.0)
                4 -> null
                5 -> Scalar.Real64(0.0625)
                6 -> Scalar.Real64(0.1875)
                else -> Scalar.Integer64(100)
            }
        return AnalysisItem(
            time,
            time / 1000000.0,
            "e$index",
            "o$index",
            "controller",
            index + 1L,
            "telemetry",
            "controller",
            "t$index",
            listOfNotNull(
                    power?.let { "humanPowerW" to it },
                    "cadenceRpm" to Scalar.Real64(80.0625),
                )
                .toMap(),
            time / 1000000.0,
            continuity = Continuity("clock", if (index < 256) "first" else "second"),
        )
    }

    private fun page(items: List<AnalysisItem>): SummaryTelemetryPage {
        val numbers = mutableListOf<Double>()
        val keys = mutableListOf<Long>()
        val text = mutableListOf<String>()
        for (item in items) {
            val p = item.values["humanPowerW"]
            val c = item.values["cadenceRpm"]
            val flags =
                16L +
                    32 +
                    64 +
                    128 +
                    256 +
                    (if (p is Scalar.Integer64) 1 else if (p == null) 2 else 0) +
                    (if (c is Scalar.Integer64) 4 else if (c == null) 8 else 0)
            numbers.addAll(
                listOf(
                    item.collectionElapsedSeconds,
                    item.originalElapsedSeconds ?: Double.NaN,
                    p?.real() ?: Double.NaN,
                    c?.real() ?: Double.NaN,
                )
            )
            keys.addAll(
                listOf(
                    item.collectionUs,
                    item.sequence,
                    flags,
                    (p as? Scalar.Integer64)?.value ?: 0,
                    (c as? Scalar.Integer64)?.value ?: 0,
                )
            )
            text.addAll(
                listOf(
                    item.observationId,
                    item.eventId,
                    item.timestamp,
                    item.producer,
                    item.continuity.clockEpoch.orEmpty(),
                    item.continuity.connectionEpoch.orEmpty(),
                )
            )
        }
        return SummaryTelemetryPage(numbers.toDoubleArray(), keys.toLongArray(), text)
    }

    private fun preparation(lifecycle: List<AnalysisItem> = emptyList()): SummaryPreparation {
        val metadata =
            AnalysisMetadata(
                "ride",
                "start",
                64.0,
                false,
                false,
                false,
                false,
                8,
                completeEncodedMetadata = "{}",
            )
        return SummaryPreparation(AnalysisPin("ride", 1, metadata, "auto")).also { lifecycle.forEach(it::append) }
    }

    @Test
    fun summaryTelemetryPagesMatchOriginalsAcrossBoundariesAndInterruptions() {
        val pause =
            AnalysisItem(
                32000000,
                32.0,
                "pause",
                "pause",
                "phone",
                1,
                "lifecycle",
                "phone",
                "pause",
                action = "pause",
                interrupted = true,
                controllerSequence = 257,
                continuity = Continuity("clock"),
            )
        val resume =
            pause.copy(
                eventId = "resume",
                observationId = "resume",
                action = "resume",
                sequence = 2,
                interrupted = false,
                controllerSequence = null,
            )
        val rows = (0..512).map(::item)
        val referencePreparation = preparation(listOf(pause, resume))
        rows.forEach(referencePreparation::append)
        val prepared = referencePreparation.finish()
        val distance = DistanceInfo("auto", null, emptyList())
        val reference = SummaryComposer(prepared, distance)
        rows.forEach {
            reference.append(
                it.copy(continuity = it.continuity.copy(interruptionIndex = prepared.timeline.interruptionIndex(it)))
            )
        }
        val expected = reference.finish()
        assertIs<SummaryOutcome.Complete>(expected)
        for (size in listOf(1, 2, 17, 128, 256)) {
            val preparation = preparation(listOf(pause, resume))
            rows.chunked(size).forEach { SummaryPages.prepare(preparation, page(it)) }
            val candidatePrepared = preparation.finish()
            assertEquals(prepared.counts, candidatePrepared.counts)
            assertEquals(prepared.eventCount, candidatePrepared.eventCount)
            assertEquals(prepared.actualEndUs, candidatePrepared.actualEndUs)
            assertEquals(prepared.endedAt, candidatePrepared.endedAt)
            assertEquals(prepared.timeline.intervals, candidatePrepared.timeline.intervals)
            assertEquals(prepared.timeline.interruptions, candidatePrepared.timeline.interruptions)
            val candidate = SummaryComposer(candidatePrepared, distance)
            rows.chunked(size).forEach { SummaryPages.compose(candidate, page(it)) }
            assertEquals(expected, candidate.finish(), "page size $size")
        }
    }

    @Test
    fun summaryTelemetryPageValidationIsAtomicAndBounded() {
        assertFailsWith<IllegalArgumentException> { page((0..256).map(::item)) }
        val preparation = preparation()
        val malformed = page(listOf(item(0), item(1)))
        malformed.keys[7] = 3
        assertFailsWith<IllegalArgumentException> { SummaryPages.prepare(preparation, malformed) }
        assertEquals(0L, preparation.finish().eventCount)
        malformed.keys[7] = 497
        malformed.numbers[4] = Double.NaN
        assertFailsWith<IllegalArgumentException> { SummaryPages.prepare(preparation, malformed) }
        assertEquals(0L, preparation.finish().eventCount)
    }

    @Test
    fun mutableCheckedSumsKeepImmutableSnapshotsAndAbsorbingOverflow() {
        for (values in
            listOf(
                listOf(0L, 1, -1, 100, -99),
                listOf(Long.MAX_VALUE, 1, -Long.MAX_VALUE),
                listOf(-Long.MAX_VALUE, -1, Long.MAX_VALUE),
                listOf(Long.MIN_VALUE, 1),
            )) {
            val mutable = CheckedSum()
            var reference = SignedSum()
            for (value in values) {
                val snapshot = mutable.snapshot()
                mutable.add(value)
                reference = reference.add(value)
                assertEquals(reference, mutable.snapshot())
                assertEquals(snapshot, CheckedSum(snapshot).snapshot())
            }
            val snapshot = mutable.snapshot()
            mutable.merge(SignedSum(3, 5, true, true))
            assertEquals(snapshot.merge(SignedSum(3, 5, true, true)), mutable.snapshot())
            assertEquals(snapshot, CheckedSum(snapshot).snapshot())
        }
        val reducer = MetricReducer(Metrics.policy("humanPowerW"), AnalysisRange(0, 1000000))
        fun reading(time: Long) =
            MetricReading(
                time,
                Original(
                    "$time",
                    "$time",
                    "t",
                    time / 1000000.0,
                    Scalar.Integer64(100),
                ),
                0,
                Continuity(),
            )
        reducer.append(reading(0))
        val checkpoint = reducer.checkpoint()!!
        reducer.append(reading(1000000))
        assertEquals(100000L, checkpoint.sum.positive)
        assertEquals(0L, checkpoint.integral.positive)
        assertEquals(200000L, reducer.checkpoint()!!.sum.positive)
        assertEquals(100000000000L, reducer.checkpoint()!!.integral.positive)
    }
}
