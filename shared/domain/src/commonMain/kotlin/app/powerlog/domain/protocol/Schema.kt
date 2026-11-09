package app.powerlog.domain.protocol

internal fun decimal(value: Json, positive: Boolean = false, maximum: Long = Long.MAX_VALUE): Long {
    val text = value.string()
    checkValue(text.isNotEmpty() && (text == "0" || text[0] in '1'..'9' && text.all { it in '0'..'9' }))
    val number = text.toLongOrNull() ?: invalid()
    checkValue(number in (if (positive) 1 else 0)..maximum)
    return number
}

internal fun integer(value: Json, low: Int = 0, high: Int = Int.MAX_VALUE): Int {
    val number = value.number()
    checkValue(number >= low && number <= high && number == number.toInt().toDouble())
    return number.toInt()
}

internal fun text(value: Json, maximum: Int = 4096): String =
    value.string().also { checkValue(it.encodeToByteArray().size <= maximum) }

internal fun version(value: Json) {
    if (integer(value) != 1) reject(Rejection.unsupportedVersion)
}
