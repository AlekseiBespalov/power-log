package app.powerlog.domain.health

public sealed interface RouteReduction {
    public fun append(points: List<RoutePoint>)

    public fun result(): List<RoutePoint>

    public class Apple(private val capacity: Int) : RouteReduction {
        private val kept = mutableListOf<IndexedRoutePoint>()
        private var accepted = 0L

        init {
            require(capacity >= 4) { "a route keeps at least four points: $capacity" }
        }

        public var stride: Int = 1
            private set

        public var latest: IndexedRoutePoint? = null
            private set

        public fun offer(point: RoutePoint): Long? {
            if (!accurate(point)) return null
            val previous = latest
            if (previous != null && point.utcUs <= previous.point.utcUs) return null
            val indexed = IndexedRoutePoint(accepted++, point)
            latest = indexed
            if (indexed.index % stride == 0L) kept += indexed
            if (kept.size + (if (kept.lastOrNull() === indexed) 0 else 1) > capacity) {
                kept.retainAll { it.index % (2L * stride) == 0L }
                stride *= 2
            }
            return indexed.index
        }

        override fun append(points: List<RoutePoint>) {
            for (point in points) offer(point)
        }

        override fun result(): List<RoutePoint> {
            val last = latest ?: return emptyList()
            val points = kept.map { it.point }
            return if (kept.lastOrNull()?.index == last.index) points else points + last.point
        }
    }

    public class HealthConnect(private val baseBytes: Long, private val budgetBytes: Long) : RouteReduction {
        private val eligible = mutableListOf<RoutePoint>()
        private var lastMillisecond: Long? = null

        override fun append(points: List<RoutePoint>) {
            for (point in points) {
                if (!accurate(point) || point.latitude !in -90.0..90.0 || point.longitude !in -180.0..180.0) continue
                val millisecond = point.utcUs.floorDiv(1_000L)
                val previous = lastMillisecond
                if (previous != null && millisecond <= previous) continue
                lastMillisecond = millisecond
                eligible += point
            }
        }

        override fun result(): List<RoutePoint> =
            HealthRoutes.indices(eligible.size, baseBytes, budgetBytes)?.map { eligible[it] } ?: emptyList()

        public companion object {
            public fun baseBytes(
                workout: HealthPlan.Workout,
                packageName: String,
                appLabel: String,
                manufacturer: String? = null,
                model: String? = null,
            ): Long =
                HealthRoutes.baseBytes(
                    RouteEncoding(
                        providerUUID = null,
                        packageName = packageName,
                        appName = appLabel,
                        clientRecordId = workout.syncId,
                        manufacturer = manufacturer,
                        model = model,
                        notes = null,
                        title = HealthPolicy.healthConnectSessionTitle,
                        plannedExerciseSessionId = null,
                        laps = workout.laps.size,
                        pauses = workout.pauses.size,
                    )
                )
        }
    }
}

private fun accurate(point: RoutePoint): Boolean =
    point.horizontalAccuracyM >= 0 && point.horizontalAccuracyM <= HealthPolicy.routeAccuracyM
