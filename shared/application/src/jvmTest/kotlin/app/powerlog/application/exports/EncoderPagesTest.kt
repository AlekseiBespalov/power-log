package app.powerlog.application.exports

import app.powerlog.application.api.PowerLogApi
import app.powerlog.application.goldens.EncoderFixtureLoader
import app.powerlog.application.goldens.GoldenLoad
import app.powerlog.application.port.FilePicker
import app.powerlog.domain.core.ride.Platform
import java.io.File
import kotlin.test.Test
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class EncoderPagesTest : EncoderPagesSuite() {
    private val repository = File(requireNotNull(System.getProperty("powerlog.repositoryRoot")))
    private val importPages = File(repository, "shared/build/encoder-import-pages")

    override fun read(repositoryPath: String): String? =
        File(repository, repositoryPath).takeIf { it.isFile }?.readText()

    override suspend fun api(rideJson: String): PowerLogApi {
        val platform =
            Platform.valueOf(
                Json.parseToJsonElement(rideJson)
                    .jsonObject
                    .getValue("context")
                    .jsonObject
                    .getValue("platform")
                    .jsonPrimitive
                    .content
            )
        return ExportHarness.api(platform) { writer ->
            assertIs<GoldenLoad.Loaded>(EncoderFixtureLoader.load(writer, rideJson))
        }
    }

    override suspend fun api(picker: FilePicker): PowerLogApi = ExportHarness.api(Platform.web, picker = { picker })

    private fun check(fixture: String, kind: ExportKind) = runBlocking { checkStorable(fixture, kind) }

    private fun import(family: String) = runBlocking {
        val pages = importedPages(family)
        val directory = File(importPages, family).apply { mkdirs() }
        File(directory, "open.json").writeText("${pages.open}\n")
        File(directory, "telemetry.json").writeText("${pages.telemetry}\n")
    }

    @Test fun androidControllerDistanceZip() = check("android-controller-distance", ExportKind.zip)

    @Test fun androidControllerDistanceFit() = check("android-controller-distance", ExportKind.fit)

    @Test fun androidControllerDistanceCsv() = check("android-controller-distance", ExportKind.csv)

    @Test fun androidGpsDistanceZip() = check("android-gps-distance", ExportKind.zip)

    @Test fun androidGpsDistanceFit() = check("android-gps-distance", ExportKind.fit)

    @Test fun androidGpsDistanceCsv() = check("android-gps-distance", ExportKind.csv)

    @Test fun iosControllerDistanceZip() = check("ios-controller-distance", ExportKind.zip)

    @Test fun iosControllerDistanceFit() = check("ios-controller-distance", ExportKind.fit)

    @Test fun iosControllerDistanceCsv() = check("ios-controller-distance", ExportKind.csv)

    @Test fun iosGpsDistanceZip() = check("ios-gps-distance", ExportKind.zip)

    @Test fun iosGpsDistanceFit() = check("ios-gps-distance", ExportKind.fit)

    @Test fun iosGpsDistanceCsv() = check("ios-gps-distance", ExportKind.csv)

    @Test fun gpsAndroidImportedCsv() = import("gps-android")

    @Test fun gpsIphoneImportedCsv() = import("gps-iphone")

    @Test fun healthDescriptorImportedCsv() = import("health-descriptor")

    @Test fun telemetryLifecycleImportedCsv() = import("telemetry-lifecycle")
}
