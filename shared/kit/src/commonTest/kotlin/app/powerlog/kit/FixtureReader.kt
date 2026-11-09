package app.powerlog.kit

internal expect fun readFixtureFile(path: String): String

internal fun fixture(name: String): J {
    val root = JsonParser(readFixtureFile("$CONTRACT_FIXTURES/$name")).parse()
    if (!name.startsWith("analytics/") && !name.startsWith("health/")) return root
    val expanded = expandFixture(J.Obj(root.fields() - "shared"), root["shared"])
    return expanded
}

private fun expandFixture(value: J, shared: J, index: Int? = null): J {
    if (value is J.Arr) return J.Arr(value.values.map { expandFixture(it, shared, index) })
    if (value !is J.Obj) return value
    fun expand(child: J): J = expandFixture(child, shared, index)
    value["\$ref"].optionalText()?.let { name ->
        val base = shared[name]
        require(base != J.Null) { "Unknown fixture reference: $name" }
        val expanded = expand(base)
        return if (value["override"] == J.Null) expanded else overlay(expanded, expand(value["override"]))
    }
    if (value["\$repeat"] != J.Null) {
        val spec = value["\$repeat"]
        return J.Arr(List(spec["count"].long().toInt()) { expandFixture(spec["template"], shared, it) })
    }
    if (value["\$concat"] != J.Null) return J.Arr(value["\$concat"].list().flatMap { expand(it).list() })
    if (value["\$entries"] != J.Null) {
        return J.Obj(expand(value["\$entries"]).list().associate { it.list()[0].text() to it.list()[1] })
    }
    if (value["\$linear"] != J.Null) {
        val spec = value["\$linear"]
        val i = requireNotNull(index)
        val start = spec["start"].text()
        val step = spec["step"].text()
        val number =
            if (start.toLongOrNull() != null && step.toLongOrNull() != null)
                (start.toLong() + step.toLong() * i).toString()
            else (start.toDouble() + step.toDouble() * i).toString()
        return spec["prefix"].optionalText()?.let {
            J.Str(it + number + (spec["suffix"].optionalText() ?: ""))
        } ?: J.Num(number)
    }
    if (value["\$time"] != J.Null) {
        val spec = value["\$time"]
        val milliseconds = spec["startMs"].long() + spec["stepMs"].long() * requireNotNull(index)
        require(milliseconds in 0 until 86400000)
        fun digits(number: Long, width: Int = 2): String = number.toString().padStart(width, '0')
        return J.Str(
            spec["date"].text() +
                "T" +
                digits(milliseconds / 3600000) +
                ":" +
                digits(milliseconds / 60000 % 60) +
                ":" +
                digits(milliseconds / 1000 % 60) +
                "." +
                digits(milliseconds % 1000, 3) +
                "Z"
        )
    }
    if (value["\$values"] != J.Null) {
        val column = value["\$values"]
        val values = if (column is J.Arr) column else expand(column)
        return expand(values.list()[requireNotNull(index)])
    }
    if (value["\$cycle"] != J.Null) {
        val cycle = value["\$cycle"].list()
        return expand(cycle[requireNotNull(index) % cycle.size])
    }
    return J.Obj(value.fields.mapValues { expand(it.value) })
}

internal sealed class J {
    data class Obj(val fields: Map<String, J>) : J()

    data class Arr(val values: List<J>) : J()

    data class Str(val value: String) : J()

    data class Num(val text: String) : J()

    data class Bool(val value: Boolean) : J()

    data object Null : J()

    operator fun get(key: String): J = (this as? Obj)?.fields?.get(key) ?: Null

    fun text(): String =
        when (this) {
            is Str -> value
            is Num -> text
            else -> error("Not text: $this")
        }

    fun double(): Double = text().toDouble()

    fun long(): Long = text().toLong()

    fun bool(): Boolean = (this as Bool).value

    fun list(): List<J> = (this as Arr).values

    fun fields(): Map<String, J> = (this as Obj).fields

    fun optionalText(): String? = if (this == Null) null else text()

    fun optionalDouble(): Double? = if (this == Null) null else double()
}

internal class JsonParser(private val source: String) {
    private var index = 0

    fun parse(): J {
        val result = value()
        whitespace()
        require(index == source.length)
        return result
    }

    private fun whitespace() {
        while (index < source.length && source[index].isWhitespace()) index++
    }

    private fun value(): J {
        whitespace()
        return when (source[index]) {
            '{' -> {
                index++
                val result = linkedMapOf<String, J>()
                whitespace()
                if (source[index] != '}')
                    do {
                        whitespace()
                        val key = string()
                        whitespace()
                        require(source[index++] == ':')
                        result[key] = value()
                        whitespace()
                        if (source[index] != ',') break
                        index++
                    } while (true)
                require(source[index++] == '}')
                J.Obj(result)
            }
            '[' -> {
                index++
                val result = mutableListOf<J>()
                whitespace()
                if (source[index] != ']')
                    do {
                        result.add(value())
                        whitespace()
                        if (source[index] != ',') break
                        index++
                    } while (true)
                require(source[index++] == ']')
                J.Arr(result)
            }
            '"' -> J.Str(string())
            't' -> {
                require(source.substring(index, index + 4) == "true")
                index += 4
                J.Bool(true)
            }
            'f' -> {
                require(source.substring(index, index + 5) == "false")
                index += 5
                J.Bool(false)
            }
            'n' -> {
                require(source.substring(index, index + 4) == "null")
                index += 4
                J.Null
            }
            else -> {
                val start = index
                while (index < source.length && source[index] in "-+0123456789.eE") index++
                require(index > start)
                J.Num(source.substring(start, index))
            }
        }
    }

    private fun string(): String {
        require(source[index++] == '"')
        val result = StringBuilder()
        while (source[index] != '"') {
            val c = source[index++]
            if (c != '\\') result.append(c)
            else
                when (val escaped = source[index++]) {
                    '"',
                    '\\',
                    '/' -> result.append(escaped)
                    'b' -> result.append('\b')
                    'f' -> result.append('\u000c')
                    'n' -> result.append('\n')
                    'r' -> result.append('\r')
                    't' -> result.append('\t')
                    'u' -> {
                        result.append(source.substring(index, index + 4).toInt(16).toChar())
                        index += 4
                    }
                    else -> error("Invalid JSON escape")
                }
        }
        index++
        return result.toString()
    }
}

internal fun overlay(base: J, changes: J): J {
    if (base is J.Obj && changes is J.Obj)
        return J.Obj(base.fields + changes.fields.mapValues { (key, value) -> overlay(base[key], value) })
    if (base is J.Arr && changes is J.Arr && base.values.size == changes.values.size)
        return J.Arr(base.values.indices.map { overlay(base.values[it], changes.values[it]) })
    return changes
}
