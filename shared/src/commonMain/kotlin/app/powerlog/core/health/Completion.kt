package app.powerlog.core.health

enum class HealthOutcome {
    pending,
    saved,
    failed,
    notSaved,
    unavailable,
    unknown,
    discarded,
    notRequested,
}

data class OwnerHealth(val outcome: HealthOutcome, val reason: HealthReason?)
