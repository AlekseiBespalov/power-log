package app.powerlog.core.presentation

enum class StartSurface {
    rider,
    ownerCommand,
    nativeOwner,
}

data class RideStartContext(
    val surface: StartSurface = StartSurface.rider,
    val hasSession: Boolean = false,
    val endingOwner: Boolean = false,
    val recoveredForeignOwner: Boolean = false,
    val unresolvedOwner: Boolean = false,
    val finishing: Boolean = false,
    val busy: Boolean = false,
    val recovering: Boolean = false,
    val deletionProbeInFlight: Boolean = false,
)

object RideStarts {
    fun rejection(phase: String?, context: RideStartContext): String? {
        val failure =
            when {
                context.unresolvedOwner || context.recovering || context.recoveredForeignOwner ->
                    RideFailure.ownerUnresolved
                context.hasSession || context.endingOwner || context.finishing || context.deletionProbeInFlight ->
                    RideFailure.rideFinishing
                context.busy -> RideFailure.actionPending
                context.surface == StartSurface.nativeOwner ||
                    phase == null ||
                    phase in setOf("idle", "ready") ||
                    context.surface == StartSurface.ownerCommand && phase == "preparing" ||
                    context.surface == StartSurface.rider && phase in setOf("completed", "failed") -> null
                else -> RideFailure.rideActive
            }
        return failure?.name
    }
}
