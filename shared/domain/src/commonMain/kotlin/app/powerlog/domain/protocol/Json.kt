package app.powerlog.domain.protocol

enum class Rejection {
    invalidTransition,
    unsupportedVersion,
    unknownKind,
    unknownField,
    duplicateKey,
    wrongEndpoint,
    invalidValue,
    invalidChunk,
    encodedFrameTooLarge,
    compressedBodyTooLarge,
    decodedBodyTooLarge,
    unmatchedReceipt,
}

class ProtocolException(val reason: Rejection) : IllegalArgumentException(reason.name)

internal fun reject(reason: Rejection): Nothing = throw ProtocolException(reason)

internal fun invalid(): Nothing = reject(Rejection.invalidValue)

internal fun checkValue(value: Boolean) {
    if (!value) invalid()
}

internal sealed class Json

internal class ObjectFields(required: String, optional: String = "") {
    val required = required.split(' ').filter { it.isNotEmpty() }
    val allowed = (this.required + optional.split(' ')).toSet()
}

internal data class Obj(val fields: Map<String, Json>) : Json() {
    operator fun get(key: String): Json? = fields[key]

    fun required(key: String): Json = fields[key] ?: invalid()

    fun closed(required: String, optional: String = "") {
        val keys = required.split(' ').filter { it.isNotEmpty() }
        val allowed = keys + optional.split(' ')
        closed(keys, allowed)
    }

    fun closed(shape: ObjectFields) = closed(shape.required, shape.allowed)

    private fun closed(required: List<String>, allowed: Collection<String>) {
        if (fields.keys.any { it !in allowed }) reject(Rejection.unknownField)
        checkValue(required.all { it in fields })
    }
}

internal data class Arr(val values: List<Json>) : Json()

internal data class Str(val value: String) : Json()

internal data class Num(val value: Double) : Json()

internal data class Bool(val value: Boolean) : Json()

internal object Nil : Json()

internal fun Json.obj(): Obj = this as? Obj ?: invalid()

internal fun Json.array(): List<Json> = (this as? Arr)?.values ?: invalid()

internal fun Json.string(): String = (this as? Str)?.value ?: invalid()

internal fun Json.number(): Double = (this as? Num)?.value ?: invalid()

internal object JsonCodec {
    fun parse(
        text: String,
        arrayLimit: Int = 256,
        byteLimit: Int = 716800,
        wireNumbers: Boolean = true,
    ): Json {
        checkUnicode(text)
        checkValue(utf8Bytes(text) <= byteLimit)
        return Parser(text, false, arrayLimit, wireNumbers).parse()
    }

    fun parse(
        bytes: ByteArray,
        fixture: Boolean = false,
        arrayLimit: Int = 256,
        byteLimit: Int = 716800,
        wireNumbers: Boolean = true,
        binary64: ((String) -> Double)? = null,
    ): Json {
        checkValue(fixture || bytes.size <= byteLimit)
        val text =
            try {
                bytes.decodeToString(throwOnInvalidSequence = true)
            } catch (_: CharacterCodingException) {
                invalid()
            }
        return Parser(text, fixture, arrayLimit, wireNumbers, binary64).parse()
    }

    fun encode(value: Json): String = encode(value, mutableMapOf())

    fun encode(value: Json, numbers: MutableMap<Long, String>): String = buildString { appendJson(value, numbers) }

    private fun StringBuilder.appendJson(value: Json, numbers: MutableMap<Long, String>) {
        when (value) {
            is Obj -> {
                append('{')
                for ((index, key) in value.fields.keys.sortedWith(Comparator(::compareScalars)).withIndex()) {
                    if (index > 0) append(',')
                    append(quote(key))
                    append(':')
                    appendJson(value.fields.getValue(key), numbers)
                }
                append('}')
            }
            is Arr -> {
                append('[')
                for ((index, item) in value.values.withIndex()) {
                    if (index > 0) append(',')
                    appendJson(item, numbers)
                }
                append(']')
            }
            is Str -> append(quote(value.value))
            is Num -> {
                val bits = value.value.toBits()
                val encoded =
                    numbers[bits]
                        ?: CanonicalNumber.encode(value.value).also {
                            if (numbers.size >= 128) numbers.remove(numbers.keys.first())
                            numbers[bits] = it
                        }
                append(encoded)
            }
            is Bool -> append(value.value)
            Nil -> append("null")
        }
    }

    fun quote(value: String): String {
        checkUnicode(value)
        return buildString {
            append('"')
            var start = 0
            for (index in value.indices) {
                val escape =
                    when (val c = value[index]) {
                        '"' -> "\\\""
                        '\\' -> "\\\\"
                        '\b' -> "\\b"
                        '\t' -> "\\t"
                        '\n' -> "\\n"
                        '\u000c' -> "\\f"
                        '\r' -> "\\r"
                        else -> if (c.code < 32) "\\u00" + c.code.toString(16).padStart(2, '0') else null
                    }
                if (escape != null) {
                    append(value, start, index)
                    append(escape)
                    start = index + 1
                }
            }
            append(value, start, value.length)
            append('"')
        }
    }

