package app.powerlog.bridge

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import app.powerlog.core.health.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class HealthExportTest {
    private val start = Instant.parse("2026-10-02T00:00:00Z")

    private fun ride(
        gps: Boolean = false,
        saveToHealth: Boolean = true,
        telemetry: Boolean = true,
        gpsDistance: Boolean = false,
        body: (RideStore, String, FakeHealthConnectAccess, HealthExport) -> Unit,
    ) {
        val context = RuntimeEnvironment.getApplication()
        val store = RideStore(context, "health-${java.util.UUID.randomUUID()}.sqlite")
        try {
            val id =
                store.create(
                    RideOptions(false, saveToHealth = saveToHealth, recordGPS = gps),
                    RecordingClockReading(start.toString(), 0),
                )
            if (telemetry)
                for (i in 0..16) store.insert(
                    id,
                    i / 8.0,
                    start.plusMillis(i * 125L).toString(),
                    "telemetry",
                    true,
                    0,
                    mapOf(
                        "humanPowerW" to 150.0,
                        "cadenceRpm" to 80.0,
                        "motorInputPowerW" to 2500.0,
                        "controllerSpeedMps" to 10.0,
                    ),
                )
            if (gps) {
                val distance = RideDistance(store)
                for (i in 0..2) store.transaction {
                    val values =
                        mapOf(
                            "latitude" to 0.0,
                            "longitude" to i * if (gpsDistance) 0.0001 else 0.001,
                            "horizontalAccuracyM" to 5.0,
                            "speedMps" to 3.0,
                        )
                    val row =
                        store.insert(
                            id,
                            i.toDouble(),
                            start.plusSeconds(i.toLong()).toString(),
                            "location",
                            true,
                            0,
                            values,
                        )
                    if (gpsDistance) distance.append(id, row, i.toDouble(), values, true, 0, "", "", true)
                }
            }
            store.seal(id, RideTiming(2.0, 2.0, start.plusSeconds(2).toString()))
            val access = FakeHealthConnectAccess()
            val exporter = HealthExport(context, store, access, mode = { ExecutionMode.foreground })
            body(store, id, access, exporter)
        } finally {
            store.close()
        }
    }

    private fun settle(exporter: HealthExport, id: String) {
        repeat(12) { exporter.save(id) }
    }

    private fun externalHeartRate() =
        HeartRateRecord(
            start,
            null,
            start.plusSeconds(2),
            null,
            listOf(HeartRateRecord.Sample(start.plusMillis(125), 123)),
            HealthMetadata.received(
                "generated-heart",
                "external-heart",
                1,
                null,
                "app.generated.sensor",
                start,
                androidx.health.connect.client.records.metadata.Metadata.RECORDING_METHOD_ACTIVELY_RECORDED,
            ),
        )

    private fun readback(store: RideStore, id: String) =
        (store.metadata(id)["componentResults"] as List<*>)
            .map { it as Map<*, *> }
            .single { it["component"] == "readback" }

    @Test
    fun completedRidePublishesOnceWithFixedIdsAndKeepsOriginalDetail() = ride { store, id, access, exporter ->
        settle(exporter, id)
        val records = access.records
        assertEquals(1, records.filterIsInstance<ExerciseSessionRecord>().size)
        assertTrue(records.filterIsInstance<PowerRecord>().isNotEmpty())
        assertTrue(records.all { it.metadata.clientRecordVersion == 1L })
        assertEquals(17, store.page(id).count { it.kind == "telemetry" })
        val calls = access.batches.size
        settle(exporter, id)
        assertEquals(calls, access.batches.size)
        assertEquals("saved", store.healthJob(id)!!.status)
    }

    @Test
    fun lostReceiptIsUncertainAndNeverRepeatedEvenAfterExplicitRefresh() = ride { store, id, access, exporter ->
        access.afterWrite = { error("lost receipt") }
        exporter.save(id)
        assertEquals("uncertain", store.healthJob(id)!!.status)
        val calls = access.batches.size
        access.afterWrite = {}
        exporter.repair(id)
        settle(exporter, id)
        assertEquals(calls, access.batches.size)
        assertTrue(app.powerlog.core.completion.RideCompletion.exportReady(store.completion(id), id))
    }

    @Test
    fun persistedSubmittedCursorBecomesUncertainWithoutProviderCall() = ride { store, id, access, exporter ->
        store.putHealthJob(store.ensureHealthJob(id).copy(status = "submitted"))
        exporter.save(id)
        assertEquals("uncertain", store.healthJob(id)!!.status)
        assertTrue(access.batches.isEmpty())
    }

    @Test
    fun confirmedPermissionFailureCanBeRetriedWithoutChangingIdentity() = ride { store, id, access, exporter ->
        val permission = HealthPermission.getWritePermission(ExerciseSessionRecord::class)
        access.grants -= permission
        exporter.save(id)
        assertEquals("failed", store.healthJob(id)!!.status)
        access.grants += permission
        exporter.repair(id)
        settle(exporter, id)
        assertEquals("saved", store.healthJob(id)!!.status)
        assertTrue(access.records.all { it.metadata.clientRecordVersion == 1L })
    }

    @Test
    fun deniedOptionalPowerStillSavesTheWorkoutAndReportsTheOmission() = ride { store, id, access, exporter ->
        access.grants -= HealthPermission.getWritePermission(PowerRecord::class)
        settle(exporter, id)
        assertEquals("saved", store.healthJob(id)!!.status)
        assertEquals(1, access.records.filterIsInstance<ExerciseSessionRecord>().size)
        assertTrue(access.records.filterIsInstance<PowerRecord>().isEmpty())
        assertTrue(Component.riderPower.wire in store.healthJob(id)!!.omittedComponents)
    }

    @Test
    fun lateControllerReadingDoesNotRevisePublishedHealth() = ride { store, id, access, exporter ->
        settle(exporter, id)
        val initial = access.records
        store.insert(
            id,
            0.25,
            start.plusMillis(250).toString(),
            "telemetry",
            true,
            0,
            mapOf("humanPowerW" to 900.0, "cadenceRpm" to 90.0),
        )
        exporter.repair(id)
        settle(exporter, id)
        assertEquals(initial, access.records)
    }

    @Test
    fun refreshWithSavingOffImportsMeasurementsWithoutPublicationWork() =
        ride(saveToHealth = false) { store, id, access, exporter ->
            access.grants = HealthReadback.types.map { HealthPermission.getReadPermission(it) }.toSet()
            access.seed(externalHeartRate())
            val revision = store.revision(id)
            exporter.refresh(id)
            settle(exporter, id)
            assertEquals(123.0, store.page(id).single { it.kind == "health" }.values["heartRateBpm"])
            assertTrue(store.revision(id) > revision)
            assertEquals("notRequested", store.metadata(id)["healthKitState"])
            assertNull(store.healthJob(id))
            assertTrue(access.batches.isEmpty())
            assertFalse(exporter.writesPending(id))
            assertNull(exporter.nextRefreshUs(id))
            val reads = (store.metadata(id)["componentResults"] as List<*>).single() as Map<*, *>
            assertEquals("saved", reads["state"])
        }

    @Test
    fun readOnlyRefreshDenialAndUnavailableProviderSettleWithoutWrites() {
        for (available in listOf(true, false)) ride(saveToHealth = false) { store, id, access, exporter ->
            access.available = available
            access.grants = emptySet()
            exporter.refresh(id)
            settle(exporter, id)
            assertNull(store.healthJob(id))
            assertTrue(access.batches.isEmpty())
            assertFalse(exporter.writesPending(id))
            assertNull(exporter.nextRefreshUs(id))
            val reads = (store.metadata(id)["componentResults"] as List<*>).single() as Map<*, *>
            assertEquals("unavailable", reads["state"])
            assertEquals(if (available) "permission" else "readbackUnavailable", reads["reason"])
        }
    }

    @Test
    fun healthConnectHistoryAccessIsNeitherRequestedNorReported() = ride { _, _, _, exporter ->
        val history = HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY
        for (gps in listOf(false, true)) assertFalse(history in exporter.permissions(gps))
        val status = exporter.status()
        assertFalse(history in status["readAuthorization"] as Map<*, *>)
        assertFalse("historyReadSupported" in status)
    }

    @Test
    fun ridesBeforeTheDefaultReadWindowAreNotReadAndShowNoReadbackError() {
        for (saveToHealth in listOf(false, true)) ride(saveToHealth = saveToHealth) { store, id, access, exporter ->
            access.seed(externalHeartRate())
            var reads = 0
            access.beforeRead = { reads++ }
            access.readableFrom = start.plusSeconds(1)
            exporter.refresh(id)
            settle(exporter, id)
            assertEquals(0, reads)
            assertTrue(store.page(id).none { it.kind == "health" })
            assertEquals("notRequested", readback(store, id)["state"])
            assertNull(readback(store, id)["reason"])
            assertEquals(if (saveToHealth) "saved" else "notRequested", store.metadata(id)["healthKitState"])
            assertFalse(exporter.writesPending(id))
            assertNull(exporter.nextRefreshUs(id))
            access.readableFrom = start
            exporter.refresh(id)
            settle(exporter, id)
            assertTrue(reads > 0)
            assertEquals(123.0, store.page(id).single { it.kind == "health" }.values["heartRateBpm"])
            assertEquals("saved", readback(store, id)["state"])
        }
    }

    @Test
    fun readOnlyRefreshWithoutBackgroundPermissionWaitsForForeground() =
        ride(saveToHealth = false) { store, id, access, foreground ->
            access.grants -= HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND
            val background =
                HealthExport(
                    RuntimeEnvironment.getApplication(),
                    store,
                    access,
                    mode = { ExecutionMode.background },
                )
            background.refresh(id)
            background.save(id)
            assertTrue(store.healthReadsDeferred(id))
            assertNull(store.healthJob(id))
            assertNull(background.nextRefreshUs(id))
            settle(foreground, id)
            assertFalse(store.healthReadsDeferred(id))
            assertNull(foreground.nextRefreshUs(id))
            assertTrue(access.batches.isEmpty())
        }

    @Test
    fun refreshHealthEnabledRideWithoutAJobDoesNotCreatePublicationWork() = ride { store, id, access, exporter ->
        store.writableDatabase.delete("publication_jobs", "ride=?", arrayOf(id))
        exporter.refresh(id)
        settle(exporter, id)
        assertNull(store.healthJob(id))
        assertTrue(access.batches.isEmpty())
        assertFalse(exporter.writesPending(id))
        assertNull(exporter.nextRefreshUs(id))
    }

    @Test
    fun refreshPreservesExistingClockFailureWithoutSchedulingInvalidReads() = ride { store, id, access, exporter ->
        store.writableDatabase.delete("publication_jobs", "ride=?", arrayOf(id))
        store.update(id, "completed", RideTiming(0.0, 0.0, start.toString()), mapOf("endedAt" to start.toString()))
        store.finishCollection(id)
        exporter.refresh(id)
        settle(exporter, id)
        assertNull(store.healthJob(id))
        assertTrue(access.batches.isEmpty())
        assertEquals("failed", store.metadata(id)["healthKitState"])
        assertEquals("clock", store.metadata(id)["healthReason"])
        assertNull(exporter.nextRefreshUs(id))
    }

    @Test
    fun refreshDoesNotRetryAKnownWriteFailureUntilExplicitRepair() = ride { store, id, access, exporter ->
        val permission = HealthPermission.getWritePermission(ExerciseSessionRecord::class)
        access.grants -= permission
        settle(exporter, id)
        val writes = access.batches.size
        access.grants += permission
        exporter.refresh(id)
        settle(exporter, id)
        assertEquals("failed", store.healthJob(id)!!.status)
        assertEquals(writes, access.batches.size)
        exporter.repair(id)
        settle(exporter, id)
        assertEquals("saved", store.healthJob(id)!!.status)
    }

    @Test
    fun discardAfterPlanningBeforeSubmissionPreventsNewProviderWrites() =
        ride(telemetry = false) { store, id, access, _ ->
            var discarded = false
            val exporter =
                HealthExport(
                    RuntimeEnvironment.getApplication(),
                    store,
                    access,
                    mode = {
                        if (!discarded) {
                            discarded = true
                            store.remove(id, discard = true)
                        }
                        ExecutionMode.foreground
                    },
                )
            assertEquals("notRequested", exporter.save(id).state)
            assertTrue(discarded)
            assertFalse(store.exists(id))
            assertNull(store.healthJob(id))
            assertTrue(access.batches.isEmpty())
            assertTrue(access.records.isEmpty())
            assertFalse(exporter.writesPending(id))
            assertNull(exporter.nextRefreshUs(id))
        }

    @Test
    fun discardDuringSubmittedWriteLeavesProviderRecordsAndRetiresAllLocalWork() = ride { store, id, access, exporter ->
        access.beforeWrite = { store.remove(id, discard = true) }
        assertEquals("notRequested", exporter.save(id).state)
        assertFalse(store.exists(id))
        assertNull(store.healthJob(id))
        assertTrue(access.records.isNotEmpty())
        assertTrue(access.records.none { it is ExerciseSessionRecord })
        val records = access.records
        settle(exporter, id)
        exporter.refresh(id)
        exporter.repair(id)
        assertEquals(records, access.records)
        assertEquals(1, access.batches.size)
        assertFalse(exporter.writesPending(id))
        assertNull(exporter.nextRefreshUs(id))
    }

    @Test
    fun discardDuringReadCannotRecreateLocalMeasurementsOrProgress() =
        ride(saveToHealth = false) { store, id, access, exporter ->
            access.beforeRead = { store.remove(id, discard = true) }
            assertEquals("notRequested", exporter.save(id).state)
            assertFalse(store.exists(id))
            assertNull(store.healthJob(id))
            assertNull(exporter.nextRefreshUs(id))
            assertTrue(access.batches.isEmpty())
        }

    @Test
    fun gpsWorkoutRetainsNativeRouteAndNeverExportsMotorPowerAsRiderPower() =
        ride(gps = true, gpsDistance = true) { _, id, access, exporter ->
            settle(exporter, id)
            val session = access.records.filterIsInstance<ExerciseSessionRecord>().single()
            assertTrue(session.exerciseRouteResult is ExerciseRouteResult.Data)
            assertTrue(
                access.records.filterIsInstance<PowerRecord>().flatMap { it.samples }.all { it.power.inWatts == 150.0 }
            )
        }
}
