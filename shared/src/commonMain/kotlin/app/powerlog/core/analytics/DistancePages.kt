package app.powerlog.core.analytics

internal interface DistanceNumbers {
    val size: Int

    operator fun get(index: Int): Double

    operator fun set(index: Int, value: Double)

    fun array(count: Int): DoubleArray = DoubleArray(count) { get(it) }
}

internal interface DistanceKeys {
    val size: Int

    operator fun get(index: Int): Long

    operator fun set(index: Int, value: Long)

    fun array(count: Int): LongArray = LongArray(count) { get(it) }
}

private class ArrayNumbers(private val values: DoubleArray) : DistanceNumbers {
    override val size
        get() = values.size

    override fun get(index: Int) = values[index]

    override fun set(index: Int, value: Double) {
        values[index] = value
    }

    override fun array(count: Int) = if (count == size) values else values.copyOf(count)
}

private class ArrayKeys(private val values: LongArray) : DistanceKeys {
    override val size
        get() = values.size

    override fun get(index: Int) = values[index]

    override fun set(index: Int, value: Long) {
        values[index] = value
    }

    override fun array(count: Int) = if (count == size) values else values.copyOf(count)
}

class DistanceInputPage
internal constructor(
    internal val numberBuffer: DistanceNumbers,
    internal val keyBuffer: DistanceKeys,
    val text: List<String>,
) {
    constructor(
        numbers: DoubleArray,
        keys: LongArray,
        text: List<String>,
    ) : this(ArrayNumbers(numbers), ArrayKeys(keys), text)

    val numbers
        get() = numberBuffer.array(numberBuffer.size)

    val keys
        get() = keyBuffer.array(keyBuffer.size)

    internal fun count(numberStride: Int, keyStride: Int, textStride: Int): Int {
        require(keyBuffer.size % keyStride == 0)
        val count = keyBuffer.size / keyStride
        require(count <= 128 && numberBuffer.size == count * numberStride && text.size == count * textStride)
        return count
    }
}

class DistancePageProjection
internal constructor(
    internal val numberBuffer: DistanceNumbers,
    internal val keyBuffer: DistanceKeys,
    internal val count: Int,
    val text: List<String>,
    val totalQ: Long,
    val coveredUs: Long,
) {
    val numbers
        get() = numberBuffer.array(count * 9)

    val keys
        get() = keyBuffer.array(count * 2)
}

internal class DistancePageCollector(
    totalQ: Long,
    coveredUs: Long,
    private val numbers: DistanceNumbers = ArrayNumbers(DoubleArray(129 * 9)),
    private val keys: DistanceKeys = ArrayKeys(LongArray(129 * 2)),
) {
    private var total = totalQ
    private var covered = coveredUs
    private var count = 0
    private val text = ArrayList<String>()

    fun append(interval: DistanceInterval?) {
        if (interval == null) return
        val priorTotal = total
        val priorCovered = covered
        total = FixedPoint.add(total, interval.distanceQ)
        covered = FixedPoint.add(covered, FixedPoint.subtract(interval.endUs, interval.startUs))
        val n = count * 9
        val k = count * 2
        numbers[n] = FixedPoint.divide(interval.startUs, 1000000)
        numbers[n + 1] = FixedPoint.divide(interval.endUs, 1000000)
        numbers[n + 2] = FixedPoint.divide(interval.distanceQ, 1000000)
        numbers[n + 3] = FixedPoint.divide(total, 1000000)
        numbers[n + 4] = FixedPoint.divide(covered, 1000000)
        numbers[n + 5] = FixedPoint.divide(priorTotal, 1000000)
        numbers[n + 6] = FixedPoint.divide(priorCovered, 1000000)
        numbers[n + 7] = if (interval.speed0Q == null) Double.NaN else interval.start.value.real()
        numbers[n + 8] = if (interval.speed1Q == null) Double.NaN else interval.end.value.real()
        keys[k] = interval.segment.toLong()
        keys[k + 1] = if (interval.indivisible) 1 else 0
        count++
        text.add(interval.start.eventId)
        text.add(interval.end.eventId)
        text.add(interval.start.timestamp)
        text.add(interval.end.timestamp)
    }

    fun result() = DistancePageProjection(numbers, keys, count, text, total, covered)
}

