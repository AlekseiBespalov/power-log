package app.powerlog.core.inspection

import app.powerlog.core.analytics.*
import app.powerlog.core.health.HealthCodec
import app.powerlog.core.protocol.*

object InspectionCacheCodec {
    private val real64Pattern = Regex("[0-9a-f]{16}")
    private val real64Fields = ObjectFields("state valueBits")
    private val originalFields = ObjectFields("id event timestamp elapsed originalElapsed value")
    private val continuityFields = ObjectFields("clock connection interruption")
    private val readingFields = ObjectFields("time original active continuity selected accuracy")
    private val admissionFields = ObjectFields("reading valueQ")
    private val sumFields = ObjectFields("positive negative positiveOverflow negativeOverflow")
    private val rangeFields = ObjectFields("start end")
    private val statisticsFields =
        ObjectFields(
            "metric range count sum integral covered minimum maximum previous firstRaw lastRaw overflow bucket predecessor"
        )
    private val plotPointFields = ObjectFields("time original continuity producer sequence segment tieKey")
    private val plotFields =
        ObjectFields("metric range points firstRaw lastRaw leadingBarrier trailingBarrier completeSegments")

    private fun fields(vararg values: Pair<String, Json>) = Obj(values.toMap())

    private fun integer(value: Long) = Str(value.toString())

    private fun nullable(value: Long?) = value?.let(::integer) ?: Nil

    private fun nullableText(value: String?) = value?.let(::Str) ?: Nil

    private fun long(value: Json): Long = value.string().toLong().also { checkValue(it.toString() == value.string()) }

    private fun int(value: Json): Int = long(value).also { checkValue(it in 0..Int.MAX_VALUE.toLong()) }.toInt()

    private fun nullableLong(value: Json) = if (value === Nil) null else long(value)

    private fun nullableString(value: Json) = if (value === Nil) null else value.string()

    private fun document(text: String) =
        JsonCodec.parse(text, arrayLimit = 16384, byteLimit = 16777216, wireNumbers = false)

    private fun document(bytes: ByteArray, binary64: (String) -> Double) =
        JsonCodec.parse(bytes, arrayLimit = 16384, byteLimit = 16777216, wireNumbers = false, binary64 = binary64)

    private fun scalar(value: Json): Scalar {
        val o = value.obj()
        if (o.required("state").string() != "real64") return HealthCodec.fromScalar(value)
        o.closed(real64Fields)
        val bits = o.required("valueBits").string()
        checkValue(real64Pattern.matches(bits))
        return Scalar.Real64(Double.fromBits(bits.toULong(16).toLong()))
    }

    private fun original(value: Original?): Json =
        value?.let {
            fields(
                "id" to Str(it.observationId),
                "event" to Str(it.eventId),
                "timestamp" to Str(it.timestamp),
                "elapsed" to Num(it.elapsedSeconds),
                "originalElapsed" to (it.originalElapsedSeconds?.let(::Num) ?: Nil),
                "value" to HealthCodec.toScalar(it.value),
            )
        } ?: Nil

    private fun original(value: Json): Original? {
        if (value === Nil) return null
        val o = value.obj()
        o.closed(originalFields)
        return Original(
            o.required("id").string(),
            o.required("event").string(),
            o.required("timestamp").string(),
            o.required("elapsed").number(),
            scalar(o.required("value")),
            o.required("originalElapsed").let { if (it === Nil) null else it.number() },
        )
    }

    private fun continuity(value: Continuity) =
        fields(
            "clock" to nullableText(value.clockEpoch),
            "connection" to nullableText(value.connectionEpoch),
            "interruption" to integer(value.interruptionIndex.toLong()),
        )

    private fun continuity(value: Json): Continuity {
        val o = value.obj()
        o.closed(continuityFields)
        return Continuity(
            nullableString(o.required("clock")),
            nullableString(o.required("connection")),
            int(o.required("interruption")),
        )
    }

