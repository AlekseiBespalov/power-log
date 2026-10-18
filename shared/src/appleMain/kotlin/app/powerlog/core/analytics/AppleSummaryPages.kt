@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.powerlog.core.analytics

import kotlinx.cinterop.*
import platform.Foundation.NSData

private class AppleSummaryInput(val numbers: NSData, val keys: NSData, private val strings: List<String>) :
    SummaryTelemetryInput {
    override val count = (keys.length.toULong() / 40uL).toInt()
    private val doubles = numbers.bytes?.reinterpret<DoubleVar>()
    private val longs = keys.bytes?.reinterpret<LongVar>()

    init {
        require(
            keys.length.toULong() <= 10240uL &&
                keys.length.toULong() % 40uL == 0uL &&
                count in 0..256 &&
                numbers.length.toULong() == count.toULong() * 32uL &&
                strings.size == count * 6
        )
    }

    override fun number(index: Int, column: Int) = doubles!![index * 4 + column]

    override fun key(index: Int, column: Int) = longs!![index * 5 + column]

    override fun text(index: Int, column: Int) = strings[index * 6 + column]
}

object AppleSummaryPages {
    @Throws(IllegalArgumentException::class, ArithmeticException::class)
    fun prepare(preparation: SummaryPreparation, numbers: NSData, keys: NSData, text: List<String>) {
        SummaryPages.prepareInput(preparation, AppleSummaryInput(numbers, keys, text))
    }

    @Throws(IllegalArgumentException::class, ArithmeticException::class)
    fun compose(composer: SummaryComposer, numbers: NSData, keys: NSData, text: List<String>) {
        SummaryPages.composeInput(composer, AppleSummaryInput(numbers, keys, text))
    }
}
