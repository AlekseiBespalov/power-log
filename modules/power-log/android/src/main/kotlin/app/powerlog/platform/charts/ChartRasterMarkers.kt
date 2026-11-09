package app.powerlog.platform.charts

import kotlin.math.roundToLong
import org.json.JSONArray
import org.json.JSONObject

internal class ChartSelection(json: JSONObject) {
    data class Tail(val id: String, val startS: Double, val endS: Double, val value: Double)

    val sourceId: String = json.optString("sourceId")
    val revision: String = json.optString("revision")
    val epoch: Double = json.optDouble("epoch")
    val sequence: Double = json.optDouble("sequence")
    val cursorPending: Boolean = json.optBoolean("cursorPending")
    val referenceS: Double? = if (json.isNull("referenceS")) null else json.optDouble("referenceS")
    val points: List<ChartRasterMarkers.Point> = points(json.optJSONArray("points"))
    val references: List<ChartRasterMarkers.Point> = points(json.optJSONArray("references"))
    val tails: List<Tail> =
        json.optJSONArray("tails")?.let { rows ->
            (0 until minOf(32, rows.length())).mapNotNull { index ->
                val tail = rows.optJSONObject(index) ?: return@mapNotNull null
                Tail(
                        tail.optString("id"),
                        tail.optDouble("startS"),
                        tail.optDouble("endS"),
                        tail.optDouble("value"),
                    )
                    .takeIf {
                        it.startS.isFinite() && it.endS.isFinite() && it.value.isFinite() && it.endS >= it.startS
                    }
            }
        } ?: emptyList()

    private fun points(rows: JSONArray?): List<ChartRasterMarkers.Point> =
        if (rows == null) emptyList()
        else (0 until minOf(32, rows.length())).mapNotNull { rows.optJSONObject(it)?.let(ChartRasterMarkers::parse) }

    companion object {
        fun parse(value: String): ChartSelection? = runCatching { ChartSelection(JSONObject(value)) }.getOrNull()
    }
}

internal class ChartTarget(val epoch: Double, val sequence: Double, val points: List<ChartRasterMarkers.Point>?) {
    companion object {
        fun parse(value: String): ChartTarget? {
            val json = runCatching { JSONObject(value) }.getOrNull() ?: return null
            val rows = json.optJSONArray("points")
            val points =
                rows
                    ?.takeIf { it.length() <= 32 }
                    ?.let {
                        (0 until it.length())
                            .map { index ->
                                ChartRasterMarkers.parse(it.optJSONObject(index) ?: return@let null) ?: return@let null
                            }
                            .takeIf { parsed -> parsed.map { point -> point.id }.toSet().size == parsed.size }
                    }
            return ChartTarget(json.optDouble("epoch"), json.optDouble("sequence"), points)
        }
    }
}

internal class ChartRasterMarkers {
    data class Point(val id: String, val atS: Double, val value: Double, val key: String = "")

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

    private fun eligible(nextEpoch: Double, nextSequence: Double): Boolean =
        active && nextEpoch == epoch && nextSequence == sequence && nextSequence > cleared

    fun receive(selection: ChartSelection, metrics: Set<String>) {
        if (selection.sourceId != source || selection.revision != revision) return
        if (!eligible(selection.epoch, selection.sequence)) return
        val next = selection.sequence
        if (next < selected || (next == selected && !pending && selection.cursorPending)) return
        selected = next
        pending = selection.cursorPending
        originals.clear()
        for (point in selection.points) if (point.id in metrics) originals[point.id] = point
    }

    fun target(packet: ChartTarget) {
        if (!eligible(packet.epoch, packet.sequence) || packet.sequence < targeted) return
        val points = packet.points ?: return
        targeted = packet.sequence
        targets = points
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
            packet: ChartSelection,
            reference: Double?,
        ): Boolean =
            presentation.size == 8 &&
                packet.sourceId == source &&
                packet.revision == revision &&
                packet.epoch == presentation[6] &&
                packet.sequence == presentation[7] &&
                reference?.let(::micros) == packet.referenceS?.let(::micros)

        fun parse(value: JSONObject): Point? {
            val atS = value.optDouble("atS")
            val number = value.optDouble("value")
            val id = value.optString("id")
            return if (atS.isFinite() && number.isFinite() && id.isNotEmpty())
                Point(id, atS, number, value.optString("key"))
            else null
        }

        private fun micros(seconds: Double): Long? = if (seconds.isFinite()) (seconds * 1e6).roundToLong() else null
    }
}
