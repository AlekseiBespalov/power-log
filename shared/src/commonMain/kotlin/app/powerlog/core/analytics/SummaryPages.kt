package app.powerlog.core.analytics

internal interface SummaryTelemetryInput {
    val count: Int

    fun number(index: Int, column: Int): Double

    fun key(index: Int, column: Int): Long

    fun text(index: Int, column: Int): String
}

class SummaryTelemetryPage(val numbers: DoubleArray, val keys: LongArray, val text: List<String>) :
    SummaryTelemetryInput {
    override val count = keys.size / 5

    init {
        require(keys.size % 5 == 0 && count in 0..256 && numbers.size == count * 4 && text.size == count * 6)
    }

    override fun number(index: Int, column: Int) = numbers[index * 4 + column]

    override fun key(index: Int, column: Int) = keys[index * 5 + column]

    override fun text(index: Int, column: Int) = text[index * 6 + column]
}

object SummaryPages {
    @Throws(IllegalArgumentException::class, ArithmeticException::class)
    fun prepare(preparation: SummaryPreparation, page: SummaryTelemetryPage) = prepareInput(preparation, page)

    @Throws(IllegalArgumentException::class, ArithmeticException::class)
    fun compose(composer: SummaryComposer, page: SummaryTelemetryPage) = composeInput(composer, page)

    private fun validate(input: SummaryTelemetryInput) {
        require(input.count in 0..256)
        for (i in 0 until input.count) {
            val flags = input.key(i, 2)
            require(flags in 0L..511L && flags and 3L != 3L && flags and 12L != 12L)
            require(input.number(i, 0).isFinite())
            require(flags and 16L == 0L || input.number(i, 1).isFinite())
        }
    }

    internal fun prepareInput(preparation: SummaryPreparation, input: SummaryTelemetryInput) {
        validate(input)
        for (i in 0 until input.count) {
            preparation.telemetry(input.key(i, 0), input.text(i, 0), input.text(i, 2), input.key(i, 2) and 16L != 0L)
        }
    }

    internal fun composeInput(composer: SummaryComposer, input: SummaryTelemetryInput) {
        validate(input)
        for (i in 0 until input.count) composer.telemetry(input, i)
    }
}
