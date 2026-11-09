package app.powerlog.application.goldens

import app.powerlog.application.store.ArchiveWriter
import app.powerlog.application.store.Commit
import app.powerlog.application.store.CommitNotice
import app.powerlog.application.store.Mutation
import app.powerlog.application.store.StoreResult
import app.powerlog.application.store.WriteBatch
import app.powerlog.domain.core.measurement.HealthMetric
import app.powerlog.domain.core.ride.Platform
import java.io.File
import java.time.Instant
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject

class GoldenLoadersTest {
    private val repository = File(System.getProperty("powerlog.repositoryRoot"))
    private val home = File(System.getProperty("powerlog.goldensHome"))

    @Test
    fun everyEncoderFixtureLoadsWithoutARefusal() = runTest {
        val fixtures =
            repository.resolve("tests/fixtures/export/storable").listFiles().orEmpty().filter {
                it.resolve("ride.json").isFile
            }
        assertEquals(
            listOf(
                "android-controller-distance",
                "android-gps-distance",
                "ios-controller-distance",
                "ios-gps-distance",
            ),
            fixtures.map { it.name }.sorted(),
        )
        for (fixture in fixtures) {
            val writer = RecordingWriter()
            val load = EncoderFixtureLoader.load(writer, fixture.resolve("ride.json").readText())
            val printed = PrintedRide(fixture.resolve("ride.zip"))
            val loaded = assertIs<GoldenLoad.Loaded>(load, fixture.name)
            assertEquals(printed.id, loaded.rideId.value, fixture.name)
            assertEquals(Platform.valueOf(fixture.name.substringBefore('-')), loaded.platform, fixture.name)
            writer.assertMatches(printed, loaded, fixture.name)
        }
    }

    @Test
    fun everyRideGoldenLoadsWithoutARefusal() =
        runTest(timeout = 10.minutes) {
            val goldens = rideGoldens()
            assertTrue(
                listOf("android", "ios", "web").all { platform -> goldens.keys.any { it.startsWith("$platform/") } },
                goldens.keys.toString(),
            )
            for ((name, golden) in goldens) {
                val writer = RecordingWriter()
                val files = ZipGolden(golden)
                val load = RideGoldenLoader(::storedHealthMetric).load(writer, files)
                val loaded = assertIs<GoldenLoad.Loaded>(load, "$name: $load")
                val printed = PrintedRide(golden.resolve("ride.zip"))
                assertEquals(printed.id, loaded.rideId.value, name)
                assertEquals(Platform.valueOf(name.substringBefore('/')), loaded.platform, name)
                assertEquals(printed.created, loaded.exportedAt, name)
                if (loaded.platform != Platform.android) assertEquals(emptyList(), loaded.droppedHealthLines, name)
                writer.assertMatches(printed, loaded, name)
            }
        }

    private fun rideGoldens(): Map<String, File> {
        val tracked = repository.resolve("tests/fixtures/rides")
        val inGit =
            listOf("android", "ios", "web").flatMap { platform ->
                tracked
                    .resolve(platform)
                    .listFiles()
                    .orEmpty()
                    .filter { it.isDirectory }
                    .map { "$platform/${it.name}" to it }
            }
        val manifest = parseJson(tracked.resolve("manifest.json").readText(), "manifest.json").jsonObject
        val listed =
            manifest
                .getValue("files")
                .jsonObject
                .keys
                .filter { it.startsWith("rides/") }
                .map { it.split('/').slice(1..2).joinToString("/") }
                .distinct()
        val outside = listed.map { it to home.resolve("rides/$it") }.filter { it.second.isDirectory }
        return (inGit + outside).toMap().toSortedMap()
    }

    private class RecordingWriter : ArchiveWriter {
        val batches = mutableListOf<WriteBatch>()

        override suspend fun commit(batch: WriteBatch): StoreResult<Commit> {
            batches += batch
            return StoreResult.Ok(Commit.Committed(emptyMap(), batches.size.toLong()))
        }

        override val commits: Flow<CommitNotice> = emptyFlow()

        override val historyCommits: Flow<CommitNotice> = emptyFlow()

