package app.powerlog.bridge

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import kotlin.reflect.KClass

internal class HealthPage(val records: List<Record>, next: String?, val scopeComplete: Boolean = true) {
    val next = next?.takeIf { it.isNotEmpty() }
}

internal data class HealthReceipt(val providerIds: List<String>)

internal interface HealthConnectAccess {
    val available: Boolean
    val backgroundReadSupported: Boolean
    val historyReadSupported: Boolean

    suspend fun granted(): Set<String>

    suspend fun insert(records: List<Record>): HealthReceipt

    suspend fun update(records: List<Record>)

    suspend fun read(
        type: KClass<out Record>,
        start: Instant,
        end: Instant,
        page: String?,
        own: Boolean,
    ): HealthPage

    suspend fun readById(type: KClass<out Record>, id: String): Record?
}

internal class AndroidHealthConnectAccess(private val context: Context) : HealthConnectAccess {
    override val available
        get() = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    override val backgroundReadSupported
        get() =
            available &&
                client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) ==
                    HealthConnectFeatures.FEATURE_STATUS_AVAILABLE

    override val historyReadSupported
        get() =
            available &&
                client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY) ==
                    HealthConnectFeatures.FEATURE_STATUS_AVAILABLE

    private val client
        get() = HealthConnectClient.getOrCreate(context)

    override suspend fun granted(): Set<String> {
        val grants = if (available) client.permissionController.getGrantedPermissions() else emptySet()
        val preferences = context.getSharedPreferences("health-access", Context.MODE_PRIVATE)
        if (grants.isEmpty()) preferences.edit().remove("observedGrantMillis").apply()
        else if (!preferences.contains("observedGrantMillis"))
            preferences.edit().putLong("observedGrantMillis", System.currentTimeMillis()).apply()
        return grants
    }

    override suspend fun insert(records: List<Record>) = HealthReceipt(client.insertRecords(records).recordIdsList)

    override suspend fun update(records: List<Record>) {
        client.updateRecords(records)
    }

    override suspend fun read(
        type: KClass<out Record>,
        start: Instant,
        end: Instant,
        page: String?,
        own: Boolean,
    ): HealthPage {
        val result =
            client.readRecords(
                ReadRecordsRequest(
                    type,
                    TimeRangeFilter.between(start, end),
                    dataOriginFilter = if (own) setOf(DataOrigin(context.packageName)) else emptySet(),
                    pageSize = 128,
                    pageToken = page,
                )
            )
        val grants = granted()
        val observed =
            context
                .getSharedPreferences("health-access", Context.MODE_PRIVATE)
                .getLong("observedGrantMillis", Long.MAX_VALUE)
        // Without the history permission, reads return only data from 30 days before the first grant:
        // https://developer.android.com/health-and-fitness/health-connect/read-data
        val complete =
            androidx.health.connect.client.permission.HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY in grants ||
                observed != Long.MAX_VALUE && !start.isBefore(Instant.ofEpochMilli(observed).minusSeconds(30L * 86400))
        return HealthPage(result.records, result.pageToken, complete)
    }

    override suspend fun readById(type: KClass<out Record>, id: String): Record? = client.readRecord(type, id).record
}