object DistancePages {
    @Throws(IllegalArgumentException::class, ArithmeticException::class)
    fun controller(
        kernel: ControllerDistanceKernel,
        page: DistanceInputPage,
        totalQ: Long,
        coveredUs: Long,
    ): DistancePageProjection {
        return controller(kernel, page, DistancePageCollector(totalQ, coveredUs))
    }

    internal fun controller(
        kernel: ControllerDistanceKernel,
        page: DistanceInputPage,
        output: DistancePageCollector,
    ): DistancePageProjection {
        val count = page.count(4, 6, 6)
        for (i in 0 until count) {
            val n = i * 4
            val k = i * 6
            val t = i * 6
            fun optionalNumber(offset: Int) = page.numberBuffer[n + offset].takeUnless { it.isNaN() }
            fun optionalText(offset: Int) =
                page.text[t + offset].takeIf { page.keyBuffer[k + 5] and (1L shl offset) != 0L }
            val speed = optionalNumber(1)
            val evidence =
                ControllerDistanceEvidence(
                    page.keyBuffer[k],
                    page.numberBuffer[n],
                    speed,
                    optionalText(0),
                    optionalText(1),
                    optionalText(2),
                    optionalText(3),
                    page.keyBuffer[k + 1].takeIf { it >= 0 }?.toInt(),
                    optionalNumber(2),
                    when (page.keyBuffer[k + 3]) {
                        1L -> Scalar.Integer64(page.keyBuffer[k + 2])
                        2L -> Scalar.Real64(page.numberBuffer[n + 3])
                        else -> null
                    },
                    page.keyBuffer[k + 4] != 0L,
                    Original(
                        page.text[t + 4],
                        page.text[t + 4],
                        page.text[t + 5],
                        page.numberBuffer[n],
                        Scalar.Real64(speed ?: 0.0),
                    ),
                )
            output.append(kernel.append(evidence))
        }
        return output.result()
    }

    @Throws(IllegalArgumentException::class, ArithmeticException::class)
    fun gps(kernel: GPSKernel, page: DistanceInputPage, totalQ: Long, coveredUs: Long): DistancePageProjection {
        return gps(kernel, page, DistancePageCollector(totalQ, coveredUs))
    }

    internal fun gps(
        kernel: GPSKernel,
        page: DistanceInputPage,
        output: DistancePageCollector,
    ): DistancePageProjection {
        val count = page.count(9, 5, 5)
        for (i in 0 until count) {
            val n = i * 9
            val k = i * 5
            val t = i * 5
            fun optionalNumber(offset: Int) = page.numberBuffer[n + offset].takeUnless { it.isNaN() }
            val fix =
                GPSFix.compact(
                    GPSDistanceRecord(
                        page.keyBuffer[k],
                        page.numberBuffer[n],
                        page.text[t],
                        page.text[t + 1],
                        page.text[t + 2],
                        page.text[t + 3],
                        page.keyBuffer[k + 2],
                        page.text[t + 4].takeIf { page.keyBuffer[k + 4] != 0L },
                        page.keyBuffer[k + 3] != 0L,
                        page.numberBuffer[n + 1],
                        page.numberBuffer[n + 2],
                        page.numberBuffer[n + 3],
                        optionalNumber(4),
                        optionalNumber(5),
                        optionalNumber(6),
                        optionalNumber(7),
                        optionalNumber(8),
                    ),
                    page.keyBuffer[k + 1].takeIf { it >= 0 }?.toInt(),
                )
            output.append(kernel.append(fix).interval)
        }
        return output.result()
    }
}
