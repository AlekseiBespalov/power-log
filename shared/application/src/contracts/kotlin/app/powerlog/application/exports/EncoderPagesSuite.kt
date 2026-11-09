package app.powerlog.application.exports

import app.powerlog.application.api.Outcome
import app.powerlog.application.api.PowerLogApi
import app.powerlog.application.fakes.Answer
import app.powerlog.application.fakes.FakeFilePicker
import app.powerlog.application.fakes.FakePickedFile
import app.powerlog.application.imports.ImportInput
import app.powerlog.application.imports.ImportedRideView
import app.powerlog.application.imports.KeyInput
import app.powerlog.application.port.FilePicker
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.ride.FitPowerSource
import app.powerlog.domain.core.ride.Platform
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

abstract class EncoderPagesSuite {
    protected abstract fun read(repositoryPath: String): String?

    // an application over an empty store that the encoder-fixture loader filled from this ride.json
    protected abstract suspend fun api(rideJson: String): PowerLogApi

    // an application over an empty store whose file picker is this one
    protected abstract suspend fun api(picker: FilePicker): PowerLogApi

    private fun json(path: String): JsonElement =
        Json.parseToJsonElement(requireNotNull(read(path)) { "$path is missing" })

    // health false: 60 §7.3 rule 7, an archive that keeps no Health produces no Health projection
    suspend fun checkStorable(fixture: String, kind: ExportKind, health: Boolean = true) {
        val directory = "tests/fixtures/export/storable/$fixture"
        val rideId = RideId(json("$directory/fixture.json").jsonObject.getValue("rideId").jsonPrimitive.content)
        val exports = api(requireNotNull(read("$directory/ride.json"))).exports
        val view = exports.open(ExportSource.Ride(rideId), kind, FitPowerSource.rider)
        assertSameJson(
            withoutSession(json("$directory/pages/open.json"), health),
            withoutSession(openJson(view), health),
            "open",
        )
        for (projection in projectionsOf.getValue(kind)) {
            if (!health && projection == "healthZip") continue
            val dumped =
                if (kind == ExportKind.csv && read("$directory/pages/seconds-csv.json") != null) "seconds-csv"
                else projection
            assertSameRows(
                projection,
                json("$directory/pages/$dumped.json").jsonArray,
                exports.pagesOf(view.sessionId, projection),
                if (health) emptySet() else healthCells[projection].orEmpty(),
                dumpedOnly(projection, view.platform),
            )
        }
        assertIs<Outcome.Ok<Unit>>(exports.close(ExportCloseInput(view.sessionId, null)))
    }

    class ImportedPages(val open: JsonElement, val telemetry: JsonArray)

    suspend fun importedPages(family: String): ImportedPages = coroutineScope {
        val csv = json("tests/fixtures/export/zip/$family/telemetry.csv.json").jsonArray
        val bytes = csv.joinToString("") { "${it.jsonPrimitive.content}\n" }.encodeToByteArray()
        val post: (() -> Unit) -> Unit = { reply -> launch { reply() } }
        val picker =
            FakeFilePicker(post).apply { picks.script = { Answer.Ok(FakePickedFile("telemetry.csv", bytes, post)) } }
        val app = api(picker)
        val imported =
            assertIs<Outcome.Ok<ImportedRideView>>(app.imports.open(KeyInput("history:list:csv")), "imports.open").value
        val view = app.exports.open(ExportSource.Imported(imported.importId), ExportKind.csv, FitPowerSource.rider)
        val pages = app.exports.pagesOf(view.sessionId, "telemetry")
        assertIs<Outcome.Ok<Unit>>(app.exports.close(ExportCloseInput(view.sessionId, null)))
        assertIs<Outcome.Ok<Unit>>(app.imports.close(ImportInput(imported.importId)))
        ImportedPages(openJson(view), dumpJson(pages))
    }

    companion object {
        private fun withoutSession(open: JsonElement, health: Boolean = true): JsonObject {
            val view = open.jsonObject - "sessionId"
            if (health) return JsonObject(view)
            val producers = view.getValue("producers").jsonObject - "health"
            return JsonObject(view + ("producers" to JsonObject(producers)))
        }

        private fun same(expected: JsonElement, actual: JsonElement): Boolean =
            when {
                expected is JsonObject && actual is JsonObject ->
                    expected.keys == actual.keys &&
                        expected.keys.all { same(expected.getValue(it), actual.getValue(it)) }
                expected is JsonArray && actual is JsonArray ->
                    expected.size == actual.size && expected.indices.all { same(expected[it], actual[it]) }
                expected is JsonNull || actual is JsonNull -> expected == actual
                expected is JsonPrimitive && actual is JsonPrimitive && !expected.isString && !actual.isString ->
                    expected.doubleOrNull?.let { it == actual.jsonPrimitive.doubleOrNull } ?: (expected == actual)
                else -> expected == actual
            }

        private fun assertSameJson(expected: JsonElement, actual: JsonElement, what: String) {
            assertTrue(same(expected, actual), "$what: $actual is not $expected")
        }

        private val startingBuildHealth =
            setOf("logicalKey", "targetEventId", "originRevision", "valueBits", "evidence")

        private fun dumpedOnly(projection: String, platform: Platform): Set<String> =
            when {
                projection != "healthZip" -> emptySet()
                platform == Platform.ios -> startingBuildHealth
                else -> startingBuildHealth + "basalEnergyKcal"
            }

        private val healthCells =
            mapOf(
                "seconds" to setOf("heartRate"),
                "summary" to setOf("averageHeartRateBpm", "maxHeartRateBpm", "caloriesKcal"),
            )

        private fun assertSameRows(
            projection: String,
            dumped: JsonArray,
            pages: List<ExportPage>,
            skipped: Set<String>,
            dumpedOnly: Set<String>,
        ) {
            val pagesDumped = dumped.map { it.jsonObject }
            assertEquals(
                pagesDumped.sumOf { it.getValue("rows").jsonPrimitive.content.toInt() },
                pages.sumOf { it.rows },
                "$projection rows",
            )
            assertEquals(
                pagesDumped.flatMap { it.getValue("connections").jsonArray },
                pages.flatMap { page ->
                    page.connections.map { exportJson.encodeToJsonElement(ExportConnection.serializer(), it) }
                },
                "$projection connections",
            )
            val listed = pages.flatMap { it.columns.keys }.toSet()
            val names = pagesDumped.flatMap { it.getValue("columns").jsonObject.keys }.toSet()
            assertEquals(emptySet(), listed - names, "$projection columns the dump doesn't have")
            assertEquals(dumpedOnly, names - listed, "$projection dumped columns the pages leave out")
            for (name in dumpedOnly - startingBuildHealth) {
                val cells = pagesDumped.flatMap { it.getValue("columns").jsonObject.getValue(name).jsonArray }
                assertTrue(cells.all { it == JsonNull }, "$projection $name holds only null cells in the dump")
            }
            for (name in listed - skipped) {
                val expected = pagesDumped.flatMap { it.getValue("columns").jsonObject.getValue(name).jsonArray }
                val actual = pages.flatMap { page -> cells(page.columns.getValue(name)) }
                assertSameJson(JsonArray(expected), JsonArray(actual), "$projection $name")
            }
        }

        private fun cells(column: ExportColumn): List<JsonElement> =
            when (column) {
                is ExportColumn.Numbers -> column.values.map { if (it.isNaN()) JsonNull else JsonPrimitive(it) }
                is ExportColumn.Texts -> column.values.map(::JsonPrimitive)
            }
    }
}
