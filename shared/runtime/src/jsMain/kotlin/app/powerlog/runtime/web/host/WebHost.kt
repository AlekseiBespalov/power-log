@file:OptIn(ExperimentalJsExport::class)

package app.powerlog.runtime.web.host

import app.powerlog.application.api.LaunchContext
import app.powerlog.application.api.LaunchReason
import app.powerlog.data.ARCHIVE_NAME
import app.powerlog.runtime.bridge.BridgeSession
import app.powerlog.runtime.clock.WebClock
import app.powerlog.runtime.compose.Ports
import app.powerlog.runtime.compose.WebRuntime
import app.powerlog.runtime.host.HostFacade
import app.powerlog.runtime.web.ble.Bluetooth
import app.powerlog.runtime.web.ble.WebBleCentral
import app.powerlog.runtime.web.device.BrowserNavigator
import app.powerlog.runtime.web.device.WebDeviceInfo
import app.powerlog.runtime.web.files.PickerDocument
import app.powerlog.runtime.web.files.PickerWindow
import app.powerlog.runtime.web.files.WebFilePicker
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

@JsExport
public fun startWebRuntime(archiveName: String?, appVersion: String): WebSession =
    WebHost.start(archiveName ?: ARCHIVE_NAME, appVersion, js("globalThis").unsafeCast<BrowserPage>())

@JsExport
public class WebSession internal constructor(private val session: BridgeSession) {
    public fun call(op: String, request: String, reply: (String) -> Unit): Unit = session.call(op, request, reply)

    public fun next(stream: String, request: String, reply: (String) -> Unit): Unit =
        session.next(stream, request, reply)

    public fun exportPage(request: String, reply: (page: dynamic) -> Unit): Unit =
        session.exportPage(request) { page ->
            val numbers: dynamic = js("({})")
            for ((name, column) in page.numbers) {
                val bytes: dynamic = column
                numbers[name] = Uint8Array(bytes.buffer, bytes.byteOffset, bytes.byteLength)
            }
            val plain: dynamic = js("({})")
            plain.envelope = page.envelope
            plain.numbers = numbers
            reply(plain)
        }
}

internal object WebHost {
    fun start(archiveName: String, appVersion: String, page: BrowserPage): WebSession {
        val clock = WebClock()
        val navigator = page.navigator
        val post: (() -> Unit) -> Unit = { block -> Promise.resolve(Unit).then { block() } }
        val bluetooth: dynamic = navigator.asDynamic().bluetooth
        val locks: dynamic = navigator.asDynamic().locks
        val geolocation = navigator.asDynamic().geolocation.unsafeCast<Geolocation?>()
        val permissions = navigator.asDynamic().permissions.unsafeCast<Permissions?>()
        val ports =
            Ports(
                permissions =
                    WebPermissionGate(
                        bluetooth != null,
                        geolocation?.unsafeCast<PositionRequests>(),
                        permissions,
                        post,
                    ),
                picker =
                    WebFilePicker(
                        page.document.unsafeCast<PickerDocument>(),
                        page.unsafeCast<PickerWindow>(),
                        { navigator.asDynamic().userActivation?.isActive != false },
                        post,
                    ),
                device = WebDeviceInfo(appVersion, navigator.unsafeCast<BrowserNavigator>()),
                ble = if (bluetooth == null) null else WebBleCentral(bluetooth.unsafeCast<Bluetooth>(), clock, post),
                location = geolocation?.let { WebLocationSource(it, clock, post) },
                health = null,
                keepAlive = null,
                tabLock = if (locks == null) null else WebTabLock(locks.unsafeCast<LockManager>()),
                persistence = WebStoragePersistence(navigator.asDynamic().storage.unsafeCast<StorageManager?>()),
                rideCard = null,
                watch = null,
                scheduler = null,
            )
        val runtime = WebRuntime.create(clock, ports, archiveName)
        runtime.host.launch(LaunchContext(LaunchReason.userInterface)) {}
        forward(runtime.host, page)
        return WebSession(runtime.openBridgeSession())
    }

    fun forward(host: HostFacade, page: BrowserPage) {
        val document = page.document
        host.foregroundChanged(document.visibilityState == "visible")
        document.addEventListener("visibilitychange") {
            val visible = document.visibilityState == "visible"
            host.foregroundChanged(visible)
            if (!visible) host.didEnterBackground {}
        }
        page.addEventListener("pagehide") { host.didEnterBackground {} }
    }
}

internal external interface BrowserPage {
    val navigator: Any
    val document: BrowserDocument

    fun addEventListener(type: String, listener: (event: dynamic) -> Unit)
}

internal external interface BrowserDocument {
    val visibilityState: String

    fun addEventListener(type: String, listener: (event: dynamic) -> Unit)
}

private external class Uint8Array(buffer: dynamic, byteOffset: Int, length: Int)
