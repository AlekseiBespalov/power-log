package app.powerlog.runtime.dev

import app.powerlog.application.port.Clock
import app.powerlog.runtime.compose.DevTools

public object DevToolsFactory {
    public val marker: String = "power-log-dev-tools"

    public fun create(switches: () -> DevSwitches, clock: Clock): DevTools = PhoneDevTools(switches, clock)

    public fun crash() {
        throw IllegalStateException("crashAfterUs")
    }
}
