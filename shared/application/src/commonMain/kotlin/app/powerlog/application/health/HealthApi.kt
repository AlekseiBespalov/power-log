package app.powerlog.application.health

import app.powerlog.application.api.Outcome
import app.powerlog.application.api.RideInput

public interface HealthApi {
    public suspend fun refresh(input: RideInput): Outcome<Unit>

    public suspend fun retrySave(input: RideInput): Outcome<Unit>
}

public enum class HealthWorkResult {
    success,
    retry,
    failure,
}
