package app.powerlog.domain.analytics

internal fun overlay(base: J, changes: J): J {
    if (base is J.Obj && changes is J.Obj)
        return J.Obj(base.fields + changes.fields.mapValues { (key, value) -> overlay(base[key], value) })
    if (base is J.Arr && changes is J.Arr && base.values.size == changes.values.size)
        return J.Arr(base.values.indices.map { overlay(base.values[it], changes.values[it]) })
    return changes
}

internal fun utcUs(value: String): Long {
    val date = value.substring(0, 10).split('-').map { it.toLong() }
    val year = date[0] - if (date[1] <= 2) 1 else 0
    val era = year / 400
    val y = year - era * 400
    val month = date[1] + if (date[1] > 2) -3 else 9
    val days = era * 146097 + y * 365 + y / 4 - y / 100 + (153 * month + 2) / 5 + date[2] - 1 - 719468
    val seconds =
        days * 86400 +
            value.substring(11, 13).toLong() * 3600 +
            value.substring(14, 16).toLong() * 60 +
            value.substring(17, 19).toLong()
    val fraction =
        if (value.getOrNull(19) == '.') value.substring(20).takeWhile { it.isDigit() }.padEnd(6, '0').take(6).toLong()
        else 0
    return seconds * 1000000 + fraction
}
