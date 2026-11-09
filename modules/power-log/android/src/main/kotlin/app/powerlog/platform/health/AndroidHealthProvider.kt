package app.powerlog.platform.health

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import app.powerlog.application.port.AppleWorkoutSession
import app.powerlog.application.port.Clock
import app.powerlog.application.port.HealthAccess
import app.powerlog.application.port.HealthAvailability
import app.powerlog.application.port.HealthConnectWriter
import app.powerlog.application.port.HealthGrants
import app.powerlog.application.port.HealthOriginalPage
import app.powerlog.application.port.HealthProvider
import app.powerlog.application.port.HealthReader
import app.powerlog.application.port.HealthScope
import app.powerlog.application.port.PermissionState
import app.powerlog.application.port.PortError
import app.powerlog.application.port.PortErrorKind
import app.powerlog.application.port.Reply
import app.powerlog.domain.core.failure.PlatformCode
import app.powerlog.domain.core.health.HealthComponent
import app.powerlog.domain.core.health.HealthMetrics
import app.powerlog.domain.core.health.HealthProviderKind
import app.powerlog.domain.health.HealthConnectRecordPlan
import app.powerlog.domain.health.HealthPolicy
import app.powerlog.domain.health.HealthRoutes
import app.powerlog.domain.health.RouteReduction
import app.powerlog.platform.ui.AndroidUiContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AndroidHealthProvider
internal constructor(
    private val client: HealthConnectClientSeam,
    private val application: Context,
    private val preferences: SharedPreferences,
    private val ui: AndroidUiContext,
    private val clock: Clock,
    packageName: String,
    appLabel: String,
    context: CoroutineContext,
) : HealthProvider {
    constructor(
        context: Context,
        ui: AndroidUiContext,
        clock: Clock,
    ) : this(
        JetpackHealthConnect(context),
        context.applicationContext,
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE),
        ui,
        clock,
        context.packageName,
        context.applicationInfo.loadLabel(context.packageManager).toString(),
        Dispatchers.IO,
    )

    private val scope = CoroutineScope(SupervisorJob() + context)
    private val inFlight = AtomicBoolean(false)

    override val kind: HealthProviderKind = HealthProviderKind.healthConnect
    override val session: AppleWorkoutSession? = null

    override val access: HealthAccess =
        object : HealthAccess {
            override fun availability(): HealthAvailability =
                when (client.sdkStatus()) {
                    HealthConnectClient.SDK_AVAILABLE -> HealthAvailability.available
                    HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthAvailability.updateRequired
                    else -> HealthAvailability.unsupported
                }

            override fun grants(reply: Reply<HealthGrants>) = call(reply) { currentGrants() }

            override fun request(scope: HealthScope, reply: Reply<Unit>) {
                if (availability() != HealthAvailability.available) return failLater(reply, PortErrorKind.unsupported)
                HealthConnectPermissionRequest.start(application, ui, permissions(scope)) { shown ->
                    if (shown) reply.ok(Unit) else reply.failed(PortError(PortErrorKind.uiUnavailable))
                }
            }
        }

    override val reader: HealthReader =
        object : HealthReader {
            override fun read(
                metric: String,
                startUtcUs: Long,
                endUtcUs: Long,
                cursor: String?,
                reply: Reply<HealthOriginalPage>,
            ) {
                val type = HealthConnectRecords.readTypes[metric] ?: return failLater(reply, PortErrorKind.invalid)
                call(reply) {
                    val page =
                        client.read(
                            type,
                            HealthConnectRecords.exact(startUtcUs),
                            HealthConnectRecords.exact(endUtcUs),
                            cursor,
                        )
                    HealthOriginalPage(
                        page.records.flatMap { HealthConnectRecords.originals(metric, it) },
                        page.next?.takeIf { it.isNotEmpty() },
                    )
                }
            }
        }

    override val writer: HealthConnectWriter =
        object : HealthConnectWriter {
            override val packageName: String = packageName
            override val appLabel: String = appLabel

            override fun insert(records: List<HealthConnectRecordPlan>, reply: Reply<List<String>>) {
                if (records.any { !fits(it) }) return failLater(reply, PortErrorKind.invalid)
                val mapped =
                    try {
                        records.map { it to HealthConnectRecords.record(it) }
                    } catch (_: IllegalArgumentException) {
                        return failLater(reply, PortErrorKind.invalid)
                    }
                if (mapped.any { (plan, record) -> plan is HealthConnectRecordPlan.Session && record == null })
                    return failLater(reply, PortErrorKind.invalid)
                val native = mapped.mapNotNull { it.second }
                if (native.isEmpty()) return later { reply.ok(emptyList()) }
                call(reply) {
                    val ids = client.insert(native)
                    if (ids.size != native.size)
                        throw IllegalStateException("A receipt of ${ids.size} for ${native.size}")
                    ids
                }
            }

            private fun fits(record: HealthConnectRecordPlan): Boolean {
                if (record !is HealthConnectRecordPlan.Session) return true
                val plan = record.plan
                if (plan.endUtcUs == null) return false
                if (plan.laps.size > HealthConnectRecords.MAX_LAPS_OR_PAUSES) return false
                if (plan.pauses.size > HealthConnectRecords.MAX_LAPS_OR_PAUSES) return false
                val bytes =
                    RouteReduction.HealthConnect.baseBytes(
                        plan,
                        packageName,
                        appLabel,
                        Build.MANUFACTURER,
                        Build.MODEL,
                    ) + HealthRoutes.POINT_BYTES * record.route.size
                return bytes <= HealthPolicy.healthConnectRouteBudgetBytes
            }
        }

    private fun permissions(scope: HealthScope): Set<String> = buildSet {
        scope.write.mapNotNullTo(this, HealthConnectRecords::writePermission)
        scope.read.mapNotNullTo(this, HealthConnectRecords::readPermission)
        if (scope.route) add(HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE)
        if (scope.backgroundRead && client.backgroundReadAvailable())
            add(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)
    }

    private suspend fun currentGrants(): HealthGrants {
        val granted = client.granted()
        if (granted.isEmpty()) preferences.edit().remove(FIRST_GRANT).apply()
        else if (!preferences.contains(FIRST_GRANT))
            preferences.edit().putLong(FIRST_GRANT, Math.floorDiv(clock.utcUs(), 1_000L)).apply()
        fun state(permission: String?) =
            if (permission != null && permission in granted) PermissionState.granted else PermissionState.denied
        return HealthGrants(
            write =
                HealthComponent.entries
                    .mapNotNull { component ->
                        HealthConnectRecords.writePermission(component)?.let { component to it }
                    }
                    .associate { (component, permission) -> component to state(permission) },
            read =
                HealthMetrics.readback(HealthProviderKind.healthConnect).associate {
                    it.wire to state(HealthConnectRecords.readPermission(it.wire))
                },
            route = state(HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE),
            backgroundRead =
                if (client.backgroundReadAvailable()) state(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)
                else null,
            readableFromUtcUs =
                if (preferences.contains(FIRST_GRANT))
                    (preferences.getLong(FIRST_GRANT, 0) - READ_HISTORY_MILLIS) * 1_000L
                else null,
        )
    }

    private fun later(answer: () -> Unit) {
        scope.launch { answer() }
    }

    private fun failLater(reply: Reply<*>, kind: PortErrorKind) = later { reply.failed(PortError(kind)) }

    private fun <T> call(reply: Reply<T>, body: suspend () -> T) {
        if (client.sdkStatus() != HealthConnectClient.SDK_AVAILABLE) return failLater(reply, PortErrorKind.unsupported)
        if (!inFlight.compareAndSet(false, true)) return failLater(reply, PortErrorKind.busy)
        scope.launch {
            val result =
                try {
                    Result.success(body())
                } catch (error: CancellationException) {
                    inFlight.set(false)
                    throw error
                } catch (error: Throwable) {
                    Result.failure(error)
                }
            inFlight.set(false)
            result.fold(reply::ok) { reply.failed(portError(it)) }
        }
    }

    companion object {
        private const val PREFERENCES = "health-access"
        private const val FIRST_GRANT = "observedGrantMillis"
        private const val READ_HISTORY_MILLIS = 30L * 86_400_000L

        fun portError(error: Throwable): PortError {
            val platform = PlatformCode("HealthConnect", code(error))
            val kind =
                when {
                    rateLimited(error) -> PortErrorKind.rateLimited
                    error is SecurityException -> PortErrorKind.denied
                    error is IllegalArgumentException -> PortErrorKind.invalid
                    else -> PortErrorKind.failed
                }
            return PortError(kind, platform)
        }

        private fun code(error: Throwable): String =
            if (Build.VERSION.SDK_INT >= 34 && error is android.health.connect.HealthConnectException)
                error.errorCode.toString()
            else error::class.java.simpleName

        private fun rateLimited(error: Throwable): Boolean {
            if (
                Build.VERSION.SDK_INT >= 34 &&
                    error is android.health.connect.HealthConnectException &&
                    error.errorCode == android.health.connect.HealthConnectException.ERROR_RATE_LIMIT_EXCEEDED
            )
                return true
            return error.message?.contains("quota", true) == true ||
                error.message?.contains("rate limit", true) == true ||
                error.cause?.let(::rateLimited) == true
        }
    }
}
