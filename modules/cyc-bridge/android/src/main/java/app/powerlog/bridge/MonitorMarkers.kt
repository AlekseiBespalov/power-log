package app.powerlog.bridge

import org.json.JSONObject

/** Retain exact original markers during pending reads, fenced by source, gesture and clear. */
internal class MonitorMarkers {
    data class Point(val id: String, val seconds: Double, val value: Double, val key: String = "")

    private var source = ""
    private var epoch = -1.0
    private var sequence = -1.0
    private var cleared = -1.0
    private var selected = -1.0
    private var pending = true
    private var targeted = -1.0
    private var active = false
    private var target: Point? = null
    private val originals = mutableMapOf<String, Point>()
    private val unavailable = mutableMapOf<String, Double>()

    fun synchronize(source: String, presentation: List<Double>) {
        if (presentation.size != 8 || (source == this.source && presentation[6] < epoch)) return
        if (source != this.source || presentation[6] != epoch) {
            this.source = source
            epoch = presentation[6]
            sequence = -1.0
            cleared = -1.0
            selected = -1.0
            targeted = -1.0
            pending = true
            target = null
            originals.clear()
            unavailable.clear()
        }
        if (presentation[7] < sequence) return
        sequence = presentation[7]
        active = presentation[2] == 1.0
        if (!active) {
            cleared = sequence
            originals.clear()
            unavailable.clear()
            target = null
            selected = maxOf(selected, cleared)
            targeted = maxOf(targeted, cleared)
        }
    }

    private fun eligible(packet: JSONObject) =
        active &&
            packet.optDouble("epoch") == epoch &&
            packet.optDouble("sequence") > cleared &&
            packet.optDouble("sequence") <= sequence

    fun receive(selection: JSONObject, metrics: Set<String>) {
        if (selection.optString("sourceId") != source || !eligible(selection)) return
        val next = selection.optDouble("sequence")
        if (
            next < selected ||
                (next == selected && !pending && selection.optBoolean("cursorPending"))
        )
            return
        selected = next
        pending = selection.optBoolean("cursorPending")
        originals.keys.retainAll(metrics)
        unavailable.keys.retainAll(metrics)
        val points = selection.optJSONArray("points")
        val present = mutableSetOf<String>()
        if (!pending) originals.clear()
        if (points != null)
            for (i in 0 until minOf(points.length(), 32)) {
                val point = parse(points.getJSONObject(i)) ?: continue
                if (point.id in metrics) {
                    originals[point.id] = point
                    present.add(point.id)
                }
            }
        if (!pending)
            for (metric in metrics - present) unavailable[metric] =
                maxOf(unavailable[metric] ?: -1.0, selected)
    }

    fun target(packet: JSONObject) {
        if (!eligible(packet) || packet.optDouble("sequence") < targeted) return
        targeted = packet.optDouble("sequence")
        target = packet.optJSONObject("point")?.let(::parse)
    }

    fun points() = if (active) originals.values.toList() else emptyList()

    fun primary(acceptance: String): Point? = target?.takeIf {
        active && it.key == acceptance && targeted > (unavailable[it.id] ?: -1.0)
    }

    companion object {
        fun parse(value: JSONObject): Point? {
            val time = value.optDouble("seconds")
            val number = value.optDouble("value")
            val id = value.optString("id")
            return if (time.isFinite() && number.isFinite() && id.isNotEmpty())
                Point(id, time, number, value.optString("key"))
            else null
        }
    }
}
