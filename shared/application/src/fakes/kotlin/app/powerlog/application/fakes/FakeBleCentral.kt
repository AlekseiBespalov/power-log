package app.powerlog.application.fakes

import app.powerlog.application.port.Advertisement
import app.powerlog.application.port.BleCentral
import app.powerlog.application.port.BleEvents
import app.powerlog.application.port.Clock
import app.powerlog.application.port.GattCharacteristic
import app.powerlog.application.port.GattReady
import app.powerlog.application.port.GattService
import app.powerlog.application.port.PortError
import app.powerlog.application.port.PortErrorKind
import app.powerlog.application.port.RadioState
import app.powerlog.application.port.SendResult
import app.powerlog.domain.core.controller.ControllerFrame
import app.powerlog.domain.core.controller.GattResult
import app.powerlog.domain.core.controller.TransportProfile
import app.powerlog.domain.core.failure.PlatformCode
import app.powerlog.domain.core.ride.Platform

class FakeBleCentral(val platform: Platform, private val clock: Clock, private val post: (() -> Unit) -> Unit) :
    BleCentral {
    enum class Restored {
        connected,
        connecting,
        disconnected,
    }

    private enum class State {
        connecting,
        connected,
        reconnecting,
        cancelling,
        ended,
    }

    private class Link(val id: Long, val bike: String, val profile: TransportProfile) {
        var state = State.connecting
        var notifying = false
        var maxWriteBytes: Int? = null
    }

    private class Database(val services: List<GattService>, val maxWriteBytes: Int?, val notifyFails: Boolean)

    private var events: BleEvents? = null
    private var prepared = false
    private var osRadio = RadioState.poweredOn
    private var centralRadio = RadioState.unknown
    private var searching = false
    private var searchInventory = false
    private var chooserOpen = false
    private var nextLink = 1L
    private var link: Link? = null
    private var keptReconnect: String? = null
    private var keptConnected = false
    private var writeBusy = false
    private var canSendOwed = false
    private val known = mutableSetOf<String>()
    private val restored = mutableMapOf<String, Restored>()
    private val databases = mutableMapOf<String, Database>()
    private val calls = mutableListOf<String>()
    private val cancelledPending = mutableSetOf<String>()
    private val written = mutableListOf<ByteArray>()

    val osCalls: List<String>
        get() = calls

    val writes: List<ByteArray>
        get() = written

    val os: Os = Os()

    override fun attach(bleEvents: BleEvents) {
        check(events == null) { "attach is called once" }
        events = bleEvents
    }

    override fun prepare() {
        attached()
        if (prepared) return
        prepared = true
        calls += "prepare"
    }

    override fun radio(): RadioState = if (platform == Platform.ios) centralRadio else osRadio

    override fun startSearch(profiles: List<TransportProfile>, inventory: Boolean) {
        attached()
        check(!searching) { "one search at a time" }
        searching = true
        searchInventory = inventory
        chooserOpen = platform == Platform.web
        calls += "scan ${profiles.joinToString(",") { it.service }}"
    }

    override fun stopSearch() {
        attached()
        if (!searching) return
        searching = false
        calls += "stopScan"
    }

    override fun connect(bike: String, profile: TransportProfile, adopt: Boolean): Long {
        attached()
        val previous = link
        if (platform == Platform.android && previous?.state == State.cancelling) {
            val refused = Link(nextLink++, bike, profile)
            fail(refused, PortError(PortErrorKind.notReady))
            return refused.id
        }
        check(previous == null || previous.state == State.ended || previous.state == State.reconnecting) {
            "one link at a time: link ${previous?.id} is ${previous?.state}"
        }
        if (previous?.state == State.reconnecting) previous.state = State.ended
        val created = Link(nextLink++, bike, profile)
        link = created
        val adopting = adopt && platform == Platform.ios
        keptReconnect?.let { kept ->
            if (!adopting || kept != bike) {
                calls += "cancel reconnect $kept"
                keptReconnect = null
            }
        }
        val refused = radioRefusal()
        when {
            refused != null -> fail(created, PortError(refused))
            platform != Platform.ios -> connectFresh(created)
            adopting -> adopt(created)
            bike !in known -> fail(created, PortError(PortErrorKind.unknownBike))
            else -> connectFresh(created)
        }
        return created.id
    }

    override fun ready(link: Long, inventory: Boolean) {
        attached()
        val live = live(link) ?: return
        val database = databases[live.bike] ?: complete(live.profile)
        calls += "discover ${live.bike}"
        val result = gattReady(live.profile, database, inventory)
        if (result.outcome == GattResult.ready) {
            live.notifying = true
            live.maxWriteBytes = result.maxWriteBytes
        }
        emit { linkReady(live.id, result) }
    }

    override fun send(link: Long, frame: ControllerFrame): SendResult {
        attached()
        val live = live(link) ?: return SendResult.notConnected
        val limit = live.maxWriteBytes
        if (limit != null && frame.bytes.size > limit) return SendResult.tooLong
        if (writeBusy) {
            canSendOwed = true
            return SendResult.blocked
        }
        written += frame.bytes.copyOf()
        return SendResult.accepted
    }

    override fun cancel(link: Long) {
        attached()
        val current = this.link?.takeIf { it.id == link } ?: return
        current.notifying = false
        when (current.state) {
            State.ended,
            State.cancelling -> return
            State.connecting -> {
                calls += "cancel ${current.bike}"
                if (platform == Platform.ios) cancelledPending += current.bike
                end(current, null)
            }
            State.reconnecting -> {
                calls += "cancel reconnect ${current.bike}"
                keptReconnect = null
                end(current, null)
            }
            State.connected -> {
                calls += "disconnect ${current.bike}"
                if (platform == Platform.web) end(current, null) else current.state = State.cancelling
            }
        }
    }

    override fun release(link: Long): Boolean {
        attached()
        val current = this.link?.takeIf { it.id == link }
        if (platform != Platform.android || current?.state != State.cancelling) return false
        calls += "close ${current.bike}"
        end(current, null)
        return true
    }

    inner class Os {
        fun radio(state: RadioState) {
            osRadio = state
            if (platform == Platform.ios && prepared) centralRadio = state
            if (platform == Platform.web || prepared) emit { radioChanged(state) }
            val refused = radioRefusal()
            val current = link
            if (refused == null || current == null) return
            if (current.state == State.reconnecting) keptReconnect = null
            if (current.state in active || current.state == State.reconnecting) end(current, PortError(refused))
        }

        fun remembered(bike: String) {
            known += bike
        }

        fun restored(bike: String, state: Restored) {
            known += bike
            restored[bike] = state
        }

        fun discovered(bike: String, name: String?, rssi: Int?, services: List<String>, manufacturerData: ByteArray?) {
            known += bike
            if (platform == Platform.web) {
                if (!chooserOpen) return
                chooserOpen = false
                searching = false
            } else if (!searching) return
            val advertisement =
                Advertisement(
                    bike = bike,
                    name = name,
                    rssi = rssi,
                    services = services.toList(),
                    manufacturerData = manufacturerData?.copyOf()?.takeIf { searchInventory },
                    at = clock.read(),
                )
            emit { advertised(advertisement) }
            if (platform == Platform.web) emit { searchEnded(null) }
        }

        fun scanFailed(code: String) =
            endSearch(PortError(PortErrorKind.searchFailed, PlatformCode("AndroidScan", code)))

        fun chooserCancelled() = endSearch(PortError(PortErrorKind.cancelled))

        fun noActivation() = endSearch(PortError(PortErrorKind.noUserActivation))

        fun connected(bike: String) {
            val current = link
            when {
                current != null && current.bike == bike && current.state == State.connecting -> {
                    cancelledPending -= bike
                    current.state = State.connected
                    emit { linkUp(current.id, bike) }
                }
                platform == Platform.ios && keptReconnect == bike -> keptConnected = true
                platform == Platform.ios -> calls += "cancel unexpected $bike"
            }
        }

        fun connectFailed(bike: String, platformCode: PlatformCode? = null) {
            if (cancelledPending.remove(bike)) return
            val current = link?.takeIf { it.bike == bike && it.state == State.connecting } ?: return
            fail(current, PortError(PortErrorKind.connectionFailed, platformCode))
        }

        fun disconnected(bike: String, systemReconnecting: Boolean = false, platformCode: PlatformCode? = null) {
            val current = link?.takeIf { it.bike == bike } ?: return
            current.notifying = false
            when (current.state) {
                State.cancelling -> end(current, null)
                State.connecting -> fail(current, PortError(PortErrorKind.connectionFailed, platformCode))
                State.connected ->
                    if (platform == Platform.ios && systemReconnecting) {
                        current.state = State.reconnecting
                        keptReconnect = bike
                        keptConnected = false
                        emit { linkDown(current.id, PortError(PortErrorKind.linkLost, platformCode), true) }
                    } else end(current, PortError(PortErrorKind.linkLost, platformCode))
                State.reconnecting ->
                    if (!systemReconnecting) {
                        keptReconnect = null
                        end(current, PortError(PortErrorKind.linkLost, platformCode))
                    }
                State.ended -> Unit
            }
        }

        fun database(bike: String, services: List<GattService>, maxWriteBytes: Int?, notifyFails: Boolean = false) {
            databases[bike] = Database(services.toList(), maxWriteBytes, notifyFails)
        }

        fun servicesModified(bike: String) {
            val current = link?.takeIf { it.bike == bike && it.state == State.connected } ?: return
            emit { servicesInvalidated(current.id) }
        }

        fun notify(bytes: ByteArray) {
            val current = link?.takeIf { it.state == State.connected && it.notifying } ?: return
            val copy = bytes.copyOf()
            val at = clock.read()
            emit { notified(current.id, copy, at) }
        }

        fun notifyError(platformCode: PlatformCode? = null) {
            val current = link?.takeIf { it.state == State.connected && it.notifying } ?: return
            emit { notificationFailed(current.id, PortError(PortErrorKind.failed, platformCode)) }
        }

        fun writeBusy() {
            writeBusy = true
        }

        fun writeReady() {
            writeBusy = false
            val current = link?.takeIf { it.state == State.connected }
            if (!canSendOwed || current == null) return
            canSendOwed = false
            emit { canSend(current.id) }
        }

        fun writeError(platformCode: PlatformCode? = null) {
            val current = link?.takeIf { it.state == State.connected } ?: return
            emit { writeFailed(current.id, PortError(PortErrorKind.writeFailed, platformCode)) }
        }
    }

    private fun adopt(created: Link) {
        val bike = created.bike
        val others = restored.keys - bike
        others.forEach { calls += "cancel restored $it" }
        val restoredState = restored.remove(bike)
        restored.clear()
        val kept = keptReconnect == bike
        keptReconnect = null
        when {
            restoredState == Restored.connected || (kept && keptConnected) -> {
                created.state = State.connected
                emit { linkUp(created.id, bike) }
            }
            restoredState == Restored.connecting || kept -> created.state = State.connecting
            bike in known -> connectFresh(created)
            else -> fail(created, PortError(PortErrorKind.unknownBike))
        }
    }

    private fun connectFresh(created: Link) {
        calls += "connect ${created.bike}"
    }

    private fun endSearch(error: PortError) {
        if (!searching && !chooserOpen) return
        searching = false
        chooserOpen = false
        emit { searchEnded(error) }
    }

    private fun live(id: Long): Link? = link?.takeIf { it.id == id && it.state == State.connected }

    private fun fail(target: Link, error: PortError) {
        target.state = State.ended
        emit { linkFailed(target.id, error) }
    }

    private fun end(target: Link, error: PortError?) {
        target.state = State.ended
        target.notifying = false
        emit { linkDown(target.id, error, false) }
    }

    private fun radioRefusal(): PortErrorKind? =
        when (radio()) {
            RadioState.poweredOn -> null
            RadioState.poweredOff -> PortErrorKind.poweredOff
            RadioState.denied -> PortErrorKind.denied
            RadioState.unsupported -> PortErrorKind.unsupported
            RadioState.unknown,
            RadioState.resetting -> PortErrorKind.notReady
        }

    private fun gattReady(profile: TransportProfile, database: Database, inventory: Boolean): GattReady {
        val service = database.services.firstOrNull { it.uuid == profile.service }
        val write = service?.characteristics?.firstOrNull { it.uuid == profile.write }
        val notify = service?.characteristics?.firstOrNull { it.uuid == profile.notify }
        val outcome =
            when {
                service == null -> GattResult.serviceMissing
                write == null || write.properties and WRITE_WITHOUT_RESPONSE == 0 -> GattResult.characteristicMissing
                notify == null || notify.properties and (NOTIFY or INDICATE) == 0 -> GattResult.characteristicMissing
                database.notifyFails -> GattResult.notifyFailed
                else -> GattResult.ready
            }
        val listed =
            when {
                !inventory -> null
                platform == Platform.web -> listOfNotNull(service)
                else -> database.services
            }
        return GattReady(
            outcome = outcome,
            maxWriteBytes = database.maxWriteBytes.takeIf { outcome == GattResult.ready && platform != Platform.web },
            inventory = listed,
            error = PortError(PortErrorKind.failed).takeIf { outcome == GattResult.notifyFailed },
        )
    }

    private fun emit(event: BleEvents.() -> Unit) {
        val sink = attached()
        post { sink.event() }
    }

    private fun attached(): BleEvents = checkNotNull(events) { "attach comes before the first command" }

    companion object {
        const val WRITE_WITHOUT_RESPONSE: Int = 0x04
        const val WRITE: Int = 0x08
        const val NOTIFY: Int = 0x10
        const val INDICATE: Int = 0x20
        const val DEFAULT_MAX_WRITE_BYTES: Int = 182

        private val active = setOf(State.connecting, State.connected, State.cancelling)

        fun services(profile: TransportProfile): List<GattService> =
            listOf(
                GattService(
                    profile.service,
                    listOf(
                        GattCharacteristic(profile.write, WRITE_WITHOUT_RESPONSE or WRITE),
                        GattCharacteristic(profile.notify, NOTIFY),
                    ),
                )
            )

        private fun complete(profile: TransportProfile) = Database(services(profile), DEFAULT_MAX_WRITE_BYTES, false)
    }
}
