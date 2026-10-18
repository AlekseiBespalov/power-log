package app.powerlog.core.health

import app.powerlog.core.analytics.FixedPoint

data class RouteEncoding(
    val providerUUID: String?,
    val packageName: String?,
    val appName: String?,
    val clientRecordId: String?,
    val manufacturer: String?,
    val model: String?,
    val notes: String?,
    val title: String?,
    val plannedExerciseSessionId: String?,
    val laps: Int,
    val pauses: Int,
)

// Health Connect rejects a record above 1,000,000 bytes. Sizes follow the API 36 Parcel of an exercise session
// (RecordInternal, ExerciseSessionRecordInternal): 76 fixed bytes, 24 per lap or pause, 48 per route point and padded
// UTF-16 strings.
// https://android.googlesource.com/platform/packages/modules/HealthFitness/+/refs/tags/android-16.0.0_r1/framework/java/android/health/connect/internal/datatypes/
object HealthRoutes {
    @Throws(
        IllegalArgumentException::class,
        ArithmeticException::class,
        app.powerlog.core.protocol.ProtocolException::class,
    )
    fun stringBytes(text: String?): Long =
        if (text == null) 4
        else FixedPoint.add(4, FixedPoint.product((FixedPoint.product(text.length.toLong() + 1, 2) + 3) / 4, 4))

    @Throws(
        IllegalArgumentException::class,
        ArithmeticException::class,
        app.powerlog.core.protocol.ProtocolException::class,
    )
    fun baseBytes(encoding: RouteEncoding): Long {
        require(encoding.laps >= 0 && encoding.pauses >= 0)
        val strings =
            listOf(
                encoding.providerUUID ?: "00000000-0000-0000-0000-000000000000",
                encoding.packageName,
                encoding.appName,
                encoding.clientRecordId,
                encoding.manufacturer,
                encoding.model,
                encoding.notes,
                encoding.title,
                encoding.plannedExerciseSessionId,
            )
        return strings.fold(FixedPoint.add(76, FixedPoint.product(encoding.laps.toLong() + encoding.pauses, 24))) {
            sum,
            s ->
            FixedPoint.add(sum, stringBytes(s))
        }
    }

    private data class Gap(val left: Int, val right: Int)

    @Throws(
        IllegalArgumentException::class,
        ArithmeticException::class,
        app.powerlog.core.protocol.ProtocolException::class,
    )
    fun priority(count: Int): IntArray = priorityPrefix(count, count)

    @Throws(
        IllegalArgumentException::class,
        ArithmeticException::class,
        app.powerlog.core.protocol.ProtocolException::class,
    )
    fun priorityPrefix(count: Int, limit: Int): IntArray {
        require(count >= 0 && limit in 0..count)
        if (limit == 0) return intArrayOf()
        if (count == 0) return intArrayOf()
        if (count == 1) return intArrayOf(0)
        val result = IntArray(limit)
        result[0] = 0
        if (limit == 1) return result
        result[1] = count - 1
        val heap = mutableListOf<Gap>()
        fun before(a: Gap, b: Gap) =
            a.right - a.left > b.right - b.left || a.right - a.left == b.right - b.left && a.left < b.left
        fun push(g: Gap) {
            if (g.right - g.left <= 1) return
            heap.add(g)
            var i = heap.lastIndex
            while (i > 0) {
                val p = (i - 1) / 2
                if (!before(heap[i], heap[p])) break
                val t = heap[i]
                heap[i] = heap[p]
                heap[p] = t
                i = p
            }
        }
        fun pop(): Gap {
            val result = heap[0]
            val last = heap.removeAt(heap.lastIndex)
            if (heap.isNotEmpty()) {
                heap[0] = last
                var i = 0
                while (true) {
                    val l = i * 2 + 1
                    if (l >= heap.size) break
                    val r = l + 1
                    val c = if (r < heap.size && before(heap[r], heap[l])) r else l
                    if (!before(heap[c], heap[i])) break
                    val t = heap[i]
                    heap[i] = heap[c]
                    heap[c] = t
                    i = c
                }
            }
            return result
        }
        push(Gap(0, count - 1))
        var i = 2
        while (heap.isNotEmpty() && i < limit) {
            val g = pop()
            val m = g.left + (g.right - g.left) / 2
            result[i++] = m
            push(Gap(g.left, m))
            push(Gap(m, g.right))
        }
        return result
    }

    @Throws(
        IllegalArgumentException::class,
        ArithmeticException::class,
        app.powerlog.core.protocol.ProtocolException::class,
    )
    fun indices(count: Int, baseBytes: Long?, budgetBytes: Long = 900000): IntArray? {
        if (baseBytes == null || baseBytes < 0 || budgetBytes < 0 || count == 0) return null
        val capacity = (budgetBytes - baseBytes) / 48
        val minimum = minOf(2, count)
        if (budgetBytes < baseBytes || capacity < minimum) return null
        return priorityPrefix(count, minOf(count.toLong(), capacity).toInt()).sorted().toIntArray()
    }

