package app.powerlog.application.exports

import app.powerlog.domain.core.ids.RideId

internal interface ExportSessions {
    fun exporting(): Set<RideId>
}
