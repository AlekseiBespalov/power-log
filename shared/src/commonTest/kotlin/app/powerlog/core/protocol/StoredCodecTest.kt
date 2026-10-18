package app.powerlog.core.protocol

import kotlin.test.*

internal expect fun readFixtureFile(path: String): ByteArray

internal fun fixture(name: String): Obj {
    val root =
        JsonCodec.parse(
                readFixtureFile("${app.powerlog.core.analytics.CONTRACT_FIXTURES}/watch-protocol/$name.json"),
                true,
            )
            .obj()
    fun expand(value: Json): Json =
        when (value) {
            is Arr -> Arr(value.values.map(::expand))
            is Obj ->
                value["\$ref"]?.let { expand(root.required("shared").obj().required(it.string())) }
                    ?: Obj(value.fields.mapValues { expand(it.value) })
            else -> value
        }
    return expand(Obj(root.fields - "shared")).obj()
}

internal fun Obj.cases(): List<Obj> = required("cases").array().map { it.obj() }

class StoredCodecTest {
    @Test
    fun watchProtocolCanonicalFixtures() {
        for (case in fixture("canonical").cases()) {
            val expected = case.required("expected").obj().required("canonicalUtf8").string()
            val input = case.required("input")
            val actual =
                if (case.required("component").string() == "finiteBinary64Printer") {
                    CanonicalNumber.encode(
                        Double.fromBits(input.obj().required("binary64BitsHex").string().toULong(16).toLong())
                    )
                } else JsonCodec.encode(input)
            assertEquals(expected, actual, case.required("id").string())
            case.required("expected").obj()["wireNumericAdmissible"]?.let {
                val admissible =
                    try {
                        CanonicalNumber.parse(actual)
                        true
                    } catch (_: ProtocolException) {
                        false
                    }
                assertEquals(it.boolean(), admissible, case.required("id").string())
            }
        }
    }

    @Test
    fun watchProtocolTimestampFixtures() {
        for (case in fixture("timestamps").cases()) {
            val input = case.required("input").string()
            if (case.required("accepted").boolean()) {
                val parsed = Timestamps.parse(input)
                assertEquals(case.required("retainedOriginal").string(), parsed.original)
                assertEquals(case.required("comparisonMicroseconds").string(), parsed.comparisonUs.toString())
                assertEquals(case.required("queryMicroseconds").string(), parsed.queryMicroseconds.toString())
            } else
                assertEquals(
                    case.required("rejection").string(),
                    assertFailsWith<ProtocolException> { Timestamps.parse(input) }.reason.name,
                )
        }
        for (case in fixture("timestamps").required("metadataMappingCases").array().map { it.obj() }) {
            val input = case.required("input").obj()
            val expected = case.required("expected").obj()
            val start = Timestamps.parse(input.required("startedAt").string())
            val event = Schema.event(input.required("event"), input.required("rideId").string())
            assertEquals(
                expected.required("elapsedMicroseconds").string(),
                (event.timestamp.comparisonUs - start.comparisonUs).toString(),
            )
            assertEquals(expected.required("originalTimestamp").string(), event.timestamp.original)
        }
    }

    @Test
    fun watchProtocolDigestFixtures() {
        for (case in fixture("digests").cases()) {
            val events =
                case
                    .required("events")
                    .array()
                    .map { Schema.event(it, it.obj().required("workoutId").string()) }
                    .toTypedArray()
            assertEquals(
                case.required("expectedDigest").string(),
                ContentDigest.events(events),
                case.required("id").string(),
            )
            val goldens = case.required("canonicalEventsUtf8").array().map { it.string() }
            assertEquals(goldens, events.map { it.canonicalUtf8 })
            val array = events.joinToString(",", "[", "]") { it.canonicalUtf8 }
            assertEquals(case.required("decodedArrayUtf8").string(), array)
            assertEquals(case.required("decodedBytes").number().toInt(), array.encodeToByteArray().size)
            assertEquals(case.required("framedUtf8").string(), events.joinToString("") { it.canonicalUtf8 + "\n" })
        }
    }
}
