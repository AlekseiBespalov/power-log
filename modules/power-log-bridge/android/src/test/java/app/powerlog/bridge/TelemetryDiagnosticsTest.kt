package app.powerlog.bridge

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.os.Handler
import android.os.Looper
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.Executor
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class TelemetryDiagnosticsTest {
    private val context = RuntimeEnvironment.getApplication()
    private val handler = Handler(Looper.getMainLooper())
    private val directory = Files.createTempDirectory("diagnostics").toFile()
    private val direct = Executor { it.run() }
    private val contract = telemetryFixture("diagnostic-log")

    @Before
    fun setup() {
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        shadowOf(context.getSystemService(BluetoothManager::class.java).adapter).setState(BluetoothAdapter.STATE_ON)
    }

    @After
    fun close() {
        directory.deleteRecursively()
        handler.removeCallbacksAndMessages(null)
    }

    private fun rows(text: String) = text.lines().filter { it.isNotEmpty() }.map(::JSONObject)

    private fun strings(array: JSONArray) = (0 until array.length()).map(array::getString)

    @Test
    fun rotationKeepsTheNewestEntriesWithinTwoFiles() {
        val log = TelemetryDiagnosticLog(directory, 1024, direct)
        for (index in 0 until 50) log.append(
            TelemetryDiagnosticEvent.telemetrySummary,
            index.toDouble(),
            mapOf("sampleCount" to index),
        )
        val text = log.read()
        assertTrue(text.toByteArray().size <= 2048)
        val rows = rows(text)
        assertTrue(rows.first().getInt("sampleCount") > 0)
        assertEquals(49, rows.last().getInt("sampleCount"))
        assertEquals(setOf("events.jsonl", "events.previous.jsonl"), directory.list()!!.toSet())
    }

    @Test
    fun linesKeepOnlyListedFieldsInSortedOrder() {
        val log = TelemetryDiagnosticLog(directory, executor = direct)
        val marker = "PRIVATE_DEVICE_IDENTIFIER_SHOULD_NEVER_BE_EXPORTED"
        log.append(
            TelemetryDiagnosticEvent.transportError,
            51.0,
            mapOf(
                "deviceId" to marker,
                "rawPayload" to marker,
                "errorDomain" to marker,
                "reason" to marker,
                "status" to marker,
                "rejection" to marker,
                "transportStage" to "identity",
                "sampleCount" to Double.NaN,
                "background" to "yes",
                "errorCode" to 8,
            ),
        )
        val text = log.read()
        assertFalse(text.contains(marker))
        assertFalse(text.contains("NaN"))
        val row = rows(text).single()
        assertEquals(
            listOf(
                "elapsedSeconds",
                "errorCode",
                "errorDomain",
                "event",
                "schemaVersion",
                "timestamp",
                "transportStage",
            ),
            row.keys().asSequence().toList(),
        )
        assertEquals("OtherErrorDomain", row.getString("errorDomain"))
        assertEquals(1, row.getInt("schemaVersion"))
        assertEquals("transport_error", row.getString("event"))
        assertTrue(Regex("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}Z").matches(row.getString("timestamp")))
    }

    @Test
    fun vocabularyMatchesTheSharedContract() {
        assertEquals(contract.getInt("maximumFileBytes"), TelemetryDiagnosticLog.MAXIMUM_FILE_BYTES)
        assertEquals(strings(contract.getJSONArray("files")), listOf("events.previous.jsonl", "events.jsonl"))
        assertEquals(strings(contract.getJSONArray("events")), TelemetryDiagnosticEvent.entries.map { it.wire })
        assertEquals(strings(contract.getJSONArray("reasons")), TelemetryDiagnosticReason.entries.map { it.wire })
        val numbers = strings(contract.getJSONArray("numbers"))
        val booleans = strings(contract.getJSONArray("booleans"))
        val choices = contract.getJSONObject("strings")
        val domains = strings(contract.getJSONArray("errorDomains"))
        val log = TelemetryDiagnosticLog(directory, executor = direct)
        val fields = mutableMapOf<String, Any?>()
        numbers.forEachIndexed { index, name -> fields[name] = index + 0.5 }
        booleans.forEach { fields[it] = true }
        for (name in choices.keys()) fields[name] = choices.getJSONArray(name).getString(0)
        log.append(TelemetryDiagnosticEvent.telemetrySummary, 1.0, fields)
        for (domain in domains) log.append(TelemetryDiagnosticEvent.transportError, 2.0, mapOf("errorDomain" to domain))
        for (name in choices.keys()) for (value in strings(choices.getJSONArray(name))) log.append(
            TelemetryDiagnosticEvent.transportStage,
            3.0,
            mapOf(name to value),
        )
        val rows = rows(log.read())
        val summary = rows.first()
        numbers.forEachIndexed { index, name -> assertEquals(name, index + 0.5, summary.getDouble(name), 0.0) }
        booleans.forEach { assertTrue(it, summary.getBoolean(it)) }
        assertEquals(domains, rows.drop(1).take(domains.size).map { it.getString("errorDomain") })
        assertEquals(
            choices.keys().asSequence().sumOf { choices.getJSONArray(it).length() },
            rows.drop(1 + domains.size).count { row -> choices.keys().asSequence().any(row::has) },
        )
        assertEquals(contract.getString("unknownErrorDomain"), TelemetryDiagnosticLog.safeErrorDomain("Unknown"))
    }

    @Test
    fun reopeningDropsATornLineAndKeepsAppending() {
        TelemetryDiagnosticLog(directory, 1024, direct).append(TelemetryDiagnosticEvent.engineStarted, 0.0)
        File(directory, "events.jsonl").appendText("{\"event\":\"torn")
        val reopened = TelemetryDiagnosticLog(directory, 1024, direct)
        assertFalse(reopened.read().contains("torn"))
        reopened.append(TelemetryDiagnosticEvent.connectRequested, 1.0)
        assertEquals(listOf("engine_started", "connect_requested"), rows(reopened.read()).map { it.getString("event") })
    }

    @Test
    fun metricsSeparateLinkAndSessionCountersAndKeepTheAppCancellationOrigin() {
        val metrics = TelemetryDiagnosticMetrics(0.0)
        metrics.beginAttempt(1.0)
        metrics.connected(2.0)
        metrics.receivedSample(3.0, 0.02)
        metrics.receivedSample(3.125, 0.04)
        assertEquals(8.0, metrics.recentSampleHz(3.2)!!, 1e-9)
        assertEquals(mapOf("latencyMeanMs" to 30.0, "latencyMaxMs" to 40.0), metrics.latency())
        assertNull(metrics.recentSampleHz(6.0))
        metrics.timedOut()
        metrics.requestedCancellation(TelemetryDiagnosticReason.responseTimeout, 5.625)
        metrics.requestedCancellation(TelemetryDiagnosticReason.retryLimit, 5.75)
        val cancelled = metrics.disconnected(6.0, 0, TelemetryDiagnosticReason.linkDisconnected)
        assertEquals("app", cancelled["initiator"])
        assertEquals("response_timeout", cancelled["reason"])
        assertEquals(4.0, cancelled["connectionSeconds"])
        assertEquals(2, cancelled["linkSamples"])
        assertEquals(2.5, cancelled["sampleAgeSecondsAtCancel"])
        assertEquals(0.375, cancelled["cancellationCallbackSeconds"])
        assertEquals("AndroidGattStatus", cancelled["errorDomain"])
        metrics.scheduledReconnect()
        metrics.beginAttempt(7.0)
        metrics.connected(8.0)
        assertEquals(5.875, metrics.receivedSample(9.0, 0.01)!!, 0.0)
        assertEquals(5.875, metrics.lastGapSeconds!!, 0.0)
        assertEquals(
            listOf(3, 1, 2, 1),
            with(metrics) { listOf(sampleCount, linkSamples, connectionAttempts, reconnects) },
        )
        val peer = metrics.disconnected(10.0, 8, TelemetryDiagnosticReason.linkDisconnected)
        assertEquals("peer_or_system", peer["initiator"])
        assertEquals("link_disconnected", peer["reason"])
        assertEquals(2.0, peer["connectionSeconds"])
        assertEquals(8, peer["errorCode"])
        assertEquals(1, metrics.requestTimeouts)
    }

    @Test
    fun peerDisconnectIsLoggedWithItsGattStatusAndTheRetryPlan() =
        ControllerAdapterFixture(handler).use { fixture ->
            fixture.identity()
            shadowOf(fixture.gatt)
                .gattCallback
                .onConnectionStateChange(fixture.gatt, 8, BluetoothProfile.STATE_DISCONNECTED)
            shadowOf(handler.looper).idle()
            val rows = rows(fixture.bluetooth.diagnosticLog.read())
            val events = rows.map { it.getString("event") }
            assertEquals(
                listOf(
                    "engine_started",
                    "connect_requested",
                    "connection_attempt",
                    "link_connected",
                    "transport_stage",
                    "transport_stage",
                    "transport_stage",
                    "identity_verified",
                    "disconnected",
                    "transport_error",
                    "reconnect_scheduled",
                ),
                events,
            )
            val disconnected = rows[events.indexOf("disconnected")]
            assertEquals("peer_or_system", disconnected.getString("initiator"))
            assertEquals("link_disconnected", disconnected.getString("reason"))
            assertEquals("AndroidGattStatus", disconnected.getString("errorDomain"))
            assertEquals(8, disconnected.getInt("errorCode"))
            val retry = rows.last()
            assertEquals("reconnecting", retry.getString("status"))
            assertEquals("link_disconnected", retry.getString("reason"))
            assertEquals(1, retry.getInt("retryAttempt"))
            assertEquals(1, retry.getInt("reconnects"))
        }

    @Test
    fun aMissingTelemetryServiceStopsTheSessionWithItsReason() {
        val bluetooth = ControllerBluetooth(context, handler, { _, _ -> }, { _, _, _ -> }, { false })
        bluetooth.connect("02:00:00:00:00:01", 4) {}
        val gatt =
            ControllerBluetooth::class.java.getDeclaredField("gatt").apply { isAccessible = true }.get(bluetooth)
                as BluetoothGatt
        shadowOf(gatt)
            .addDiscoverableService(
                BluetoothGattService(
                    UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb"),
                    BluetoothGattService.SERVICE_TYPE_PRIMARY,
                )
            )
        shadowOf(gatt).notifyConnection(gatt.device.address)
        shadowOf(handler.looper).idle()
        val rows = rows(bluetooth.diagnosticLog.read())
        val failure = rows.first { it.getString("event") == "transport_error" }
        assertEquals("service_discovery", failure.getString("reason"))
        val stopped = rows.last()
        assertEquals("session_stopped", stopped.getString("event"))
        assertEquals("service_discovery", stopped.getString("reason"))
        assertEquals("error", stopped.getString("status"))
        bluetooth.disconnect()
    }

    @Test
    fun backgroundChangesAreLoggedOnce() {
        val bluetooth = ControllerBluetooth(context, handler, { _, _ -> }, { _, _, _ -> }, { false })
        bluetooth.setBackground(true)
        bluetooth.setBackground(true)
        bluetooth.setBackground(false)
        val rows = rows(bluetooth.diagnosticLog.read()).filter { it.getString("event") == "background_changed" }
        assertEquals(listOf(true, false), rows.map { it.getBoolean("background") })
    }
}
