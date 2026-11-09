@file:OptIn(DelicateCoroutinesApi::class)

package app.powerlog.runtime

import app.powerlog.application.YieldPolicy
import app.powerlog.application.contracts.BleScenarioRunner
import app.powerlog.application.inSlices
import app.powerlog.application.port.LocationEvents
import app.powerlog.application.port.LocationProviderState
import app.powerlog.application.port.PermissionKind
import app.powerlog.application.port.PermissionState
import app.powerlog.application.port.PermissionStates
import app.powerlog.application.port.Reply
import app.powerlog.application.port.TabLease
import app.powerlog.data.ARCHIVE_NAME
import app.powerlog.domain.core.time.ClockReading
import app.powerlog.domain.recording.LocationOriginal
import app.powerlog.domain.settings.PersistenceState
import app.powerlog.runtime.clock.WebClock
import app.powerlog.runtime.compose.WebLoop
import app.powerlog.runtime.web.ble.WebBleHarness
import app.powerlog.runtime.web.host.startWebRuntime
import app.powerlog.runtime.web.location.Geolocation
import app.powerlog.runtime.web.location.WebLocationSource
import app.powerlog.runtime.web.permissions.Permissions
import app.powerlog.runtime.web.permissions.PositionRequests
import app.powerlog.runtime.web.permissions.WebPermissionGate
import app.powerlog.runtime.web.persistence.StorageManager
import app.powerlog.runtime.web.persistence.WebStoragePersistence
import app.powerlog.runtime.web.tablock.LockManager
import app.powerlog.runtime.web.tablock.WebTabLock
import kotlin.js.Promise
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.w3c.dom.MessageChannel

internal val webAdapterCases: List<Pair<String, BrowserCase>> =
    listOf(
        browserCase("WebBleCentralBrowserSuite.everyWebScenarioPassesThroughAFakeNavigatorBluetooth") { fixtures ->
            settled {
                val runner = BleScenarioRunner(fixtures["tests/fixtures/contract/ports/ble-central.json"] as String)
                val protocol = fixtures["tests/fixtures/protocol.json"] as String
                val failures =
                    runner
                        .run("web") { WebBleHarness(runner.profiles.values.toList(), protocol) }
                        .filter { it.failure != null }
                check(failures.isEmpty()) { failures.joinToString("; ") { "${it.scenario}: ${it.failure}" } }
            }
        },
        browserCase("WebTabLockBrowserSuite.oneLeasePerNameGoesToAWaitingTabOnRelease") {
            GlobalScope.promise {
                    val name = "power-log-browser-tablock"
                    val held = acquire(name, waitIfHeld = false).await() ?: error("the free lock was refused")
                    check(acquire(name, waitIfHeld = false).await() == null) { "a held lock was granted twice" }
                    val waiting = acquire(name, waitIfHeld = true)
                    held.release()
                    (waiting.await() ?: error("the waiting tab got no lease")).release()
                }
                .unsafeCast<TestResult>()
        },
        browserCase("WebStoragePersistenceBrowserSuite.chromiumAnswersWhetherItKeepsTheStore") {
            GlobalScope.promise {
                    val persistence = WebStoragePersistence(js("navigator.storage").unsafeCast<StorageManager>())
                    val state = Promise { resolve, reject ->
                        persistence.state(Reply(onOk = resolve, onFailed = { reject(IllegalStateException("$it")) }))
                    }
                        .await()
                    check(state == PersistenceState.persisted || state == PersistenceState.notPersisted) {
                        "navigator.storage answered $state"
                    }
                }
                .unsafeCast<TestResult>()
        },
        browserCase("WebLocationSourceBrowserSuite.chromiumsOverriddenPositionArrivesAsAFix") {
            Promise<Unit> { resolve, reject ->
                    val source =
                        WebLocationSource(
                            js("navigator.geolocation").unsafeCast<Geolocation>(),
                            WebClock(),
                            {
                                Promise.resolve(Unit).then { _ -> it() }
                            },
                        )
                    source.attach(
                        object : LocationEvents {
                            override fun fixesReceived(batch: List<LocationOriginal>, at: ClockReading) {
                                source.stop()
                                val fix = batch.single()
                                if (fix.latitude == 52.52 && fix.longitude == 13.405 && fix.horizontalAccuracyM == 12.0)
                                    resolve(Unit)
                                else reject(IllegalStateException("got $fix"))
                            }

                            override fun providerChanged(state: LocationProviderState) {
                                if (state != LocationProviderState.waiting && state != LocationProviderState.available)
                                    reject(IllegalStateException("the provider turned $state"))
                            }
                        }
                    )
                    source.start()
                }
                .unsafeCast<TestResult>()
        },
        browserCase("WebPermissionGateBrowserSuite.aGrantedGeolocationReadsGrantedAndARequestAnswersGranted") {
            GlobalScope.promise {
                    val gate =
                        WebPermissionGate(
                            js("navigator.bluetooth != null").unsafeCast<Boolean>(),
                            js("navigator.geolocation").unsafeCast<PositionRequests>(),
                            js("navigator.permissions").unsafeCast<Permissions>(),
                        ) {
                            Promise.resolve(Unit).then { _ -> it() }
                        }
                    val status =
                        Promise<PermissionStates> { resolve, reject ->
                                gate.status(Reply(resolve) { reject(IllegalStateException("$it")) })
                            }
                            .await()
                    check(status.location == PermissionState.granted) { "status answered $status" }
                    val requested =
                        Promise<PermissionStates> { resolve, reject ->
                                gate.request(
                                    setOf(PermissionKind.location),
                                    Reply(resolve) { reject(IllegalStateException("$it")) },
                                )
                            }
                            .await()
                    check(requested.location == PermissionState.granted) { "request answered $requested" }
                }
                .unsafeCast<TestResult>()
        },
        browserCase("WebClockBrowserSuite.theMonotonicClockNeverGoesBack") {
            settled {
                val clock = WebClock()
                val readings = List(10_000) { clock.monoUs() }
                val back = readings.zipWithNext().firstOrNull { (previous, next) -> next < previous }
                check(back == null) { "${back?.second} went back from ${back?.first}" }
            }
        },
    )

