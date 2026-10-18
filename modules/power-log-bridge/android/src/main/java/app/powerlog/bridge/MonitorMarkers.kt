package app.powerlog.bridge

import app.powerlog.core.inspection.*
import org.json.JSONObject

internal class MonitorMarkers {
    data class Point(val id: String, val seconds: Double, val value: Double, val key: String = "")

    private var source = ""
    private var revision = ""
    private var epoch = -1.0
    private var sequence = -1.0
    private var cleared = -1.0
    private var selected = -1.0
    private var pending = true
    private var targeted = -1.0
    private var active = false
    private var targets = emptyList<Point>()
    private val originals = mutableMapOf<String, Point>()

    fun synchronize(source: String, presentation: List<Double>, revision: String = "") {
        if (presentation.size != 8 || (source == this.source && presentation[6] < epoch)) return
        if (source != this.source || presentation[6] != epoch || revision != this.revision) {
            this.source = source
            this.revision = revision
            epoch = presentation[6]
            sequence = -1.0
            cleared = -1.0
            selected = -1.0
            targeted = -1.0
            pending = true
            targets = emptyList()
            originals.clear()
        }
        if (presentation[7] < sequence) return
        sequence = presentation[7]
        active = presentation[2] == 1.0
        if (!active) {
            cleared = sequence
            originals.clear()
            targets = emptyList()
            selected = maxOf(selected, cleared)
            targeted = maxOf(targeted, cleared)
        }
    }

    private fun eligible(packet: JSONObject, selection: Boolean = false): Boolean {
        val nextEpoch = packet.optDouble("epoch")
        val nextSequence = packet.optDouble("sequence")
        if (
            !active ||
                !nextEpoch.isFinite() ||
                !nextSequence.isFinite() ||
                nextEpoch < 0 ||
                nextSequence <= cleared ||
                nextSequence < 0
        )
            return false
        return runCatching {
                val current =
                    InspectionTicket(
                        InspectionContext(
                            source,
                            revision.ifEmpty { "pending" },
                            "auto",
                            epoch.toLong(),
                            sequence.toLong(),
                        ),
                        1,
                    )
                val result =
                    InspectionTicket(
                        InspectionContext(
                            if (selection) packet.optString("sourceId") else source,
                            if (selection) packet.optString("revision").ifEmpty { "pending" }
                            else revision.ifEmpty { "pending" },
                            if (selection) packet.optString("distanceSelection", "auto") else "auto",
                            nextEpoch.toLong(),
                            nextSequence.toLong(),
                        ),
                        if (selection) packet.optLong("generation", 1) else 1,
                    )
                InspectionAcceptance.accepts(current, result)
            }
            .getOrDefault(false)
    }

    fun receive(selection: JSONObject, metrics: Set<String>) {
        if (!eligible(selection, true)) return
        val next = selection.optDouble("sequence")
        if (next < selected || (next == selected && !pending && selection.optBoolean("cursorPending"))) return
        selected = next
        pending = selection.optBoolean("cursorPending")
        val points = selection.optJSONArray("points")
        originals.clear()
        if (points != null)
            for (i in 0 until minOf(points.length(), 32)) {
                val point = parse(points.getJSONObject(i)) ?: continue
                if (point.id in metrics) {
                    originals[point.id] = point
                }
            }
    }

    fun target(packet: JSONObject) {
        if (!eligible(packet) || packet.optDouble("sequence") < targeted) return
        val points = packet.optJSONArray("points") ?: return
        if (points.length() > 32) return
        val parsed = (0 until points.length()).map { parse(points.optJSONObject(it) ?: return) ?: return }
        if (parsed.map { it.id }.toSet().size != parsed.size) return
        targeted = packet.optDouble("sequence")
        targets = parsed
    }

    fun points() = if (active && selected == sequence) originals.values.toList() else emptyList()

    fun previews(acceptance: String): List<Point> =
        if (active && targeted == sequence && (selected != sequence || pending)) targets.filter { it.key == acceptance }
        else emptyList()

    companion object {
        fun acceptsPacket(
            source: String,
            revision: String,
            presentation: List<Double>,
            packet: JSONObject,
            reference: Double?,
        ): Boolean = runCatching {
            val current =
                InspectionTicket(
                    InspectionContext(source, revision, "auto", presentation[6].toLong(), presentation[7].toLong()),
                    1,
                )
            val result =
                InspectionTicket(
                    InspectionContext(
                        packet.getString("sourceId"),
                        packet.getString("revision"),
                        packet.optString("distanceSelection", "auto"),
                        packet.getLong("epoch"),
                        packet.getLong("sequence"),
                    ),
                    packet.optLong("generation", 1),
                )
            val resultReference = if (packet.isNull("referenceSeconds")) null else packet.getDouble("referenceSeconds")
            InspectionAcceptance.acceptsSelection(
                current,
                result,
                reference?.let(::elapsedUs),
                resultReference?.let(::elapsedUs),
            )
        }
            .getOrDefault(false)

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