    private fun reading(value: MetricReading?): Json =
        value?.let {
            require(it.reference == null) { "Persist compact references in the adapter's compact cache" }
            fields(
                "time" to integer(it.timeUs),
                "original" to original(it.original),
                "active" to nullable(it.activeInterval?.toLong()),
                "continuity" to continuity(it.continuity),
                "selected" to Bool(it.selected),
                "accuracy" to (it.accuracy?.let(HealthCodec::toScalar) ?: Nil),
            )
        } ?: Nil

    private fun reading(value: Json): MetricReading? {
        if (value === Nil) return null
        val o = value.obj()
        o.closed(readingFields)
        return MetricReading(
            long(o.required("time")),
            original(o.required("original")),
            o.required("active").let { if (it === Nil) null else int(it) },
            continuity(o.required("continuity")),
            o.required("selected").boolean(),
            o.required("accuracy").let { if (it === Nil) null else scalar(it) },
        )
    }

    private fun admission(value: MetricAdmission?): Json =
        value?.let { fields("reading" to reading(it.reading), "valueQ" to integer(it.valueQ)) } ?: Nil

    private fun admission(value: Json): MetricAdmission? {
        if (value === Nil) return null
        val o = value.obj()
        o.closed(admissionFields)
        return MetricAdmission(requireNotNull(reading(o.required("reading"))), long(o.required("valueQ")))
    }

    private fun sum(value: SignedSum) =
        fields(
            "positive" to integer(value.positive),
            "negative" to integer(value.negative),
            "positiveOverflow" to Bool(value.positiveOverflow),
            "negativeOverflow" to Bool(value.negativeOverflow),
        )

    private fun sum(value: Json): SignedSum {
        val o = value.obj()
        o.closed(sumFields)
        return SignedSum(
                long(o.required("positive")),
                long(o.required("negative")),
                o.required("positiveOverflow").boolean(),
                o.required("negativeOverflow").boolean(),
            )
            .also { checkValue(it.positive >= 0 && it.negative >= 0) }
    }

    private fun range(value: AnalysisRange) = fields("start" to integer(value.startUs), "end" to integer(value.endUs))

    private fun range(value: Json): AnalysisRange {
        val o = value.obj()
        o.closed(rangeFields)
        return AnalysisRange(long(o.required("start")), long(o.required("end")))
    }

    @Throws(ProtocolException::class, IllegalArgumentException::class)
    fun statistics(value: MetricCheckpoint): String = statistics(value, mutableMapOf())

    internal fun statistics(value: MetricCheckpoint, numbers: MutableMap<Long, String>): String =
        JsonCodec.encode(
            fields(
                "metric" to Str(value.metric),
                "range" to range(value.range),
                "count" to integer(value.count),
                "sum" to sum(value.sum),
                "integral" to sum(value.integral),
                "covered" to sum(value.covered),
                "minimum" to admission(value.minimum),
                "maximum" to admission(value.maximum),
                "previous" to admission(value.previous),
                "firstRaw" to reading(value.firstRaw),
                "lastRaw" to reading(value.lastRaw),
                "overflow" to Arr(value.overflowQuantities.sortedBy { it.ordinal }.map { Str(it.name) }),
                "bucket" to Bool(value.bucket),
                "predecessor" to admission(value.leadingPredecessor),
            ),
            numbers,
        )

