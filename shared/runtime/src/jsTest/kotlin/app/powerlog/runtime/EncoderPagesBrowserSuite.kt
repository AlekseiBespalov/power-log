@file:OptIn(DelicateCoroutinesApi::class)

package app.powerlog.runtime

import app.powerlog.application.Executors
import app.powerlog.application.api.LaunchContext
import app.powerlog.application.api.LaunchReason
import app.powerlog.application.api.PowerLogApi
import app.powerlog.application.exports.EncoderPagesSuite
import app.powerlog.application.exports.ExportKind
import app.powerlog.application.fakes.FakeDeviceInfo
import app.powerlog.application.fakes.FakeFilePicker
import app.powerlog.application.fakes.FakePermissionGate
import app.powerlog.application.goldens.EncoderFixtureLoader
import app.powerlog.application.goldens.GoldenLoad
import app.powerlog.application.launch.ApplicationStores
import app.powerlog.application.launch.PowerLogApplication
import app.powerlog.application.port.FilePicker
import app.powerlog.application.store.ArchiveWriter
import app.powerlog.application.store.DiagnosticLogRepository
import app.powerlog.application.store.LogChunk
import app.powerlog.application.store.LogField
import app.powerlog.application.store.LogLevel
import app.powerlog.application.store.LogLine
import app.powerlog.application.store.LogValue
import app.powerlog.application.store.StoreResult
import app.powerlog.data.MemoryMeasurements
import app.powerlog.data.WebStores
import app.powerlog.domain.core.ride.Platform
import app.powerlog.runtime.clock.WebClock
import kotlin.js.Promise
import kotlin.test.assertIs
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlinx.coroutines.test.TestResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal class EncoderPagesBrowserSuite(private val fixtures: dynamic, private val archiveName: String) :
    EncoderPagesSuite() {
    override fun read(repositoryPath: String): String? = fixtures[repositoryPath] as String?

    override suspend fun api(rideJson: String): PowerLogApi {
        val ride = Json.parseToJsonElement(rideJson).jsonObject
        val platform = Platform.valueOf(ride.getValue("context").jsonObject.getValue("platform").jsonPrimitive.content)
        val withoutHealth = JsonObject(ride + ("health_sample" to JsonArray(emptyList()))).toString()
        return application(platform, null) {
            EncoderFixtureLoader.load(it, withoutHealth).let { load -> assertIs<GoldenLoad.Loaded>(load, "$load") }
        }
    }

    override suspend fun api(picker: FilePicker): PowerLogApi = application(Platform.web, picker) {}

    private suspend fun application(
        platform: Platform,
        picker: FilePicker?,
        load: suspend (ArchiveWriter) -> Unit,
    ): PowerLogApi {
        val clock = WebClock()
        val stores = WebStores.create(archiveName, Unlogged, clock::read)
        val post: (() -> Unit) -> Unit = { block -> Promise.resolve(Unit).then { block() } }
        val loop = Dispatchers.Default
        val application =
            PowerLogApplication(
                executors = Executors(ride = loop, health = loop, maintenance = loop, reads = loop),
                clock = clock,
                stores =
                    ApplicationStores(
                        host = stores.host,
                        writer = stores.writer,
                        rides = stores.rides,
                        measurements = stores.measurements,
                        connection = stores.connection,
                        maintenance = stores.maintenance,
                        health = stores.health,
                        settings = stores.settings,
                        cache = stores.cache,
                        memory = MemoryMeasurements(),
                        log = null,
                    ),
                device = FakeDeviceInfo(FakeDeviceInfo.snapshot(platform)),
                permissions = FakePermissionGate(post),
                picker = picker ?: FakeFilePicker(post),
                ble = null,
                location = null,
                health = null,
                keepAlive = null,
                tabLock = null,
                persistence = null,
                rideCard = null,
                watch = null,
                watchCodec = null,
                scheduler = null,
                exampleRides = null,
                developerTools = false,
                crashLines = null,
                unexpected = { component, error, at ->
                    LogLine(
                        LogLevel.error,
                        at.utcUs,
                        at.monoUs,
                        "unexpected",
                        listOf(LogField(component, LogValue.Text("$error"))),
                    )
                },
            )
        application.attach()
        application.host.launch(LaunchContext(LaunchReason.userInterface))
        load(stores.writer)
        return application.api
    }

    private object Unlogged : DiagnosticLogRepository {
        override suspend fun append(line: LogLine) = Unit

        override suspend fun read(after: Long, maxBytes: Int): StoreResult<LogChunk> =
            StoreResult.Ok(LogChunk(ByteArray(0), null))
    }
}

private fun storable(fixture: String, kind: ExportKind): (dynamic) -> TestResult = { fixtures ->
    GlobalScope.promise {
            EncoderPagesBrowserSuite(fixtures, "power-log-encoder-pages-$fixture-$kind")
                .checkStorable(fixture, kind, health = false)
        }
        .unsafeCast<TestResult>()
}

internal val encoderPagesCases: List<Pair<String, BrowserCase>> =
    listOf(
        browserCase(
            "EncoderPagesBrowserSuite.androidControllerDistanceFit",
            storable("android-controller-distance", ExportKind.fit),
        ),
        browserCase(
            "EncoderPagesBrowserSuite.androidControllerDistanceCsv",
            storable("android-controller-distance", ExportKind.csv),
        ),
        browserCase(
            "EncoderPagesBrowserSuite.androidControllerDistanceZip",
            storable("android-controller-distance", ExportKind.zip),
        ),
        browserCase("EncoderPagesBrowserSuite.androidGpsDistanceFit", storable("android-gps-distance", ExportKind.fit)),
        browserCase("EncoderPagesBrowserSuite.androidGpsDistanceCsv", storable("android-gps-distance", ExportKind.csv)),
        browserCase("EncoderPagesBrowserSuite.androidGpsDistanceZip", storable("android-gps-distance", ExportKind.zip)),
        browserCase(
            "EncoderPagesBrowserSuite.iosControllerDistanceFit",
            storable("ios-controller-distance", ExportKind.fit),
        ),
        browserCase(
            "EncoderPagesBrowserSuite.iosControllerDistanceCsv",
            storable("ios-controller-distance", ExportKind.csv),
        ),
        browserCase(
            "EncoderPagesBrowserSuite.iosControllerDistanceZip",
            storable("ios-controller-distance", ExportKind.zip),
        ),
        browserCase("EncoderPagesBrowserSuite.iosGpsDistanceFit", storable("ios-gps-distance", ExportKind.fit)),
        browserCase("EncoderPagesBrowserSuite.iosGpsDistanceCsv", storable("ios-gps-distance", ExportKind.csv)),
        browserCase("EncoderPagesBrowserSuite.iosGpsDistanceZip", storable("ios-gps-distance", ExportKind.zip)),
    )