    private fun utf8Bytes(text: String): Long {
        var bytes = 0L
        for (c in text) bytes +=
            when {
                c.code < 128 -> 1
                c.code < 2048 || c.isSurrogate() -> 2
                else -> 3
            }
        return bytes
    }

    private fun compareScalars(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val x = scalar(a, i)
            val y = scalar(b, j)
            if (x != y) return x.compareTo(y)
            i += if (x > 65535) 2 else 1
            j += if (y > 65535) 2 else 1
        }
        return (a.length - i).compareTo(b.length - j)
    }

    private fun scalar(text: String, index: Int): Int =
        if (text[index].isHighSurrogate()) 65536 + ((text[index].code - 0xd800) shl 10) + text[index + 1].code - 0xdc00
        else text[index].code

    internal fun checkUnicode(text: String) {
        var i = 0
        while (i < text.length) {
            if (text[i].isHighSurrogate()) {
                checkValue(i + 1 < text.length && text[i + 1].isLowSurrogate())
                i++
            } else checkValue(!text[i].isLowSurrogate())
            i++
        }
    }

    private class Parser(
        val text: String,
        val fixture: Boolean,
        val arrayLimit: Int,
        val wireNumbers: Boolean,
        val binary64: ((String) -> Double)? = null,
    ) {
        var index = 0
        val numbers = mutableMapOf<String, Double>()

        fun parse(): Json {
            val result = value(0)
            whitespace()
            checkValue(index == text.length)
            return result
        }

        fun whitespace() {
            while (index < text.length && text[index] in " \t\n\r") index++
        }

        fun consume(c: Char): Boolean {
            whitespace()
            if (index < text.length && text[index] == c) {
                index++
                return true
            }
            return false
        }

        fun value(depth: Int): Json {
            checkValue(depth <= if (fixture) 64 else 24)
            whitespace()
            checkValue(index < text.length)
            return when (text[index]) {
                '{' -> {
                    index++
                    val fields = linkedMapOf<String, Json>()
                    if (!consume('}'))
                        do {
                            whitespace()
                            val key = string()
                            checkValue(consume(':'))
                            if (key in fields) reject(Rejection.duplicateKey)
                            fields[key] = value(depth + 1)
                            checkValue(fields.size <= if (fixture) 100000 else 128)
                            if (consume('}')) break
                            checkValue(consume(','))
                        } while (true)
                    Obj(fields)
                }
                '[' -> {
                    index++
                    val items = mutableListOf<Json>()
                    if (!consume(']'))
                        do {
                            items.add(value(depth + 1))
                            checkValue(items.size <= if (fixture) 100000 else arrayLimit)
                            if (consume(']')) break
                            checkValue(consume(','))
                        } while (true)
                    Arr(items)
                }
                '"' -> Str(string())
                't' -> {
                    literal("true")
                    Bool(true)
                }
                'f' -> {
                    literal("false")
                    Bool(false)
                }
                'n' -> {
                    literal("null")
                    Nil
                }
                else -> number()
            }
        }

        fun literal(value: String) {
            checkValue(text.startsWith(value, index))
            index += value.length
        }

        fun string(): String {
            checkValue(index < text.length && text[index++] == '"')
            var start = index
            var result: StringBuilder? = null
            while (index < text.length) {
                val c = text[index++]
                if (c == '"') {
                    val value = result?.append(text, start, index - 1)?.toString() ?: text.substring(start, index - 1)
                    checkUnicode(value)
                    val stringLimit = if (fixture) 4000000 else 716800
                    // Valid UTF-16 uses at most three UTF-8 bytes per code unit.
                    checkValue(value.length <= stringLimit / 3 || utf8Bytes(value) <= stringLimit)
                    return value
                }
                checkValue(c.code >= 32)
                if (c == '\\') {
                    val builder = result ?: StringBuilder().also { result = it }
                    builder.append(text, start, index - 1)
                    checkValue(index < text.length)
                    builder.append(
                        when (val escape = text[index++]) {
                            '"',
                            '\\',
                            '/' -> escape
                            'b' -> '\b'
                            'f' -> '\u000c'
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            'u' -> {
                                checkValue(index + 4 <= text.length)
                                val code = text.substring(index, index + 4)
                                checkValue(code.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
                                index += 4
                                code.toInt(16).toChar()
                            }
                            else -> invalid()
                        }
                    )
                    start = index
                }
            }
            invalid()
        }

        fun number(): Json {
            val start = index
            if (index < text.length && text[index] == '-') index++
            checkValue(index < text.length)
            if (text[index] == '0') index++
            else {
                checkValue(text[index] in '1'..'9')
                while (index < text.length && text[index] in '0'..'9') index++
            }
            if (index < text.length && text[index] == '.') {
                index++
                digits()
            }
            if (index < text.length && text[index] in "eE") {
                index++
                if (index < text.length && text[index] in "+-") index++
                digits()
            }
            val token = text.substring(start, index)
            val value =
                numbers[token]
                    ?: CanonicalNumber.parse(token, !fixture && wireNumbers, binary64).also {
                        if (numbers.size < 128) numbers[token] = it
                    }
            return Num(value)
        }

        fun digits() {
            val start = index
            while (index < text.length && text[index] in '0'..'9') index++
            checkValue(index > start)
        }
    }
}
