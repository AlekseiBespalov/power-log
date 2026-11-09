package app.powerlog.runtime.dev

import app.powerlog.domain.core.controller.ControllerDecoders
import app.powerlog.domain.core.health.HealthMetrics

public object SuiteTables {
    public val decoders: ControllerDecoders = ControllerDecoders
    public val healthMetrics: HealthMetrics = HealthMetrics
}