internal val webHostCases: List<Pair<String, BrowserCase>> =
    listOf(
        browserCase("WebHostBrowserSuite.startWebRuntimeOpensTheArchiveAndServesDeviceInfo") {
            GlobalScope.promise {
                    val session = startWebRuntime(null, "0.7.0")
                    val answer = Promise { resolve, _ ->
                        session.next("device.info", """{"params":{},"after":null,"key":"device"}""", resolve)
                    }
                        .await()
                    val envelope = Json.parseToJsonElement(answer).jsonObject
                    check(envelope.getValue("ok").jsonPrimitive.boolean) { "device.info answered $answer" }
                    val value = envelope.getValue("value").jsonObject
                    check(value.getValue("platform").jsonPrimitive.content == "web") { "device.info answered $answer" }
                    check(value.getValue("appVersion").jsonPrimitive.content == "0.7.0") {
                        "device.info answered $answer"
                    }
                    repeat(50) {
                        val names = js("indexedDB.databases()").unsafeCast<Promise<Array<dynamic>>>().await()
                        if (names.any { it.name == ARCHIVE_NAME }) return@promise
                        delay(100)
                    }
                    error("no IndexedDB database named $ARCHIVE_NAME opened")
                }
                .unsafeCast<TestResult>()
        }
    )

internal val webSliceCases: List<Pair<String, BrowserCase>> =
    listOf(
        browserCase("SlicesBrowserSuite.everySliceOfAnEightHourRideWaitsForABrowserTask") {
            GlobalScope.promise {
                    val rows = 8 * 3_600 * 8
                    val ticker = MessageChannel()
                    var tasks = 0
                    ticker.port1.onmessage = {
                        tasks++
                        ticker.port2.postMessage(null)
                    }
                    ticker.port2.postMessage(null)
                    val seen = mutableListOf<Int>()
                    withContext(WebLoop) { inSlices(rows) { _, _ -> seen += tasks } }
                    ticker.port1.close()
                    check(seen.size == (rows + YieldPolicy.rows - 1) / YieldPolicy.rows) { "${seen.size} slices" }
                    val stalls = seen.zipWithNext().count { (before, after) -> after == before }
                    check(stalls == 0) { "$stalls of ${seen.size - 1} slices followed the last with no browser task" }
                }
                .unsafeCast<TestResult>()
        }
    )

private fun settled(work: () -> Unit): TestResult =
    Promise<Unit> { resolve, reject ->
            try {
                work()
                resolve(Unit)
            } catch (failure: Throwable) {
                reject(failure)
            }
        }
        .unsafeCast<TestResult>()

private fun acquire(name: String, waitIfHeld: Boolean): Promise<TabLease?> = Promise { resolve, reject ->
    WebTabLock(js("navigator.locks").unsafeCast<LockManager>())
        .acquire(name, waitIfHeld, Reply(onOk = resolve, onFailed = { reject(IllegalStateException("$it")) }))
}
