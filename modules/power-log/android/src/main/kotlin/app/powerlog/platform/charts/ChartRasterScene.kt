package app.powerlog.platform.charts

import kotlin.math.abs
import kotlin.math.floor
import org.json.JSONObject

internal class ChartRasterScene(
    val key: String,
    val source: String,
    val revision: String,
    val start: Double,
    val end: Double,
    val min: Double,
    val max: Double,
    val decimals: Int,
    val laneCount: Int,
    val series: List<Series>,
) {
    data class Point(val time: Double, val value: Double, val start: Boolean)

    data class Series(val id: String, val color: Int, val points: List<Point>, val step: Boolean)

    companion object {
        private const val maximumBytes = 2 * 1024 * 1024
        private const val maximumSeries = 32
        private const val maximumPoints = 16_384
        private val colorPattern = Regex("^#[0-9a-fA-F]{6}$")

        fun parse(json: String, source: String): ChartRasterScene {
            require(json.toByteArray().size <= maximumBytes) { "Chart packet exceeds size limit" }
            val data = JSONObject(json)
            val key = data.getString("key")
            val sourceId = data.getString("sourceId")
            val start = data.getDouble("startS")
            val end = data.getDouble("endS")
            val low = data.getDouble("min")
            val high = data.getDouble("max")
            val decimals = data.getInt("decimals")
            val lanes = data.getInt("laneCount")
            require(
                key.isNotEmpty() &&
                    key.toByteArray().size <= 512 &&
                    sourceId == source &&
                    listOf(start, end, low, high).all { it.isFinite() } &&
                    end - start >= 1e-6 &&
                    high > low &&
                    decimals in 0..8 &&
                    lanes in 1..128
            ) {
                "Invalid chart scene"
            }
            val array = data.getJSONArray("series")
            require(array.length() <= maximumSeries) { "Too many chart series" }
            var total = 0
            val ids = HashSet<String>()
            val series =
                (0 until array.length()).map { index ->
                    val item = array.getJSONObject(index)
                    val id = item.getString("id")
                    val color = item.getString("color")
                    require(id.isNotEmpty() && ids.add(id) && colorPattern.matches(color)) { "Invalid chart series" }
                    val rows = item.getJSONArray("points")
                    total += rows.length()
                    require(total <= maximumPoints) { "Too many chart points" }
                    var previous = Double.NEGATIVE_INFINITY
                    val points =
                        (0 until rows.length()).map { row ->
                            val point = rows.getJSONArray(row)
                            require(point.length() == 3) { "Invalid chart point" }
                            Point(point.getDouble(0), point.getDouble(1), point.getBoolean(2)).also {
                                val us = floor(it.time * 1_000_000)
                                require(
                                    it.time.isFinite() && it.value.isFinite() && abs(it.time) <= 1e12 && us >= previous
                                ) {
                                    "Invalid or unsorted chart point"
                                }
                                previous = us
                            }
                        }
                    Series(id, android.graphics.Color.parseColor(color), points, item.optBoolean("step"))
                }
            return ChartRasterScene(
                key,
                sourceId,
                data.getString("revision"),
                start,
                end,
                low,
                high,
                decimals,
                lanes,
                series,
            )
        }
    }
}