        fun assertMatches(printed: PrintedRide, loaded: GoldenLoad.Loaded, name: String) {
            val first = batches.first().mutations
            val create = assertIs<Mutation.CreateRide>(first.first(), name)
            val sessions = first.drop(1).map { assertIs<Mutation.OpenControllerSession>(it, name).session }
            val appends = batches.drop(1).map { it.mutations.single() }
            val telemetry = appends.filterIsInstance<Mutation.AppendTelemetry>().map { it.rows.rows }
            val locations = appends.filterIsInstance<Mutation.AppendLocations>().map { it.fixes.size }
            val health = appends.filterIsInstance<Mutation.AppendHealthSamples>().map { it.samples.size }
            val events = appends.filterIsInstance<Mutation.AppendEvents>().map { it.events.size }

            assertEquals(printed.rows("telemetry"), telemetry.sum(), "$name telemetry rows")
            assertEquals(printed.rows("gps"), locations.sum(), "$name GPS fixes")
            assertEquals(
                printed.rows("health") - loaded.droppedHealthLines.size,
                health.sum(),
                "$name Health samples",
            )
            assertEquals(printed.rows("events"), events.sum(), "$name events")
            assertTrue((telemetry + locations + health + events).all { it in 1..GOLDEN_BATCH_ROWS }, name)
            assertEquals(
                1 + listOf("telemetry", "gps", "health", "events").sumOf { batchesFor(printed.rows(it)) },
                loaded.batches,
                "$name batches",
            )
            assertEquals(loaded.batches, batches.size, name)
            assertEquals(printed.connections, sessions.map { it.sessionNo }, "$name controller sessions")

            val ride = create.ride
            val meta = printed.ride
            assertEquals(Instant.parse(meta.text("startedAt")).toEpochMilli(), ride.startedUtcUs / 1_000, name)
            assertEquals(Instant.parse(meta.text("endedAt")).toEpochMilli(), ride.end?.utcUs?.div(1_000), name)
            assertEquals(meta.int("sampleHz"), ride.options.sampleRate.hz, name)
            assertEquals(meta.bool("indoor"), ride.options.indoor, name)
            assertEquals(meta.bool("watchEnabled"), ride.options.useWatch, name)
            assertEquals(meta.bool("saveToHealth"), ride.options.saveToHealth, name)
            assertEquals(meta.bool("recordGPS"), ride.options.recordGps, name)
            assertEquals(meta.bool("example"), ride.example, name)
            assertEquals(meta.bool("interrupted"), ride.interrupted, name)
            assertEquals(meta.text("batteryConsumptionSource"), ride.options.batteryMethod.name, name)
            assertEquals(meta.obj("health").text("state"), create.health.outcome.name, name)
            assertEquals(meta.obj("health").textOrNull("provider"), create.health.provider?.name, name)
            assertEquals(printed.gpsSources, if (locations.sum() > 0) listOf("phone") else emptyList(), name)
            assertEquals(
                printed.healthSources.toSet(),
                appends
                    .filterIsInstance<Mutation.AppendHealthSamples>()
                    .flatMap { append -> append.samples.map { it.producer.name } }
                    .toSet(),
                name,
            )
        }

        private fun batchesFor(rows: Int): Int = (rows + GOLDEN_BATCH_ROWS - 1) / GOLDEN_BATCH_ROWS
    }

    private class PrintedRide(zip: File) {
        private val entries =
            ZipFile(zip).use { file ->
                file.entries().toList().associate {
                    it.name.substringAfterLast('/') to file.getInputStream(it).readBytes().decodeToString()
                }
            }
        private val pkg =
            Fields("datapackage.json", parseJson(entries.getValue("datapackage.json"), "datapackage.json"))

        val id: String = pkg.text("id")
        val created: String = pkg.text("created")
        val ride: Fields = pkg.obj("powerLog:ride")
        val connections: List<Int> = pkg.obj("powerLog:sources").objects("connections").map { it.int("connection") }
        val gpsSources: List<String> = pkg.obj("powerLog:sources").texts("gps")
        val healthSources: List<String> = pkg.obj("powerLog:sources").texts("health")

        fun rows(resource: String): Int {
            val path = pkg.objects("resources").single { it.text("name") == resource }.text("path")
            return CsvTable(path, entries.getValue(path)).rows().count()
        }
    }
}

internal fun storedHealthMetric(platform: Platform, printed: String): HealthMetric? =
    (if (platform == Platform.ios) IOS_HEALTH_METRICS else ANDROID_HEALTH_METRICS)[printed]?.let(::HealthMetric)

internal class ZipGolden(directory: File) : RideGoldenFiles {
    private val zip = directory.resolve("ride.zip")

    override val entries: List<String> = ZipFile(zip).use { file -> file.entries().toList().map { it.name } }

    override fun text(entry: String): String =
        ZipFile(zip).use { file -> file.getInputStream(file.getEntry(entry)).readBytes().decodeToString() }

    override val sidecar: String = directory.resolve("sidecar.json").readText()
}

private val IOS_HEALTH_METRICS =
    mapOf(
        "heartRate" to "heartRateBpm",
        "activeEnergy" to "activeEnergyKcal",
        "basalEnergy" to "basalEnergyKcal",
        "distance" to "cyclingDistanceMeters",
        "power" to "cyclingPowerW",
        "cadence" to "cyclingCadenceRpm",
        "speed" to "cyclingSpeedMps",
        "respiratoryRate" to "respiratoryRateBrpm",
        "oxygenSaturation" to "oxygenSaturationFraction",
        "heartRateVariabilitySDNN" to "heartRateVariabilitySDNNMs",
        "physicalEffort" to "physicalEffortMET",
        "functionalThresholdPower" to "cyclingFunctionalThresholdPowerW",
        "workoutEffort" to "workoutEffortScore",
        "estimatedWorkoutEffort" to "estimatedWorkoutEffortScore",
    )
private val ANDROID_HEALTH_METRICS =
    mapOf(
        "heartRate" to "heartRate",
        "activeEnergy" to "activeEnergy",
        "respiratoryRate" to "respiratoryRate",
        "oxygenSaturation" to "oxygenSaturation",
        "power" to "riderPower",
        "cadence" to "cyclingCadence",
        "speed" to "cyclingSpeed",
        "distance" to "cyclingDistance",
    )
