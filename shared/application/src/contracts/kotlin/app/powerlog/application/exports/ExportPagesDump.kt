package app.powerlog.application.exports

import app.powerlog.application.api.Outcome
import app.powerlog.domain.core.ride.FitPowerSource
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

val exportJson: Json = Json {
    classDiscriminator = "kind"
    encodeDefaults = true
    explicitNulls = true
}

val projectionsOf: Map<ExportKind, List<String>> =
    mapOf(
        ExportKind.zip to listOf("telemetry", "consumption", "gps", "healthZip", "lifecycle"),
        ExportKind.fit to listOf("seconds", "summary"),
        ExportKind.csv to listOf("seconds"),
    )

private fun increases(previous: List<Double>, next: List<Double>): Boolean {
    for ((a, b) in previous.zip(next)) if (a != b) return b > a
    return next.size > previous.size
}

suspend fun ExportsApi.open(source: ExportSource, kind: ExportKind, fitPower: FitPowerSource): ExportSessionView =
    assertIs<Outcome.Ok<ExportSessionView>>(open(ExportOpenInput(source, kind, fitPower)), "exports.open $kind").value

suspend fun ExportsApi.pagesOf(sessionId: String, projection: String): List<ExportPage> {
    val pages = mutableListOf<ExportPage>()
    var after: List<Double>? = null
    while (true) {
        val page =
            assertIs<Outcome.Ok<ExportPage>>(page(ExportPageInput(sessionId, projection, after)), projection).value
        val next = page.next
        if (after != null && next != null)
            assertTrue(increases(after, next), "$projection pages go from $after to $next")
        pages += page
        if (page.done) return pages
        after = assertNotNull(next, "$projection page ${pages.size} is not the last but has no next")
        if (pages.size > 1_000_000) fail("$projection never ends")
    }
}

private fun cell(value: Double): JsonPrimitive = if (value.isNaN()) JsonNull else JsonPrimitive(value)

fun dumpJson(pages: List<ExportPage>): JsonArray = buildJsonArray {
    for (page in pages) add(
        buildJsonObject {
            put("rows", JsonPrimitive(page.rows))
            put("next", page.next?.let { next -> JsonArray(next.map(::JsonPrimitive)) } ?: JsonNull)
            put("done", JsonPrimitive(page.done))
            put(
                "connections",
                exportJson.encodeToJsonElement(ListSerializer(ExportConnection.serializer()), page.connections),
            )
            put(
                "columns",
                JsonObject(
                    page.columns.mapValues { (_, column) ->
                        when (column) {
                            is ExportColumn.Numbers -> JsonArray(column.values.map(::cell))
                            is ExportColumn.Texts -> JsonArray(column.values.map(::JsonPrimitive))
                        }
                    }
                ),
            )
        }
    )
}

fun openJson(view: ExportSessionView): JsonElement =
    exportJson.encodeToJsonElement(ExportSessionView.serializer(), view)
