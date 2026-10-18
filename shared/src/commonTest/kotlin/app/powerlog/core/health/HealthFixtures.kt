package app.powerlog.core.health

import app.powerlog.core.Sha256
import app.powerlog.core.analytics.*
import app.powerlog.core.protocol.ControllerSecond
import app.powerlog.core.protocol.JsonCodec
import kotlin.test.*

internal const val R = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
internal const val RW = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
internal const val PHONE = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
internal const val WATCH = "ffffffff-ffff-4fff-8fff-ffffffffffff"
internal const val UTC = "2026-09-09T00:00:00.000Z"
internal const val PID = "99999999-8888-4777-8666-555555555555"
internal var assertionCount = 0

internal fun eq(expected: Any?, actual: Any?, message: String = "") {
    assertionCount++
    assertEquals(expected, actual, message)
}

internal fun yes(value: Boolean, message: String = "") {
    assertionCount++
    assertTrue(value, message)
}

internal fun cases(name: String): List<J> = fixture("health/$name.json")["cases"].list()

internal fun canonical(j: J): String {
    return when (j) {
        is J.Obj -> j.fields.entries.joinToString(",", "{", "}") { JsonCodec.quote(it.key) + ":" + canonical(it.value) }
        is J.Arr -> j.values.joinToString(",", "[", "]", transform = ::canonical)
        is J.Str -> JsonCodec.quote(j.value)
        is J.Num -> j.text
        is J.Bool -> j.value.toString()
        J.Null -> "null"
    }
}

internal fun quantity(j: J, id: String = R): NormalizedContent {
    val source =
        if (j["source"] == J.Null) MeasurementSource("controller", "controller")
        else MeasurementSource(j["source"]["source"].text(), j["source"]["device"].text())
    val metadata = PublicationMetadata(id, EndpointRole.phone, PHONE, EndpointRole.phone, PHONE, true, false)
    val q =
        ProviderContent.Quantity(
            QuantityContent(
                HealthKind.riderPower,
                if (j["startUs"] == J.Null) 0 else j["startUs"].long(),
                if (j["endUs"] == J.Null) 1000000 else j["endUs"].long(),
                if (j is J.Str) if (j.value == "A") 150.0 else if (j.value == "B") 175.0 else 200.0
                else j["value"].double(),
                "W",
                source,
                metadata,
            )
        )
    return NormalizedContent.Present(q, AdapterProjection.Apple(q))
}

internal fun record(
    state: RecordState = RecordState.notSubmitted,
    version: Long = 1,
    content: NormalizedContent = quantity(J.Str("A")),
    component: Component = Component.riderPower,
    syncId: String = HealthIds.second(R, HealthKind.riderPower, 0),
    ids: List<String> = emptyList(),
    reason: HealthReason? = null,
): PublicationRecord {
    val normalized =
        if (content !is NormalizedContent.Present || content.fields !is ProviderContent.Quantity) content
        else {
            val q = content.fields.fields
            val actual: ProviderContent =
                when (component) {
                    Component.workout ->
                        ProviderContent.Session(
                            SessionContent(
                                0,
                                1000000,
                                q.metadata,
                                if (q.value > 150)
                                    listOf(
                                        BoundaryContent(
                                            "seq:phone:1",
                                            "seq:phone:2",
                                            0,
                                            if (q.value == 175.0) 500000 else 750000,
                                        )
                                    )
                                else emptyList(),
                                emptyList(),
                                emptyList(),
                            )
                        )
                    Component.route -> ProviderContent.Route(emptyList(), q.metadata)
                    Component.cyclingCadence ->
                        ProviderContent.Quantity(q.copy(kind = HealthKind.cyclingCadence, unit = "RPM"))
                    else -> content.fields
                }
            NormalizedContent.Present(
                actual,
                if (component == Component.workout)
                    AdapterProjection.HealthConnect(
                        actual,
                        0,
                        0,
                        RouteIntent.NotRequested,
                        440,
                        RouteEncoding(
                            null,
                            "app.powerlog.mobile",
                            "Power Log",
                            syncId,
                            null,
                            "Android phone",
                            null,
                            "Power Log ride",
                            null,
                            0,
                            0,
                        ),
                    )
                else AdapterProjection.Apple(actual),
            )
        }
    return PublicationRecord(
        syncId,
        component,
        if (normalized is NormalizedContent.Absent) RecordOperation.delete else RecordOperation.upsert,
        version,
        normalized,
        HealthCodec.contentDigest(normalized),
        1,
        ids,
        state,
        null,
        null,
        0,
        null,
        reason,
    )
}

internal fun readings(input: J, key: String = "readings", valueKey: String = "value"): Array<SecondReading> =
    input[key]
        .list()
        .map { r ->
            val source = MeasurementSource(r["source"]["source"].text(), r["source"]["device"].text())
            SecondReading(
                Origin(null, null, null, "fixture-${r["sequence"].text()}", r["producer"].text(), r["sequence"].long()),
                UTC,
                r["offsetUs"].long(),
                r["collectionUs"].long(),
                source,
                r["activeInterval"].optionalDouble()?.toInt(),
                Continuity(
                    r["clockEpoch"].optionalText(),
                    r["connectionEpoch"].optionalText(),
                    r["interruptionIndex"].long().toInt(),
                ),
                r[valueKey].optionalDouble()?.let { Scalar.Real64(it) },
                null,
                false,
                r["horizontalAccuracyM"].optionalDouble(),
                r["speedAccuracyMps"].optionalDouble(),
            )
        }
        .toTypedArray()

internal fun sampleExpected(e: J, v: SecondValue) {
    for ((key, value) in e.fields()) when (key) {
        "n" -> eq(value.long(), v.n)
        "validOriginalCount" -> eq(value.long(), v.validOriginalCount)
        "integralQUs" -> eq(value.long(), v.integralQUs)
        "coveredUs" -> eq(value.long(), v.coveredUs)
        "value" -> eq(value.double().toBits(), v.value.toBits())
        else -> error("Unknown sample expectation $key")
    }
}

internal fun bucket(j: J): ControllerSecond = HealthCodec.decodeControllerSecond(canonical(j))

internal fun digestLines(values: List<String>): String =
    Sha256.hash(values.joinToString("\n", postfix = "\n").encodeToByteArray())

open class HealthFixtureTest {
    @kotlin.test.AfterTest
    fun healthAssertionReport() {
        println("HEALTH_ASSERTIONS:" + assertionCount)
    }
}

internal inline fun <reified T : Throwable> fails(block: () -> Unit): T {
    assertionCount++
    return kotlin.test.assertFailsWith<T>(block = block)
}
