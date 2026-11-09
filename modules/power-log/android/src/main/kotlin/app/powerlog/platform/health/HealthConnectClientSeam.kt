package app.powerlog.platform.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import kotlin.reflect.KClass

internal class HealthConnectPage(val records: List<Record>, val next: String?)

internal interface HealthConnectClientSeam {
    fun sdkStatus(): Int

    fun backgroundReadAvailable(): Boolean

    suspend fun granted(): Set<String>

    suspend fun insert(records: List<Record>): List<String>

    suspend fun read(type: KClass<out Record>, start: Instant, end: Instant, pageToken: String?): HealthConnectPage
}

internal class JetpackHealthConnect(private val context: Context) : HealthConnectClientSeam {
    private val client
        get() = HealthConnectClient.getOrCreate(context)

    override fun sdkStatus(): Int = HealthConnectClient.getSdkStatus(context)

    override fun backgroundReadAvailable(): Boolean =
        client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) ==
            HealthConnectFeatures.FEATURE_STATUS_AVAILABLE

    override suspend fun granted(): Set<String> = client.permissionController.getGrantedPermissions()

    override suspend fun insert(records: List<Record>): List<String> = client.insertRecords(records).recordIdsList

    override suspend fun read(
        type: KClass<out Record>,
        start: Instant,
        end: Instant,
        pageToken: String?,
    ): HealthConnectPage {
        val response =
            client.readRecords(
                ReadRecordsRequest(
                    type,
                    TimeRangeFilter.between(start, end),
                    pageSize = PAGE_SIZE,
                    pageToken = pageToken,
                )
            )
        return HealthConnectPage(response.records, response.pageToken)
    }

    companion object {
        const val PAGE_SIZE = 128
    }
}
