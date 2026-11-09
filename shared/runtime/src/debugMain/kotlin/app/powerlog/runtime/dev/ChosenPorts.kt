package app.powerlog.runtime.dev

import app.powerlog.application.port.BleCentral
import app.powerlog.application.port.BleEvents
import app.powerlog.application.port.PermissionGate
import app.powerlog.application.port.PermissionKind
import app.powerlog.application.port.PermissionStates
import app.powerlog.application.port.RadioState
import app.powerlog.application.port.Reply
import app.powerlog.application.port.SendResult
import app.powerlog.domain.core.controller.ControllerFrame
import app.powerlog.domain.core.controller.TransportProfile

internal class DemoChoice(private val bike: () -> Boolean) {
    val demo: Boolean by lazy { bike() }
}

internal class ChosenBleCentral(
    private val choice: DemoChoice,
    private val real: BleCentral,
    private val demo: () -> BleCentral,
) : BleCentral {
    private var events: BleEvents? = null
    private val central: BleCentral by lazy {
        (if (choice.demo) demo() else real).also { chosen -> events?.let(chosen::attach) }
    }

    val chosen: BleCentral
        get() = central

    override fun attach(bleEvents: BleEvents) {
        events = bleEvents
    }

    override fun prepare() = central.prepare()

    override fun radio(): RadioState = central.radio()

    override fun startSearch(profiles: List<TransportProfile>, inventory: Boolean) =
        central.startSearch(profiles, inventory)

    override fun stopSearch() = central.stopSearch()

    override fun connect(bike: String, profile: TransportProfile, adopt: Boolean): Long =
        central.connect(bike, profile, adopt)

    override fun ready(link: Long, inventory: Boolean) = central.ready(link, inventory)

    override fun send(link: Long, frame: ControllerFrame): SendResult = central.send(link, frame)

    override fun cancel(link: Long) = central.cancel(link)

    override fun release(link: Long): Boolean = central.release(link)
}

internal class ChosenPermissionGate(
    private val choice: DemoChoice,
    private val real: PermissionGate,
    private val granted: () -> PermissionGate,
) : PermissionGate {
    private val gate: PermissionGate by lazy { if (choice.demo) granted() else real }

    override fun status(reply: Reply<PermissionStates>) = gate.status(reply)

    override fun request(kinds: Set<PermissionKind>, reply: Reply<PermissionStates>) = gate.request(kinds, reply)
}
