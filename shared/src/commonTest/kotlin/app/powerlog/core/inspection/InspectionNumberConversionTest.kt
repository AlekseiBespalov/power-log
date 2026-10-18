package app.powerlog.core.inspection

import app.powerlog.core.analytics.*
import kotlin.test.*

class InspectionNumberConversionTest {
    @Test
    fun plotFixturePlatformConversionMatchesPortableCodec() {
        var conversions = 0
        for (row in inspectionFixture("accelerators")["plot"].list()) {
            val metric = row["metric"].text()
            val originals = row["points"].list().mapIndexed { i, point -> inspectionItem(point, metric, i) }
            for (bounds in row["buckets"].list()) {
                val range = AnalysisRange(bounds.list()[0].long(), bounds.list()[1].long())
                val bucket = InspectionPlotReducer(metric, range, 1, owned = true)
                originals.forEach { bucket.append(it) }
                val encoded = InspectionCacheCodec.plot(bucket.aggregate())
                val portable = InspectionCacheCodec.readPlot(encoded)
                val platform =
                    InspectionCacheCodec.readPlot(encoded.encodeToByteArray(throwOnInvalidSequence = true)) {
                        conversions++
                        it.toDouble()
                    }
                assertEquals(portable.points, platform.points, row["id"].text())
                assertEquals(encoded, InspectionCacheCodec.plot(platform), row["id"].text())
            }
        }
        assertTrue(conversions > 0, "Platform binary64 conversion was not used")
    }

    private fun encodedPlot(): String {
        val reducer = InspectionPlotReducer("humanPowerW", AnalysisRange(0, 16000000), 1, owned = true)
        reducer.append(
            AnalysisItem(
                0,
                1.25,
                "event",
                "original",
                "controller",
                1,
                "telemetry",
                "controller",
                "2026-01-01T00:00:00Z",
                mapOf("humanPowerW" to Scalar.Real64(150.0)),
            )
        )
        return InspectionCacheCodec.plot(reducer.aggregate())
    }

    @Test
    fun platformConversionKeepsNumberGrammarBoundsRoundingAndSignedZero() {
        val encoded = encodedPlot()
        assertTrue("\"elapsed\":1.25" in encoded)
        fun replace(token: String) = encoded.replace("\"elapsed\":1.25", "\"elapsed\":$token")
        val valid =
            listOf(
                "0",
                "-0",
                "0.125",
                "-0.125",
                "28800",
                "99999.0000001",
                "4.9e-324",
                "-4.9e-324",
                "2.2250738585072014e-308",
                "1.7976931348623157e308",
                "-1.7976931348623157e308",
                "1.00000000000000011102230246251565404236316680908203125",
                "1.00000000000000033306690738754696212708950042724609375",
            )
        for (token in valid) {
            val text = replace(token)
            val portable = InspectionCacheCodec.readPlot(text)
            val platform =
                InspectionCacheCodec.readPlot(text.encodeToByteArray(throwOnInvalidSequence = true), String::toDouble)
            assertEquals(
                portable.points.single().point.original.elapsedSeconds.toBits(),
                platform.points.single().point.original.elapsedSeconds.toBits(),
                token,
            )
        }
        val invalid =
            listOf(
                "1e309",
                "1e-325",
                "1e-324",
                "9".repeat(4097),
                "0x1.0p0",
                "+1",
                "01",
                "1.",
                "1e2147483648",
                "0e2147483648",
                "NaN",
                "Infinity",
            )
        for (token in invalid) {
            assertFailsWith<IllegalArgumentException>(token.take(30)) { InspectionCacheCodec.readPlot(replace(token)) }
            assertFailsWith<IllegalArgumentException>(token.take(30)) {
                InspectionCacheCodec.readPlot(
                    replace(token).encodeToByteArray(throwOnInvalidSequence = true),
                    String::toDouble,
                )
            }
        }
        for (invalidResult in listOf(0.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> {
                InspectionCacheCodec.readPlot(encoded.encodeToByteArray(throwOnInvalidSequence = true)) {
                    invalidResult
                }
            }
        }
        assertTrue("4062c00000000000" in encoded)
        for (bits in listOf("4062C00000000000", "4062c0000000000", "4062c000000000000", "4062c0000000000z")) {
            val text = encoded.replace("4062c00000000000", bits)
            assertFailsWith<IllegalArgumentException> { InspectionCacheCodec.readPlot(text) }
            assertFailsWith<IllegalArgumentException> {
                InspectionCacheCodec.readPlot(text.encodeToByteArray(throwOnInvalidSequence = true), String::toDouble)
            }
        }
    }

    @Test
    fun shortStringBoundPreservesUtf8LimitAndEscapedSurrogateAdmission() {
        val encoded = encodedPlot()
        fun replace(event: String) = encoded.replace("\"event\":\"event\"", "\"event\":\"$event\"")
        assertTrue("\"event\":\"event\"" in encoded)
        for (event in listOf("a".repeat(716800), "€".repeat(238933), "\\ud83d\\ude00")) {
            val portable = InspectionCacheCodec.readPlot(replace(event))
            val platform =
                InspectionCacheCodec.readPlot(
                    replace(event).encodeToByteArray(throwOnInvalidSequence = true),
                    String::toDouble,
                )
            assertEquals(portable.points, platform.points)
        }
        for (event in listOf("a".repeat(716801), "€".repeat(238934), "\\ud800", "\\udc00")) {
            assertFailsWith<IllegalArgumentException> { InspectionCacheCodec.readPlot(replace(event)) }
            assertFailsWith<IllegalArgumentException> {
                InspectionCacheCodec.readPlot(
                    replace(event).encodeToByteArray(throwOnInvalidSequence = true),
                    String::toDouble,
                )
            }
        }
        for (bytes in
            listOf(
                byteArrayOf(0xc0.toByte(), 0xaf.toByte()),
                byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()),
            )) {
            assertFailsWith<IllegalArgumentException> { InspectionCacheCodec.readPlot(bytes, String::toDouble) }
        }
    }
}
