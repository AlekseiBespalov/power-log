package app.powerlog.bridge

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.*

internal class RideExport(
    private val context: Context,
    private val store: RideStore,
    private val distance: RideDistance,
    private val monitor: RideMonitor,
) {
    @Suppress("UNCHECKED_CAST")
    fun detail(id: String, source: String): Payload {
        val metadata = store.metadata(id)
        val (elapsed, timer) = store.timing(id)
        val query =
            monitor.query(
                "stats",
                id,
                mapOf(
                    "generation" to 0,
                    "expectedRevision" to store.revision(id).toString(),
                    "distanceSource" to source,
                    "metrics" to listOf("humanPowerW", "cadenceRpm", "speedMps"),
                    "startSeconds" to 0,
                    "endSeconds" to elapsed,
                ),
            )
        check(query["status"] == "ok") { "Ride changed. Try again." }
        val stats = query["statistics"] as Map<String, Payload>
        val distanceInfo = distance.info(id, source)
        val selected = distanceInfo["selected"] as? Payload
        val route = mutableListOf<Payload>()
        var locations = 0L
        val stride = max(1, (store.count(id, "location") / 500).toInt())
        store.readableDatabase
            .rawQuery(
                "SELECT * FROM observations WHERE ride=? AND kind='location' ORDER BY time,id",
                arrayOf(id),
            )
            .use { c ->
                while (c.moveToNext()) {
                    val row = with(store) { c.observation() }
                    if (
                        row.active &&
                            (row.values["horizontalAccuracyM"] ?: 999.0) <= 50 &&
                            locations++ % stride == 0L &&
                            route.size < 501
                    )
                        route.add(
                            mapOf(
                                "latitude" to row.values["latitude"],
                                "longitude" to row.values["longitude"],
                                "segment" to row.segment,
                            )
                        )
                }
            }
        fun mean(metric: String): Double? {
            val s = stats[metric] ?: return null
            return if (s.num("coveredSeconds") > 0) s.num("integral") / s.num("coveredSeconds")
            else null
        }
        fun maximum(metric: String) = (stats[metric]?.get("max") as? Map<*, *>)?.get("value")
        val power = stats["humanPowerW"] ?: emptyMap()
        val summary =
            mapOf(
                "schemaVersion" to 1,
                "id" to id,
                "startedAt" to metadata["startedAt"],
                "endedAt" to (metadata["endedAt"] ?: iso()),
                "elapsedSeconds" to elapsed,
                "timerSeconds" to timer,
                "distance" to distanceInfo,
                "distanceMeters" to selected?.get("distanceMeters"),
                "averageSpeedMps" to
                    selected?.let {
                        if (it.num("coveredSeconds") > 0)
                            it.num("distanceMeters") / it.num("coveredSeconds")
                        else null
                    },
                "maximumSpeedMps" to maximum("speedMps"),
                "averageRiderPowerW" to mean("humanPowerW"),
                "maximumRiderPowerW" to maximum("humanPowerW"),
                "averageCadenceRpm" to mean("cadenceRpm"),
                "maximumCadenceRpm" to maximum("cadenceRpm"),
                "riderWorkJoules" to power["integral"],
                "telemetryCoveredSeconds" to power.num("coveredSeconds"),
                "heartRateCoveredSeconds" to 0,
                "eventCount" to store.count(id),
                "telemetryCount" to store.count(id, "telemetry"),
                "locationCount" to store.count(id, "location"),
                "healthCount" to 0,
                "lapCount" to store.events(id).count { it.second == "lap" } + 1,
                "routePreview" to route,
                "warnings" to
                    if (selected?.flag("partial") == true) listOf("Distance is partial.")
                    else emptyList<String>(),
                "provenance" to
                    mapOf(
                        "riderPower" to "CYC rider power",
                        "distance" to (selected?.str("source") ?: "unavailable"),
                        "gps" to "phone",
                    ),
            )
        return mapOf("metadata" to metadata, "summary" to summary.filterValues { it != null })
    }

    private fun destination(id: String, extension: String): File {
        val metadata = store.metadata(id)
        check(
            metadata.str("phase") == "completed" &&
                metadata["sealRevision"] == metadata["verifiedSealRevision"]
        ) {
            "Finish saving this ride before exporting."
        }
        return File(File(context.cacheDir, "exports").apply { mkdirs() }, "PowerLog-$id.$extension")
    }

    fun archive(id: String): String {
        val file = destination(id, "zip")
        val temp = File(file.path + ".tmp")
        try {
            ZipOutputStream(temp.outputStream()).use { zip ->
                fun entry(name: String, body: (java.io.Writer) -> Unit) {
                    zip.putNextEntry(ZipEntry(name))
                    val writer = zip.writer()
                    body(writer)
                    writer.flush()
                    zip.closeEntry()
                }
                entry("metadata.json") { it.write(json(store.metadata(id)).toString()) }
                entry("lifecycle.json") {
                    it.write(
                        org.json
                            .JSONArray(
                                store.events(id).map { (time, action) ->
                                    mapOf("elapsedSeconds" to time, "action" to action)
                                }
                            )
                            .toString()
                    )
                }
                val columns =
                    listOf("timestamp", "elapsedSeconds", "sequence") +
                        telemetryMetrics.filter { it != "controllerSpeedMps" } +
                        listOf(
                            "controllerSpeedMps",
                            "controllerModel",
                            "firmwareLabel",
                            "controllerProtocol",
                            "connectionEpoch",
                        )
                entry("telemetry.csv") { writer ->
                    writer.write(columns.joinToString(",") + "\n")
                    var sequence = 0
                    store.each(id) { row ->
                        if (row.kind == "telemetry") {
                            val identity = row.identity.split('|')
                            val values: Payload =
                                row.values +
                                    mapOf(
                                        "timestamp" to row.timestamp,
                                        "elapsedSeconds" to row.time,
                                        "sequence" to sequence++,
                                        "controllerModel" to identity.getOrNull(0),
                                        "firmwareLabel" to identity.getOrNull(1),
                                        "controllerProtocol" to identity.getOrNull(2),
                                        "connectionEpoch" to row.epoch,
                                    )
                            writer.write(
                                columns.joinToString(",") { values[it]?.toString() ?: "" } + "\n"
                            )
                        }
                    }
                }
                entry("locations.csv") { writer ->
                    writer.write(
                        "timestamp,elapsedSeconds,active,segment," +
                            locationMetrics.joinToString(",") +
                            "\n"
                    )
                    store.each(id) { row ->
                        if (row.kind == "location")
                            writer.write(
                                "${row.timestamp},${row.time},${row.active},${row.segment}," +
                                    locationMetrics.joinToString(",") {
                                        row.values[it]?.toString() ?: ""
                                    } +
                                    "\n"
                            )
                    }
                }
            }
            check(temp.renameTo(file)) { "Could not save export." }
            return file.toURI().toString()
        } finally {
            temp.delete()
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun fit(id: String, source: String): String {
        val file = destination(id, "fit")
        val temp = File(file.path + ".tmp")
        val details = detail(id, source)
        val summary = details["summary"] as Payload
        val metadata = details["metadata"] as Payload
        val epoch = Instant.parse(metadata.str("startedAt")).epochSecond - 631065600
        val events = store.events(id)
        var eventIndex = 0
        val selected = distance.selected(id, source)
        val distanceColumn =
            when (selected) {
                "gps:phone" -> "gpsDistanceMeters"
                "controller" -> "controllerDistanceMeters"
                else -> null
            }
        try {
            FitWriter(temp).use { writer ->
                writer.message(
                    0,
                    listOf(
                        Field.enum(0, 4),
                        Field.u16(1, 255),
                        Field.u16(2, 1),
                        Field.u32(4, epoch.toDouble()),
                    ),
                )
                fun eventsThrough(time: Double) {
                    while (eventIndex < events.size && events[eventIndex].first <= time) {
                        val (t, action) = events[eventIndex++]
                        if (action in listOf("start", "resume", "pause", "stop"))
                            writer.message(
                                21,
                                listOf(
                                    Field.u32(253, epoch + floor(t)),
                                    Field.enum(0, 0),
                                    Field.enum(
                                        1,
                                        if (action in listOf("start", "resume")) 0 else 4,
                                    ),
                                    Field.u32(3, 0.0),
                                ),
                            )
                    }
                }
                var second = -1L
                val bin = mutableMapOf<String, Double>()
                var powerCount = 0
                var cadenceCount = 0
                fun flush() {
                    if (second < 0) return
                    eventsThrough(second.toDouble())
                    val fields = mutableListOf(Field.u32(253, (epoch + second).toDouble()))
                    if (powerCount > 0)
                        fields.add(Field.u16(7, bin.getValue("humanPowerW") / powerCount))
                    if (cadenceCount > 0)
                        fields.add(Field.u8(4, bin.getValue("cadenceRpm") / cadenceCount))
                    if (bin["latitude"] != null && bin["longitude"] != null) {
                        fields.add(Field.s32(0, bin.getValue("latitude") / 180 * 2147483648))
                        fields.add(Field.s32(1, bin.getValue("longitude") / 180 * 2147483648))
                    }
                    bin["speedMps"]?.let { fields.add(Field.u32(73, it * 1000)) }
                    bin["altitudeMeters"]?.let { fields.add(Field.u32(78, (it + 500) * 5)) }
                    bin["distance"]?.let { fields.add(Field.u32(5, it * 100)) }
                    if (fields.size > 1) writer.message(20, fields)
                    bin.clear()
                    powerCount = 0
                    cadenceCount = 0
                }
                store.eachChronological(id) { row ->
                    val next = floor(row.time).toLong()
                    if (next != second) {
                        flush()
                        second = next
                    }
                    if (row.active) {
                        row.values["humanPowerW"]
                            ?.takeIf { it in 0.0..32766.0 }
                            ?.let {
                                bin["humanPowerW"] = (bin["humanPowerW"] ?: 0.0) + it
                                powerCount++
                            }
                        row.values["cadenceRpm"]
                            ?.takeIf { it in 0.0..254.0 }
                            ?.let {
                                bin["cadenceRpm"] = (bin["cadenceRpm"] ?: 0.0) + it
                                cadenceCount++
                            }
                        if ((row.values["horizontalAccuracyM"] ?: 999.0) <= 50)
                            listOf("latitude", "longitude", "speedMps").forEach { key ->
                                row.values[key]?.let { bin[key] = it }
                            }
                        if ((row.values["verticalAccuracyM"] ?: 999.0) in 0.0..20.0)
                            row.values["altitudeMeters"]?.let { bin["altitudeMeters"] = it }
                        distanceColumn?.let {
                            row.values[it]?.let { value -> bin["distance"] = value }
                        }
                    }
                }
                flush()
                eventsThrough(Double.MAX_VALUE)
                val end = epoch + floor(summary.num("elapsedSeconds"))
                val boundaries =
                    listOf(0.0) +
                        events.filter { it.second == "lap" }.map { it.first } +
                        summary.num("elapsedSeconds")
                val laps = boundaries.zipWithNext().filter { (a, b) -> b > a }
                fun activeTime(a: Double, b: Double): Double {
                    var active = true
                    var prior = 0.0
                    var total = 0.0
                    (events + (summary.num("elapsedSeconds") to "stop")).forEach { (t, event) ->
                        if (active) total += max(0.0, min(t, b) - max(prior, a))
                        if (event in listOf("pause", "stop")) active = false
                        else if (event in listOf("start", "resume")) active = true
                        prior = t
                    }
                    return total
                }
                laps.forEachIndexed { index, (a, b) ->
                    val meters = selected?.let { distance.range(id, it, a, b).first }
                    writer.message(
                        19,
                        listOf(
                            Field.u32(253, epoch + floor(b)),
                            Field.u16(254, index),
                            Field.enum(0, 9),
                            Field.enum(1, 1),
                            Field.u32(2, epoch + floor(a)),
                            Field.u32(7, (b - a) * 1000),
                            Field.u32(8, activeTime(a, b) * 1000),
                            Field.u32(9, meters?.times(100)),
                            Field.enum(25, 2),
                        ),
                    )
                }
                writer.message(
                    18,
                    listOf(
                        Field.u32(253, end),
                        Field.u16(254, 0),
                        Field.enum(0, 8),
                        Field.enum(1, 1),
                        Field.u32(2, epoch.toDouble()),
                        Field.enum(5, 2),
                        Field.enum(6, if (metadata.flag("indoor")) 6 else 28),
                        Field.u32(7, summary.num("elapsedSeconds") * 1000),
                        Field.u32(8, summary.num("timerSeconds") * 1000),
                        Field.u32(
                            9,
                            (summary["distanceMeters"] as? Number)?.toDouble()?.times(100),
                        ),
                        Field.u16(20, summary["averageRiderPowerW"] as? Number),
                        Field.u16(21, summary["maximumRiderPowerW"] as? Number),
                        Field.u8(18, summary["averageCadenceRpm"] as? Number),
                        Field.u16(25, 0),
                        Field.u16(26, laps.size),
                        Field.u32(48, summary["riderWorkJoules"] as? Number),
                    ),
                )
                writer.message(
                    34,
                    listOf(
                        Field.u32(253, end),
                        Field.u32(0, summary.num("timerSeconds") * 1000),
                        Field.u16(1, 1),
                        Field.enum(2, 0),
                        Field.enum(3, 26),
                        Field.enum(4, 1),
                    ),
                )
                writer.finish()
            }
            check(temp.renameTo(file))
            return file.toURI().toString()
        } finally {
            temp.delete()
        }
    }
}

internal data class Field(val number: Int, val type: Int, val bytes: ByteArray) {
    companion object {
        fun value(n: Int, t: Int, value: Number?, size: Int, signed: Boolean = false): Field {
            val v = value?.toDouble()?.let { round(it) }
            val valid =
                v != null &&
                    v.isFinite() &&
                    v >= (if (signed) -2147483648.0 else 0.0) &&
                    v < (if (signed) 2147483647.0 else 2.0.pow(size * 8) - 1)
            val bits = if (valid) round(v!!).toLong() else if (signed) 2147483647L else -1L
            return Field(n, t, ByteArray(size) { (bits shr (8 * it)).toByte() })
        }

        fun enum(n: Int, v: Number?) = value(n, 0, v, 1)

        fun u8(n: Int, v: Number?) = value(n, 2, v, 1)

        fun u16(n: Int, v: Number?) = value(n, 0x84, v, 2)

        fun u32(n: Int, v: Number?) = value(n, 0x86, v, 4)

        fun s32(n: Int, v: Number?) = value(n, 0x85, v, 4, true)
    }
}

internal class FitWriter(file: File) : AutoCloseable {
    private val file =
        RandomAccessFile(file, "rw").apply {
            setLength(0)
            write(ByteArray(14))
        }
    private var definition = byteArrayOf()

    fun message(global: Int, fields: List<Field>) {
        val header =
            byteArrayOf(
                0x40,
                0,
                0,
                global.toByte(),
                (global shr 8).toByte(),
                fields.size.toByte(),
            ) +
                fields
                    .flatMap {
                        listOf(it.number.toByte(), it.bytes.size.toByte(), it.type.toByte())
                    }
                    .toByteArray()
        if (!header.contentEquals(definition)) {
            file.write(header)
            definition = header
        }
        file.write(0)
        fields.forEach { file.write(it.bytes) }
    }

    fun finish() {
        val length = file.length() - 14
        require(length in 0..0xfffffffeL)
        val header =
            byteArrayOf(14, 0x20, 0xde.toByte(), 0x52) +
                ByteArray(4) { (length shr (8 * it)).toByte() } +
                byteArrayOf(46, 70, 73, 84)
        val checksum = crc(header)
        file.seek(0)
        file.write(header)
        file.write(checksum and 255)
        file.write(checksum shr 8)
        file.seek(0)
        var sum = 0
        val buffer = ByteArray(65536)
        while (true) {
            val count = file.read(buffer)
            if (count < 0) break
            sum = crc(buffer.copyOf(count), sum)
        }
        file.write(sum and 255)
        file.write(sum shr 8)
        file.fd.sync()
    }

    override fun close() = file.close()

    companion object {
        fun crc(bytes: ByteArray, initial: Int = 0): Int {
            var crc = initial
            bytes.forEach {
                crc = crc xor (it.toInt() and 255)
                repeat(8) { crc = if (crc and 1 == 1) (crc shr 1) xor 0xa001 else crc shr 1 }
            }
            return crc
        }
    }
}
