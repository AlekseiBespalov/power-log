@file:OptIn(ExperimentalJsExport::class)

package app.powerlog.kit

import kotlin.js.Date
import kotlin.math.floor

@JsExport
public fun rideDate(utcUs: Double, nowUtcUs: Double, uses24h: Boolean, locale: String): String {
    val value = Date(floor(utcUs / 1_000))
    val now = Date(floor(nowUtcUs / 1_000))
    val date: dynamic = js("({ weekday: 'short', month: 'short', day: 'numeric' })")
    if (value.getFullYear() != now.getFullYear()) date.year = "numeric"
    val time: dynamic = js("({ hour: 'numeric', minute: '2-digit' })")
    if (uses24h) time.hourCycle = "h23" else time.hour12 = true
    val day: String = value.asDynamic().toLocaleDateString(locale, date) as String
    val clock: String = value.asDynamic().toLocaleTimeString(locale, time) as String
    return "$day · $clock"
}

@JsExport public fun integer(value: Double, locale: String): String = value.asDynamic().toLocaleString(locale) as String
