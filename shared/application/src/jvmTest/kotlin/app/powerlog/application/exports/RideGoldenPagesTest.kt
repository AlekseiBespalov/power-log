package app.powerlog.application.exports

import app.powerlog.application.api.Outcome
import app.powerlog.application.api.PowerLogApi
import app.powerlog.application.goldens.GoldenLoad
import app.powerlog.application.goldens.RideGoldenLoader
import app.powerlog.application.goldens.ZipGolden
import app.powerlog.application.goldens.storedHealthMetric
import app.powerlog.domain.core.ride.FitPowerSource
import app.powerlog.domain.core.ride.Platform
import java.io.File
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class RideGoldenPagesTest {
    private val repository = File(requireNotNull(System.getProperty("powerlog.repositoryRoot")))
    private val output = File(requireNotNull(System.getProperty("powerlog.ridePages")))
    private val home = File(requireNotNull(System.getProperty("powerlog.goldensHome")))

    private class Loaded(val api: PowerLogApi, val golden: GoldenLoad.Loaded)

    private suspend fun load(platform: Platform, golden: File, archive: File): Loaded {
        var load: GoldenLoad? = null
        val api =
            ExportHarness.api(platform, directory = archive) {
                load = RideGoldenLoader(::storedHealthMetric).load(it, ZipGolden(golden))
            }
        return Loaded(api, assertIs<GoldenLoad.Loaded>(load, "$golden: $load"))
    }

    private fun goldens(): Map<String, File> {
        val inGit = File(repository, "tests/fixtures/rides")
        val listed =
            Json.parseToJsonElement(File(inGit, "manifest.json").readText())
                .jsonObject
                .getValue("files")
                .jsonObject
                .keys
                .filter { it.startsWith("rides/") }
                .map { it.split('/').slice(1..2).joinToString("/") }
        val outside =
            if (File(home, "rides").isDirectory) listed.associateWith { File(home, "rides/$it") } else emptyMap()
        val tracked =
            listOf("android", "ios", "web").flatMap { platform ->
                File(inGit, platform)
                    .listFiles()
                    .orEmpty()
                    .filter { it.isDirectory }
                    .map { "$platform/${it.name}" to it }
            }
        return (tracked.toMap() + outside).toSortedMap()
    }

    private fun fitPower(golden: File): FitPowerSource {
        val recorded = File(golden, "ride.json").takeIf { it.isFile } ?: return FitPowerSource.rider
        val power = Json.parseToJsonElement(recorded.readText()).jsonObject["fitPower"]?.jsonPrimitive?.content
        return power?.let(FitPowerSource::valueOf) ?: FitPowerSource.rider
    }

    @Test
    fun writesThePagesOfEveryRideGolden() = runBlocking {
        output.deleteRecursively()
        val goldens = goldens()
        assertTrue(goldens.isNotEmpty(), "no ride goldens found")
        for ((name, golden) in goldens) {
            val target = File(output, name).apply { mkdirs() }
            val loaded = load(Platform.valueOf(name.substringBefore('/')), golden, File(target, "archive"))
            File(target, "dropped-health-lines.json")
                .writeText("${loaded.golden.droppedHealthLines.joinToString(",", "[", "]")}\n")
            val exports = loaded.api.exports
            for (kind in ExportKind.entries) {
                val view = exports.open(ExportSource.Ride(loaded.golden.rideId), kind, fitPower(golden))
                val pages = File(target, kind.name).apply { mkdirs() }
                File(pages, "open.json").writeText("${openJson(view)}\n")
                for (projection in projectionsOf.getValue(kind)) File(pages, "$projection.json")
                    .writeText("${dumpJson(exports.pagesOf(view.sessionId, projection))}\n")
                assertIs<Outcome.Ok<Unit>>(exports.close(ExportCloseInput(view.sessionId, null)))
            }
        }
    }
}
