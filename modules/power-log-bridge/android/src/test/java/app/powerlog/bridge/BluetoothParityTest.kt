package app.powerlog.bridge

import android.Manifest
import android.bluetooth.*
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.powerlog.core.presentation.RideFailure
import app.powerlog.core.telemetry.*
import app.powerlog.core.telemetry.cyc.CycProtocol
import java.io.File
import java.time.Duration
import java.util.UUID
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
import org.robolectric.shadows.ShadowBluetoothAdapter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class BluetoothParityTest {
    private val context = RuntimeEnvironment.getApplication()
    private val handler = Handler(Looper.getMainLooper())
    private val adapter = context.getSystemService(BluetoothManager::class.java).adapter
    private val id = "02:00:00:00:00:01"
    private val states = mutableListOf<Payload>()
    private var recording = false
    private lateinit var bluetooth: ControllerBluetooth
    private val fixture = JSONObject(File("../../../tests/fixtures/protocol.json").readText())

    @Before
    fun setup() {
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
        shadowOf(adapter.getRemoteDevice(id)).setName("Fixture bike")
        bluetooth =
            ControllerBluetooth(
                context,
                handler,
                { event, value -> if (event == "onState") states.add(value) },
                { _, _, _ -> },
                { recording },
            )
    }

    @After
    fun close() {
        bluetooth.disconnect()
        handler.removeCallbacksAndMessages(null)
    }

    private fun field(name: String): Any? =
        ControllerBluetooth::class.java.getDeclaredField(name).apply { isAccessible = true }.get(bluetooth)

    private fun field(name: String, value: Any?) =
        ControllerBluetooth::class.java.getDeclaredField(name).apply { isAccessible = true }.set(bluetooth, value)

    private fun advance(millis: Long) = shadowOf(handler.looper).idleFor(Duration.ofMillis(millis))

    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun receive(frame: ByteArray) = receiveAdapter(bluetooth, frame)

    private fun identity() {
        val payload = bytes(fixture.getJSONObject("identity").getString("payloadHex"))
        val crc = CycProtocol.crc(payload)
        stageIdentity(bluetooth)
        setAdapterRequest(bluetooth, ControllerRequest.identity, SystemClock.elapsedRealtime() / 1000.0)
        receive(byteArrayOf(2, payload.size.toByte()) + payload + byteArrayOf((crc shr 8).toByte(), crc.toByte(), 3))
    }

    private fun telemetry() {
        setAdapterRequest(bluetooth, ControllerRequest.telemetry, SystemClock.elapsedRealtime() / 1000.0)
        receive(bytes(fixture.getJSONArray("telemetry").getJSONObject(2).getString("frameHex")))
    }

    private fun failConnection(peer: Boolean = false) {
        ControllerBluetooth::class
            .java
            .getDeclaredMethod(
                "failed",
                TelemetryDiagnosticReason::class.java,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Map::class.java,
                RideFailure::class.java,
            )
            .apply { isAccessible = true }
            .invoke(
                bluetooth,
                TelemetryDiagnosticReason.linkDisconnected,
                peer,
                false,
                emptyMap<String, Any?>(),
                RideFailure.bikeUnavailable,
            )
    }

    private fun handshakeToIdentity() {
        val gatt = field("gatt") as BluetoothGatt
        val notify = installControllerService(gatt)
        shadowOf(gatt).notifyConnection(gatt.device.address)
        advance(0)
        shadowOf(gatt).gattCallback.onDescriptorWrite(gatt, notify.descriptors.single(), BluetoothGatt.GATT_SUCCESS)
        advance(0)
        assertEquals(ControllerRequest.identity, field("pending"))
    }

    private fun logRows() = bluetooth.diagnosticLog.read().lines().filter { it.isNotBlank() }.map(::JSONObject)

    @Test
    fun stopSearchPublishesIdleAndEachSearchHasTwentySeconds() {
        bluetooth.startScan()
        advance(15000)
        assertEquals("scanning", bluetooth.state["status"])
        bluetooth.stopScan()
        assertEquals("idle", states.last()["status"])
        bluetooth.startScan()
        advance(5000)
        assertEquals("scanning", bluetooth.state["status"])
        advance(14999)
        assertEquals("scanning", bluetooth.state["status"])
        advance(1)
        assertEquals("error", states.last()["status"])
    }

    @Test
    fun aSearchThatFindsNothingSaysSoAtItsDeadlineButAManualStopDoesNot() {
        bluetooth.startScan()
        advance(20000)
        assertEquals("error", bluetooth.state["status"])
        assertEquals(RideFailure.bikeNotFound.androidText, bluetooth.state["error"])
        assertEquals("bikeNotFound", bluetooth.state["errorCode"])
        bluetooth.startScan()
        assertNull(bluetooth.state["error"])
        advance(1000)
        bluetooth.stopScan()
        advance(20000)
        assertEquals("idle", bluetooth.state["status"])
        assertNull(bluetooth.state["error"])
        bluetooth.startScan()
        (field("scanner") as android.bluetooth.le.ScanCallback).onScanResult(
            0,
            android.bluetooth.le.ScanResult(adapter.getRemoteDevice(id), null, -50, 0),
        )
        advance(20000)
        assertEquals("idle", bluetooth.state["status"])
        assertNull(bluetooth.state["error"])
    }

    @Test
    fun aFailedSearchShowsPlainTextAndKeepsItsCodeInTheConnectionLog() {
        bluetooth.startScan()
        (field("scanner") as android.bluetooth.le.ScanCallback).onScanFailed(2)
        advance(0)
        assertEquals("error", bluetooth.state["status"])
        assertEquals("Bluetooth search failed. Try again.", bluetooth.state["error"])
        assertEquals("bluetoothSearchFailed", bluetooth.state["errorCode"])
        val failure = logRows().single { it.getString("event") == "transport_error" }
        assertEquals("platform_error", failure.getString("reason"))
        assertEquals(2, failure.getInt("errorCode"))
        assertEquals("scanning", failure.getString("status"))
    }

    @Test
    fun unsupportedIdentityAndTelemetryShowTheSharedTexts() {
        bluetooth.connect(id, 4) {}
        stageIdentity(bluetooth)
        setAdapterRequest(bluetooth, ControllerRequest.identity, SystemClock.elapsedRealtime() / 1000.0)
        receive(controllerFrame(byteArrayOf(111, 5, 3) + "X1 20260618".toByteArray() + byteArrayOf(0)))
        assertEquals("error", bluetooth.state["status"])
        assertEquals(RideFailure.controllerUnsupported.androidText, bluetooth.state["error"])
        assertEquals("controllerUnsupported", bluetooth.state["errorCode"])
        bluetooth.disconnect()
        completeCancellation(bluetooth, handler)
        bluetooth.connect(id, 4) {}
        identity()
        val payload = bytes(fixture.getJSONArray("telemetry").getJSONObject(2).getString("payloadHex"))
        payload[4] = (payload[4] + 1).toByte()
        setAdapterRequest(bluetooth, ControllerRequest.telemetry, SystemClock.elapsedRealtime() / 1000.0)
        receive(controllerFrame(payload))
        assertEquals("error", bluetooth.state["status"])
        assertEquals(
            "This controller's firmware isn't supported yet. Share diagnostics from Settings so support can be added.",
            bluetooth.state["error"],
        )
        assertEquals("firmwareUnsupported", bluetooth.state["errorCode"])
        val rejections = logRows().filter { it.has("rejection") }.map { it.getString("rejection") }
        assertEquals(listOf("identity", "telemetryMask"), rejections)
        val stopped = logRows().filter { it.getString("event") == "session_stopped" }.map { it.getString("reason") }
        assertEquals(listOf("identity_rejected", "telemetry_rejected"), stopped.filter { it.endsWith("_rejected") })
    }

    @Test
    fun aDeviceThatNeverAnswersIdentityIsRejectedOnItsSecondAttemptUnlessItIsAKnownController() {
        @Suppress("UNCHECKED_CAST") val known = field("knownControllers") as MutableMap<String, ControllerIdentity>
        for (verified in listOf(false, true)) {
            known.clear()
            if (verified) known[id] = CycProtocol.restoreIdentity("X6", "20250725", "5.3")
            bluetooth.connect(id, 4) {}
            handshakeToIdentity()
            advance(3000)
            assertEquals("reconnecting", bluetooth.state["status"])
            completeCancellation(bluetooth, handler)
            advance(2000)
            handshakeToIdentity()
            advance(3000)
            if (verified) {
                assertEquals("reconnecting", bluetooth.state["status"])
            } else {
                assertEquals("error", bluetooth.state["status"])
                assertEquals(RideFailure.controllerNoReply.androidText, bluetooth.state["error"])
                assertEquals("controllerNoReply", bluetooth.state["errorCode"])
                assertEquals(2, bluetooth.diagnostics()["connectionAttempts"])
            }
            bluetooth.disconnect()
            completeCancellation(bluetooth, handler)
        }
    }

    @Test
    fun manualStopSearchImmediatelyPublishesIdleAndCancelsItsDeadline() {
        bluetooth.startScan()
        advance(1000)
        bluetooth.stopScan()
        assertEquals("idle", states.last()["status"])
        states.clear()
        advance(20000)
        assertTrue(states.isEmpty())
    }

    @Test
    fun restartingSearchAndConnectingDoNotPublishAnIntermediateIdle() {
        bluetooth.startScan()
        states.clear()
        bluetooth.startScan()
        assertEquals(listOf("scanning"), states.map { it["status"] })
        states.clear()
        bluetooth.connect(id, 4) {}
        assertEquals(listOf("connecting"), states.map { it["status"] })
        bluetooth.stopScan()
        assertEquals("connecting", bluetooth.state["status"])
    }

    @Test
    fun connectCompletesOnAdmissionAndLaterFailureOnlyChangesState() {
        var completions = 0
        bluetooth.connect(id, 4) {
            assertNull(it)
            completions++
        }
        assertEquals(1, completions)
        assertEquals("connecting", bluetooth.state["status"])
        failConnection()
        assertEquals(1, completions)
        assertEquals("reconnecting", bluetooth.state["status"])
        bluetooth.disconnect()
        advance(60000)
        assertEquals(1, completions)
        assertEquals("idle", bluetooth.state["status"])
        assertEquals(1, bluetooth.diagnostics()["connectionAttempts"])
    }

    @Test
    fun anotherConnectIsRejectedUntilTheCurrentControllerIsDisconnected() {
        bluetooth.connect(id, 4) {}
        for (status in listOf("connecting", "connected", "reconnecting")) {
            field("state", bluetooth.state + ("status" to status))
            for (device in listOf(id, "02:00:00:00:00:02")) {
                val error =
                    assertThrows(IllegalStateException::class.java) {
                        bluetooth.connect(device, 8) { fail("Unexpected admission") }
                    }
                assertEquals("Disconnect the current controller before connecting again.", error.message)
                assertEquals(4, bluetooth.hz)
                assertEquals(1, bluetooth.diagnostics()["connectionAttempts"])
            }
        }
    }

    @Test
    fun bluetoothOffRejectsAdmissionWithoutStartingAnAttempt() {
        shadowOf(adapter).setState(BluetoothAdapter.STATE_OFF)
        val error =
            assertThrows(RideFailureException::class.java) {
                bluetooth.connect(id, 4) { fail("Unexpected admission") }
            }
        assertEquals(RideFailure.bluetoothOff, error.failure)
        assertEquals(RideFailure.bluetoothOff, RideFailures.classify(error, "bike"))
        assertEquals(0, bluetooth.diagnostics()["connectionAttempts"])
        assertEquals("idle", bluetooth.state["status"])
    }

    @Test
    fun searchAndConnectNameTheBluetoothStateThatBlocksThem() {
        shadowOf(adapter).setState(BluetoothAdapter.STATE_OFF)
        assertEquals(
            RideFailure.bluetoothOff,
            assertThrows(RideFailureException::class.java) { bluetooth.startScan() }.failure,
        )
        shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
        adapterSession(bluetooth).manualDisconnect(true)
        assertEquals(
            RideFailure.bluetoothNotReady,
            assertThrows(RideFailureException::class.java) {
                    bluetooth.connect(id, 4) { fail("Unexpected admission") }
                }
                .failure,
        )
        assertEquals(0, bluetooth.diagnostics()["connectionAttempts"])
    }

    @Test
    fun aPhoneWithoutBluetoothSaysSo() {
        ShadowBluetoothAdapter.setIsBluetoothSupported(false)
        assertEquals(
            RideFailure.bluetoothUnsupported,
            assertThrows(RideFailureException::class.java) { bluetooth.startScan() }.failure,
        )
        assertEquals(
            RideFailure.bluetoothUnsupported,
            assertThrows(RideFailureException::class.java) { bluetooth.validateConnect(id, 4) }.failure,
        )
    }

    @Test
    fun manualDisconnectKeepsSelectedIdentityAndSelectingAnotherBikeDoesNotReuseIt() {
        bluetooth.connect(id, 4) {}
        identity()
        telemetry()
        val connected = bluetooth.state
        bluetooth.disconnect()
        completeCancellation(bluetooth, handler)
        for (key in listOf("deviceId", "deviceName", "controllerModel", "firmwareLabel")) {
            assertNotNull(connected[key])
            assertEquals(connected[key], bluetooth.state[key])
        }
        assertEquals("Fixture bike", bluetooth.state["deviceName"])
        assertNull(bluetooth.deviceId)
        bluetooth.connect("02:00:00:00:00:02", 4) {}
        assertNull(bluetooth.state["controllerModel"])
        bluetooth.disconnect()
        completeCancellation(bluetooth, handler)
        bluetooth.connect(id, 4) {}
        assertEquals(connected["controllerModel"], bluetooth.state["controllerModel"])
    }

    @Test
    fun retryKeepsIdentityAndRecoveryErrorUntilFreshTelemetry() {
        bluetooth.connect(id, 4) {}
        identity()
        telemetry()
        val model = bluetooth.state["controllerModel"]
        failConnection()
        completeCancellation(bluetooth, handler)
        advance(1000)
        assertEquals("reconnecting", bluetooth.state["status"])
        assertEquals(model, bluetooth.state["controllerModel"])
        assertEquals(RideFailure.bikeReconnecting.androidText, bluetooth.state["error"])
        assertEquals(true, bluetooth.state["recoverableConnectionError"])
        identity()
        assertEquals(RideFailure.bikeReconnecting.androidText, bluetooth.state["error"])
        telemetry()
        assertEquals("connected", bluetooth.state["status"])
        assertNull(bluetooth.state["error"])
        assertEquals(false, bluetooth.state["recoverableConnectionError"])
    }

    @Test
    fun oneTwentySecondDeadlineCoversConnectionDiscoveryAndIdentity() {
        bluetooth.connect(id, 4) {}
        val gatt = field("gatt") as BluetoothGatt
        advance(15000)
        assertEquals("connecting", bluetooth.state["status"])
        val service =
            BluetoothGattService(UUID.fromString(ControllerGatt.SERVICE), BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val writer =
            BluetoothGattCharacteristic(
                UUID.fromString(ControllerGatt.WRITE),
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            )
        val notify =
            BluetoothGattCharacteristic(
                UUID.fromString(ControllerGatt.NOTIFY),
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ,
            )
        notify.addDescriptor(
            BluetoothGattDescriptor(
                UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
                BluetoothGattDescriptor.PERMISSION_WRITE,
            )
        )
        service.addCharacteristic(writer)
        service.addCharacteristic(notify)
        shadowOf(gatt).addDiscoverableService(service)
        shadowOf(gatt).allowCharacteristicNotification(notify)
        advance(4000)
        shadowOf(gatt)
            .gattCallback
            .onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        advance(0)
        advance(999)
        assertEquals("connecting", bluetooth.state["status"])
        advance(1)
        assertEquals("reconnecting", bluetooth.state["status"])
        assertEquals(RideFailure.bikeReconnecting.androidText, bluetooth.state["error"])
        assertFalse(shadowOf(gatt).isClosed)
        completeCancellation(bluetooth, handler)
        assertTrue(shadowOf(gatt).isClosed)
        advance(1999)
        assertEquals(1, bluetooth.diagnostics()["connectionAttempts"])
        advance(1)
        assertEquals(2, bluetooth.diagnostics()["connectionAttempts"])
    }

    @Test
    fun missingControllerServiceFollowsTheSharedDiscoveryRule() {
        val rows = telemetryFixture("transport").getJSONArray("discovery")
        @Suppress("UNCHECKED_CAST") val known = field("knownControllers") as MutableMap<String, ControllerIdentity>
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            if (row.getBoolean("platformError")) continue
            recording = row.getBoolean("active")
            known.clear()
            if (row.getBoolean("known")) known[id] = CycProtocol.restoreIdentity("X6", "20250725", "5.3")
            bluetooth.connect(id, 4) {}
            val gatt = field("gatt") as BluetoothGatt
            shadowOf(gatt)
                .addDiscoverableService(
                    BluetoothGattService(UUID.randomUUID(), BluetoothGattService.SERVICE_TYPE_PRIMARY)
                )
            shadowOf(gatt)
                .gattCallback
                .onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
            advance(0)
            assertEquals(
                row.toString(),
                if (row.getBoolean("retry")) "reconnecting" else "error",
                bluetooth.state["status"],
            )
            bluetooth.disconnect()
            completeCancellation(bluetooth, handler)
        }
    }

    @Test
    fun connectionDeadlineUsesFiveRetriesThenStopsUnlessARideIsActive() {
        for (active in listOf(false, true)) {
            recording = active
            bluetooth.connect(id, 4) {}
            for (delay in listOf(1000L, 2000L, 4000L, 8000L, 16000L)) {
                advance(20000)
                assertEquals("reconnecting", bluetooth.state["status"])
                completeCancellation(bluetooth, handler)
                advance(delay)
            }
            advance(20000)
            if (active) {
                assertEquals("reconnecting", bluetooth.state["status"])
                val attempts = bluetooth.diagnostics()["connectionAttempts"] as Int
                completeCancellation(bluetooth, handler)
                advance(29999)
                assertEquals(attempts, bluetooth.diagnostics()["connectionAttempts"])
                advance(1)
                assertEquals(attempts + 1, bluetooth.diagnostics()["connectionAttempts"])
            } else {
                assertEquals("error", bluetooth.state["status"])
                assertEquals(RideFailure.bikeUnavailable.androidText, bluetooth.state["error"])
                assertEquals("bikeUnavailable", bluetooth.state["errorCode"])
            }
            bluetooth.disconnect()
            completeCancellation(bluetooth, handler)
        }
    }

    @Test
    fun cancellationBeforeConnectionClosesWithoutCallbackAndAllowsReconnect() {
        bluetooth.connect(id, 4) {}
        val old = field("gatt") as BluetoothGatt
        bluetooth.disconnect()
        assertTrue(shadowOf(old).isClosed)
        assertFalse(adapterSession(bluetooth).state.cancellationPending)
        bluetooth.connect(id, 4) {}
        shadowOf(old).gattCallback.onConnectionStateChange(old, 0, BluetoothProfile.STATE_CONNECTED)
        advance(0)
        assertEquals(1, bluetooth.diagnostics()["connectionAttempts"])
        assertEquals("connecting", bluetooth.state["status"])
    }

    @Test fun establishedCancellationTimeoutRetainsIdleRetryCount() = cancellationTimeout(false)

    @Test fun establishedCancellationTimeoutRetainsRecordingRetryCount() = cancellationTimeout(true)

    private fun cancellationTimeout(active: Boolean) {
        recording = active
        bluetooth.connect(id, 4) {}
        val old = field("gatt") as BluetoothGatt
        shadowOf(old).gattCallback.onConnectionStateChange(old, 0, BluetoothProfile.STATE_CONNECTED)
        advance(0)
        advance(11000)
        assertTrue(shadowOf(old).isClosed)
        assertFalse(adapterSession(bluetooth).state.cancellationPending)
        assertEquals(id, bluetooth.deviceId)
        assertEquals(1, field("retries"))
        assertEquals(2, bluetooth.diagnostics()["connectionAttempts"])
        val current = field("gatt")
        shadowOf(old).gattCallback.onConnectionStateChange(old, 0, BluetoothProfile.STATE_DISCONNECTED)
        advance(0)
        assertSame(current, field("gatt"))
    }

    @Test
    fun radioOffReleasesHandleAndRadioOnResumesIntent() {
        bluetooth.connect(id, 4) {}
        val old = field("gatt") as BluetoothGatt
        shadowOf(adapter).setState(BluetoothAdapter.STATE_OFF)
        context.sendBroadcast(
            android.content
                .Intent(BluetoothAdapter.ACTION_STATE_CHANGED)
                .putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF)
        )
        advance(0)
        assertTrue(shadowOf(old).isClosed)
        assertEquals(id, bluetooth.deviceId)
        assertEquals(RideFailure.bluetoothOff.androidText, bluetooth.state["error"])
        shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
        context.sendBroadcast(
            android.content
                .Intent(BluetoothAdapter.ACTION_STATE_CHANGED)
                .putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_ON)
        )
        advance(0)
        assertEquals(2, bluetooth.diagnostics()["connectionAttempts"])
        assertEquals(RideFailure.bikeReconnecting.androidText, bluetooth.state["error"])
    }

    @Test
    fun aLostLinkShowsTheSharedReconnectingText() {
        bluetooth.connect(id, 4) {}
        handshakeToIdentity()
        identity()
        telemetry()
        assertEquals("connected", bluetooth.state["status"])
        val gatt = field("gatt") as BluetoothGatt
        shadowOf(gatt).gattCallback.onConnectionStateChange(gatt, 8, BluetoothProfile.STATE_DISCONNECTED)
        advance(0)
        assertEquals("reconnecting", bluetooth.state["status"])
        assertEquals("Connection to your bike was lost. Reconnecting…", bluetooth.state["error"])
        assertNull(bluetooth.state["errorCode"])
        assertEquals(true, bluetooth.state["recoverableConnectionError"])
        val lost = logRows().single { it.getString("event") == "transport_error" }
        assertEquals("link_disconnected", lost.getString("reason"))
        assertEquals(8, lost.getInt("errorCode"))
    }

    @Test
    fun anUnansweredRequestIsSentAgainAndOnlyThreeSilentSecondsCloseTheLink() {
        recording = true
        bluetooth.connect(id, 2) {}
        handshakeToIdentity()
        identity()
        val resends = { logRows().count { it.getString("event") == "request_resent" } }
        advance(990)
        assertEquals(0, resends())
        advance(20)
        assertEquals(1, resends())
        telemetry()
        assertEquals("connected", bluetooth.state["status"])
        assertNull(field("unansweredSince"))
        advance(field("nextPollAt") as Long - SystemClock.elapsedRealtime())
        assertNotNull(field("unansweredSince"))
        advance(2990)
        assertEquals("connected", bluetooth.state["status"])
        advance(20)
        assertEquals("reconnecting", bluetooth.state["status"])
        assertEquals(3, resends())
        assertEquals(
            "response_timeout",
            logRows().single { it.getString("event") == "transport_error" }.getString("reason"),
        )
        assertEquals(
            2.0,
            logRows().single { it.getString("event") == "reconnect_scheduled" }.getDouble("retryDelaySeconds"),
            0.0,
        )
    }

    @Test
    fun onlyThreeSecondsOfTelemetryRestartTheBackoff() {
        recording = true
        bluetooth.connect(id, 2) {}
        identity()
        telemetry()
        field("retries", 5)
        advance(600)
        telemetry()
        assertEquals(5, field("retries"))
        advance(2500)
        telemetry()
        assertEquals(0, field("retries"))
    }

    @Test
    fun connectionsThatKeepFailingOutsideARideShowTheSharedCouldNotConnectText() {
        bluetooth.connect(id, 4) {}
        val failAttempt = {
            val gatt = field("gatt") as BluetoothGatt
            shadowOf(gatt).gattCallback.onConnectionStateChange(gatt, 133, BluetoothProfile.STATE_DISCONNECTED)
            advance(0)
        }
        for (delay in listOf(1000L, 2000L, 4000L, 8000L, 16000L)) {
            failAttempt()
            assertEquals(RideFailure.bikeReconnecting.androidText, bluetooth.state["error"])
            advance(delay)
        }
        failAttempt()
        assertEquals("error", bluetooth.state["status"])
        assertEquals(
            "Power Log couldn't connect to your bike. " +
                "Close other apps connected to the bike, turn its display off and on, then connect again.",
            bluetooth.state["error"],
        )
        assertEquals("bikeUnavailable", bluetooth.state["errorCode"])
        assertEquals(false, bluetooth.state["recoverableConnectionError"])
        val reasons = logRows().filter { it.getString("event") == "transport_error" }.map { it.getString("reason") }
        assertEquals(List(6) { "connection_failed" }, reasons)
        assertEquals(
            "retry_limit",
            logRows().single { it.getString("event") == "session_stopped" }.getString("reason"),
        )
    }

    @Test
    fun lateDiscoveryFromAnEarlierSearchCannotPopulateTheNewSearch() {
        val deadline = telemetryFixture("deadlines")
        bluetooth.startScan()
        val old = field("scanner") as android.bluetooth.le.ScanCallback
        bluetooth.startScan()
        old.onScanResult(0, android.bluetooth.le.ScanResult(adapter.getRemoteDevice(id), null, -50, 0))
        advance(0)
        assertTrue((field("devices") as Map<*, *>).isEmpty())
        val current = field("scanner") as android.bluetooth.le.ScanCallback
        advance((deadline.getDouble("attemptDeadline") * 1000).toLong())
        current.onScanResult(0, android.bluetooth.le.ScanResult(adapter.getRemoteDevice(id), null, -50, 0))
        advance(0)
        assertTrue((field("devices") as Map<*, *>).isEmpty())
        assertEquals("bikeNotFound", bluetooth.state["errorCode"])
    }

    @Test
    fun retryFixturesUseTheAdapterAndDoNotExtendTheCancellationFence() {
        val rows = telemetryFixture("transport").getJSONArray("retries")
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            recording = row.getBoolean("active")
            bluetooth.connect(id, 4) {}
            field("established", !row.optBoolean("disconnected", true))
            field("retries", row.getInt("attempt") - 1)
            field(
                "stableSince",
                if (row.isNull("stable")) null else SystemClock.elapsedRealtime() / 1000.0 - row.getDouble("stable"),
            )
            failConnection(peer = row.getBoolean("peer") && row.getBoolean("completed"))
            val transport = adapterSession(bluetooth).state
            if (row.isNull("delay")) assertEquals("error", bluetooth.state["status"])
            else {
                assertEquals(
                    row.getDouble("delay"),
                    transport.reconnectDue!! - SystemClock.elapsedRealtime() / 1000.0,
                    0.000001,
                )
                assertEquals(transport.reconnectDue!! + 5, transport.cancellationDeadline!!, 0.0)
            }
            bluetooth.disconnect()
            completeCancellation(bluetooth, handler)
        }
    }

    @Test
    fun cancellationCompletionAndOldCallbacksFollowTheDeadlineFixture() {
        bluetooth.connect(id, 4) {}
        val old = field("gatt") as BluetoothGatt
        advance(20000)
        assertTrue(shadowOf(old).isClosed)
        assertFalse(adapterSession(bluetooth).state.cancellationPending)
        advance(1000)
        val current = field("gatt")
        shadowOf(old).gattCallback.onConnectionStateChange(old, 0, BluetoothProfile.STATE_DISCONNECTED)
        advance(0)
        assertSame(current, field("gatt"))
        assertEquals(2, bluetooth.diagnostics()["connectionAttempts"])
    }

    @Test
    fun preConnectTimeoutRejectsLateNativeConnectionWithoutWaitingForCancellation() {
        bluetooth.connect(id, 4) {}
        val old = field("gatt") as BluetoothGatt
        advance(20000)
        shadowOf(old).gattCallback.onConnectionStateChange(old, 0, BluetoothProfile.STATE_CONNECTED)
        advance(0)
        assertTrue(shadowOf(old).isClosed)
        assertFalse(adapterSession(bluetooth).state.cancellationPending)
        advance(1000)
        assertEquals(2, bluetooth.diagnostics()["connectionAttempts"])
        assertNotSame(old, field("gatt"))
    }
}
