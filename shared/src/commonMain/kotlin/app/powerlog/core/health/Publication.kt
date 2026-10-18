package app.powerlog.core.health

object Publication {
    /** Provider identity and content version are fixed for this append-once projection. */
    @Throws(IllegalArgumentException::class, app.powerlog.core.protocol.ProtocolException::class)
    fun plan(plan: RecordPlan): PublicationRecord {
        val content = HealthCodec.canonicalContent(plan.content)
        HealthValidation.validate(plan.copy(content = content))
        return PublicationRecord(
            plan.syncId,
            plan.component,
            plan.operation,
            1,
            content,
            HealthCodec.contentDigest(content),
            plan.sourceRevision,
            emptyList(),
            if (plan.operation == RecordOperation.delete) RecordState.withdrawn else RecordState.notSubmitted,
            null,
            null,
            0,
            plan.routeIntent,
        )
    }

    @Throws(
        IllegalArgumentException::class,
        ArithmeticException::class,
        app.powerlog.core.protocol.ProtocolException::class,
    )
    fun routeProjection(
        intent: RouteIntent,
        granted: Boolean,
        existingRoute: List<RoutePoint>?,
        requestedRoute: List<RoutePoint>,
        gpsFrozen: Boolean,
    ): RoutePermissionProjection {
        if (!gpsFrozen)
            return RoutePermissionProjection(
                RouteIntent.NotRequested,
                emptyList(),
                ComponentState.notRequested,
                null,
                false,
            )
        return when (intent) {
            RouteIntent.NotRequested ->
                RoutePermissionProjection(intent, emptyList(), ComponentState.notRequested, null, false)
            is RouteIntent.Replace ->
                if (granted) RoutePermissionProjection(intent, requestedRoute, ComponentState.pending, null, true)
                else
                    RoutePermissionProjection(
                        RouteIntent.Preserve,
                        emptyList(),
                        ComponentState.pending,
                        HealthReason.permission,
                        true,
                    )
            RouteIntent.Preserve ->
                if (!granted) RoutePermissionProjection(intent, emptyList(), ComponentState.pending, null, true)
                else if (existingRoute != null)
                    RoutePermissionProjection(intent, existingRoute, ComponentState.saved, null, true)
                else
                    RoutePermissionProjection(
                        intent,
                        emptyList(),
                        ComponentState.pending,
                        HealthReason.readbackUnavailable,
                        false,
                    )
            RouteIntent.Remove ->
                if (granted) RoutePermissionProjection(intent, emptyList(), ComponentState.pending, null, true)
                else
                    RoutePermissionProjection(
                        intent,
                        emptyList(),
                        ComponentState.pending,
                        HealthReason.permission,
                        false,
                    )
        }
    }
}

data class RoutePermissionProjection(
    val intent: RouteIntent,
    val route: List<RoutePoint>,
    val state: ComponentState,
    val reason: HealthReason?,
    val invoke: Boolean,
)