    @Throws(
        IllegalArgumentException::class,
        ArithmeticException::class,
        app.powerlog.core.protocol.ProtocolException::class,
    )
    fun project(
        provider: Provider,
        startUs: Long,
        endUs: Long,
        points: Array<RoutePoint>,
        baseBytes: Long?,
        budgetBytes: Long = 900000,
    ): RouteProjection {
        val base =
            baseBytes
                ?: if (provider == Provider.appleHealth) 0L
                else return RouteProjection.Unavailable(ProjectionFailure.sizeUnknown)
        if (base < 0 || budgetBytes < 0) return RouteProjection.Unavailable(ProjectionFailure.invalidEncoding)
        for (point in points) HealthValidation.validate(point)
        val sorted = points.sortedWith(compareBy<RoutePoint> { it.timeUs }.thenBy { it.originKey })
        val eligible =
            if (provider == Provider.appleHealth) sorted.filter { it.timeUs >= startUs && it.timeUs <= endUs }
            else {
                val s = SecondBuckets.floorDiv(startUs, 1000)
                val e = SecondBuckets.floorDiv(endUs, 1000)
                if (e <= s) return RouteProjection.Unavailable(ProjectionFailure.unrepresentableTime)
                sorted
                    .filter {
                        it.timeUs >= startUs &&
                            it.timeUs < endUs &&
                            SecondBuckets.floorDiv(it.timeUs, 1000) >= s &&
                            SecondBuckets.floorDiv(it.timeUs, 1000) < e
                    }
                    .distinctBy { SecondBuckets.floorDiv(it.timeUs, 1000) }
            }
        if (eligible.isEmpty()) return RouteProjection.Unavailable(ProjectionFailure.unrepresentableTime)
        val chosen =
            if (provider == Provider.appleHealth) eligible.indices.toList()
            else
                indices(eligible.size, base, budgetBytes)?.toList()
                    ?: return RouteProjection.Unavailable(ProjectionFailure.minimumRecordTooLarge)
        val ids = chosen.map { eligible[it].originKey }
        val bytes = FixedPoint.add(base, FixedPoint.product(chosen.size.toLong(), 48))
        return if (chosen.size == points.size) RouteProjection.Full(ids, bytes)
        else RouteProjection.Reduced(ids, points.size.toLong(), eligible.size.toLong(), bytes)
    }
}

class RouteEligibilityCursor(private val provider: Provider, private val startUs: Long, private val endUs: Long) {
    private var previous: RoutePoint? = null
    private var lastProviderMs: Long? = null
    var count: Long = 0
        private set

    @Throws(
        IllegalArgumentException::class,
        ArithmeticException::class,
        app.powerlog.core.protocol.ProtocolException::class,
    )
    fun append(orderedPage: Array<RoutePoint>): Array<RoutePoint> {
        require(orderedPage.size <= 128)
        val output = mutableListOf<RoutePoint>()
        for (point in orderedPage) {
            HealthValidation.validate(point)
            val old = previous
            require(
                old == null ||
                    point.timeUs > old.timeUs ||
                    point.timeUs == old.timeUs && point.originKey > old.originKey
            )
            previous = point
            val ms = SecondBuckets.floorDiv(point.timeUs, 1000)
            val eligible =
                if (provider == Provider.appleHealth) point.timeUs >= startUs && point.timeUs <= endUs
                else
                    point.timeUs >= startUs &&
                        point.timeUs < endUs &&
                        ms >= SecondBuckets.floorDiv(startUs, 1000) &&
                        ms < SecondBuckets.floorDiv(endUs, 1000) &&
                        ms != lastProviderMs
            if (eligible) {
                output.add(point)
                count = FixedPoint.add(count, 1)
                lastProviderMs = ms
            }
        }
        return output.toTypedArray()
    }
}

class RouteRetentionCursor @Throws(IllegalArgumentException::class) constructor(private val selectedIndices: IntArray) {
    private var index = 0L
    private var nextSelected = 0

    init {
        require(selectedIndices.all { it >= 0 } && selectedIndices.toList().zipWithNext().all { (a, b) -> a < b })
    }

    @Throws(
        IllegalArgumentException::class,
        ArithmeticException::class,
        app.powerlog.core.protocol.ProtocolException::class,
    )
    fun append(eligiblePage: Array<RoutePoint>): Array<RoutePoint> {
        require(eligiblePage.size <= 128)
        val output = mutableListOf<RoutePoint>()
        for (point in eligiblePage) {
            if (nextSelected < selectedIndices.size && index == selectedIndices[nextSelected].toLong()) {
                output.add(point)
                nextSelected++
            }
            index = FixedPoint.add(index, 1)
        }
        return output.toTypedArray()
    }

    @Throws(
        IllegalArgumentException::class,
        ArithmeticException::class,
        app.powerlog.core.protocol.ProtocolException::class,
    )
    fun exhausted(): Boolean = nextSelected == selectedIndices.size
}