    @Throws(ProtocolException::class, IllegalArgumentException::class)
    fun readStatistics(text: String): MetricCheckpoint {
        val o = document(text).obj()
        o.closed(statisticsFields)
        val metric = o.required("metric").string()
        InspectionMetrics.policy(metric)
        return MetricCheckpoint(
                metric,
                range(o.required("range")),
                long(o.required("count")),
                sum(o.required("sum")),
                sum(o.required("integral")),
                sum(o.required("covered")),
                admission(o.required("minimum")),
                admission(o.required("maximum")),
                admission(o.required("previous")),
                reading(o.required("firstRaw")),
                reading(o.required("lastRaw")),
                o.required("overflow").array().map { Quantity.valueOf(it.string()) }.toSet(),
                o.required("bucket").boolean(),
                admission(o.required("predecessor")),
            )
            .also {
                checkValue(it.count >= 0 && (it.firstRaw == null) == (it.lastRaw == null))
                checkValue(it.firstRaw == null || requireNotNull(it.lastRaw).timeUs >= it.firstRaw.timeUs)
                val retained =
                    listOfNotNull(
                        it.firstRaw,
                        it.lastRaw,
                        it.minimum?.reading,
                        it.maximum?.reading,
                        it.previous?.reading,
                    )
                checkValue(
                    retained.all { reading ->
                        reading.timeUs >= it.range.startUs &&
                            (if (it.bucket) reading.timeUs < it.range.endUs else reading.timeUs <= it.range.endUs)
                    }
                )
                checkValue(
                    retained.all { reading ->
                        reading.original?.let { original ->
                            val time = FixedPoint.timeUs(original.elapsedSeconds)
                            time >= it.range.startUs &&
                                (if (it.bucket) time < it.range.endUs else time <= it.range.endUs)
                        } != false
                    }
                )
                checkValue(it.leadingPredecessor == null || it.leadingPredecessor.reading.timeUs < it.range.startUs)
            }
    }

    private fun point(value: InspectionPlotPoint) =
        fields(
            "time" to integer(value.point.timeUs),
            "original" to original(value.point.original),
            "continuity" to continuity(value.point.continuity),
            "producer" to Str(value.point.producer),
            "sequence" to integer(value.point.sequence),
            "segment" to integer(value.segment.toLong()),
            "tieKey" to nullable(value.tieKey),
        )

    private fun point(value: Json): InspectionPlotPoint {
        val o = value.obj()
        o.closed(plotPointFields)
        return InspectionPlotPoint(
            InspectionPoint(
                long(o.required("time")),
                requireNotNull(original(o.required("original"))),
                continuity(o.required("continuity")),
                o.required("producer").string(),
                long(o.required("sequence")),
            ),
            int(o.required("segment")),
            nullableLong(o.required("tieKey")),
        )
    }

    @Throws(ProtocolException::class, IllegalArgumentException::class)
    fun plot(value: InspectionPlotAggregate): String = plot(value, mutableMapOf())

    internal fun plot(value: InspectionPlotAggregate, numbers: MutableMap<Long, String>): String =
        JsonCodec.encode(
            fields(
                "metric" to Str(value.metric),
                "range" to range(value.range),
                "points" to Arr(value.points.map(::point)),
                "firstRaw" to nullable(value.firstRawUs),
                "lastRaw" to nullable(value.lastRawUs),
                "leadingBarrier" to Bool(value.leadingBarrier),
                "trailingBarrier" to Bool(value.trailingBarrier),
                "completeSegments" to Bool(value.completeSegments),
            ),
            numbers,
        )

    @Throws(ProtocolException::class, IllegalArgumentException::class)
    fun readPlot(text: String): InspectionPlotAggregate = readPlot(document(text))

    @Throws(ProtocolException::class, IllegalArgumentException::class)
    fun readPlot(bytes: ByteArray, binary64: (String) -> Double): InspectionPlotAggregate =
        readPlot(document(bytes, binary64))

    private fun readPlot(document: Json): InspectionPlotAggregate {
        val o = document.obj()
        o.closed(plotFields)
        return InspectionPlotAggregate(
            o.required("metric").string(),
            range(o.required("range")),
            o.required("points").array().map(::point),
            nullableLong(o.required("firstRaw")),
            nullableLong(o.required("lastRaw")),
            o.required("leadingBarrier").boolean(),
            o.required("trailingBarrier").boolean(),
            o.required("completeSegments").boolean(),
        )
    }
}

class InspectionCacheEncoding {
    private val numbers = linkedMapOf<Long, String>()

    internal val retainedNumberCount: Int
        get() = numbers.size

    fun statistics(value: MetricCheckpoint): String = InspectionCacheCodec.statistics(value, numbers)

    fun plot(value: InspectionPlotAggregate): String = InspectionCacheCodec.plot(value, numbers)
}
