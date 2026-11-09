package app.powerlog.kit

import app.powerlog.domain.connection.ConnectionDisplay
import app.powerlog.domain.connection.ConnectionState

internal object ConnectionTexts {
    fun connection(state: ConnectionState, display: ConnectionDisplay): String =
        when {
            display != ConnectionDisplay.unavailable -> "Connected"
            state == ConnectionState.reconnecting -> "Reconnecting…"
            state == ConnectionState.connected -> "Waiting for data"
            state == ConnectionState.connecting -> "Connecting…"
            state == ConnectionState.searching -> "Searching…"
            else -> "Not connected"
        }

    fun controllerFault(faultCode: Int): String = "Your controller reports fault $faultCode. Check the bike's display."

    fun controllerSpeedNote(controllerSpeed: Boolean?): String? =
        if (controllerSpeed == false) "Controller speed needs CYC X6 or X12 firmware with protocol 5.3." else null
}
