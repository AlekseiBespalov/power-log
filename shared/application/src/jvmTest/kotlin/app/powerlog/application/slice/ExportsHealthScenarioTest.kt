package app.powerlog.application.slice

import app.powerlog.application.api.LaunchResult
import app.powerlog.application.api.Outcome
import app.powerlog.application.exports.ExportCloseInput
import app.powerlog.application.exports.ExportColumn
import app.powerlog.application.exports.ExportKind
import app.powerlog.application.exports.ExportOpenInput
import app.powerlog.application.exports.ExportPage
import app.powerlog.application.exports.ExportPageInput
import app.powerlog.application.exports.ExportSessionView
import app.powerlog.application.exports.ExportSource
import app.powerlog.application.store.Mutation
import app.powerlog.application.store.WriteBatch
import app.powerlog.domain.core.controller.ControllerCapabilities
import app.powerlog.domain.core.health.HealthProviderKind
import app.powerlog.domain.core.ids.EventId
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.measurement.ControllerSession
import app.powerlog.domain.core.measurement.HealthMetric
import app.powerlog.domain.core.measurement.HealthRepresentation
import app.powerlog.domain.core.measurement.HealthSample
import app.powerlog.domain.core.measurement.Producer
import app.powerlog.domain.core.measurement.TelemetryRows
import app.powerlog.domain.core.measurement.TelemetrySample
import app.powerlog.domain.core.metric.MetricCatalog
import app.powerlog.domain.core.metric.MetricId
import app.powerlog.domain.core.ride.Device
import app.powerlog.domain.core.ride.FitPowerSource
import app.powerlog.domain.core.ride.RideEvent
import app.powerlog.domain.core.ride.RideEventKind
import app.powerlog.domain.core.ride.RidePhase
import app.powerlog.domain.core.time.RideTiming
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ExportsHealthScenarioTest {
    private val powerAndCadence = setOf(MetricId.humanPowerW, MetricId.cadenceRpm)

    private fun power(watts: Double): (MetricId, Long) -> Double? = { metric, _ ->
        if (metric == MetricId.humanPowerW) watts else 80.0
    }

    private class Phone(val device: Device, val provider: HealthProviderKind) {
        val heartRate = if (provider == HealthProviderKind.appleHealth) "heartRateBpm" else "heartRate"
        val activeEnergy = if (provider == HealthProviderKind.appleHealth) "activeEnergyKcal" else "activeEnergy"
    }

    private val iphone = Phone(Device.iphone, HealthProviderKind.appleHealth)
    private val android = Phone(Device.androidPhone, HealthProviderKind.healthConnect)

    private fun sample(
        rideId: RideId,
        key: String,
        metric: String?,
        value: Double?,
        startS: Double,
        endS: Double = startS,
        representation: HealthRepresentation = HealthRepresentation.rawQuantity,
        origin: String = "app.generated",
        producer: Producer = Producer.phone,
    ): HealthSample {
        val startUs = (startS * 1e6).roundToLong()
        val endUs = (endS * 1e6).roundToLong()
        return HealthSample(
            rideId,
            producer,
            key,
            origin,
            null,
            null,
            metric?.let(::HealthMetric),
            representation,
            value,
            null,
            Scenario.START_UTC_US + endUs,
            endUs,
            Scenario.START_UTC_US + startUs,
            Scenario.START_UTC_US + endUs,
            if (endUs in 0 until 10_000_000) 1 else null,
        )
    }

    private suspend fun Scenario.healthRide(phone: Phone, samples: (RideId) -> List<HealthSample>): RideId {
        val seeded =
            seedRide(
                endUs = 10_000_000,
                readingsUs = listOf(0L),
                columns = powerAndCadence,
                recordedBy = phone.device,
                provider = phone.provider,
                value = power(100.0),
            )
        archive.seed(WriteBatch(listOf(Mutation.AppendHealthSamples(seeded.ride.id, samples(seeded.ride.id)))))
        return seeded.ride.id
    }

    private suspend fun Scenario.summary(rideId: RideId): Map<String, Double?> {
        val open = ExportOpenInput(ExportSource.Ride(rideId), ExportKind.fit, FitPowerSource.rider)
        val view = assertIs<Outcome.Ok<ExportSessionView>>(api.exports.open(open)).value
        val page =
            assertIs<Outcome.Ok<ExportPage>>(api.exports.page(ExportPageInput(view.sessionId, "summary", null))).value
        assertEquals(Outcome.Ok(Unit), api.exports.close(ExportCloseInput(view.sessionId, null)))
        val kinds = (page.columns.getValue("kind") as ExportColumn.Texts).values
        val row = kinds.indexOf("session")
        return page.columns
            .filterValues { it is ExportColumn.Numbers }
            .mapValues { (_, column) -> (column as ExportColumn.Numbers).values[row].takeUnless { it.isNaN() } }
    }

    private fun energy(phone: Phone, expected: Double?, samples: (RideId) -> List<HealthSample>) {
        val scenario = Scenario()
        scenario.test {
            val rideId = scenario.healthRide(phone, samples)
            assertEquals(LaunchResult.Ready, scenario.launch())
            assertEquals(expected, scenario.summary(rideId)["caloriesKcal"])
        }
    }

    @Test
    fun energyN5ClipsEachIntervalRecordToTheRide() {
        for (phone in listOf(iphone, android)) energy(phone, 5.0) { id ->
            listOf(
                sample(id, "a", phone.activeEnergy, 4.0, -2.0, 2.0, HealthRepresentation.intervalRecord),
                sample(id, "b", phone.activeEnergy, 6.0, 8.0, 12.0, HealthRepresentation.intervalRecord),
            )
        }
    }

    @Test
    fun energySpanningTheRideKeepsItsShareInsideTheRide() {
        for (phone in listOf(iphone, android)) energy(phone, 10.0) { id ->
            listOf(sample(id, "a", phone.activeEnergy, 20.0, -5.0, 15.0, HealthRepresentation.intervalRecord))
        }
    }

    @Test
    fun overlappingEnergyRecordsAddUp() {
        for (phone in listOf(iphone, android)) energy(phone, 110.0) { id ->
            listOf(
                sample(id, "a", phone.activeEnergy, 10.0, 2.0, 4.0, HealthRepresentation.intervalRecord),
                sample(id, "b", phone.activeEnergy, 100.0, 0.0, 10.0, HealthRepresentation.intervalRecord),
            )
        }
    }

    @Test
    fun anIphoneRawEnergySampleCountsAsAnIntervalAndASeriesMemberDoesNot() {
        energy(iphone, 3.0) { id ->
            listOf(
                sample(id, "quantity", iphone.activeEnergy, 6.0, -5.0, 5.0),
                sample(id, "member", iphone.activeEnergy, 40.0, 2.0, 4.0, HealthRepresentation.rawSeries),
            )
        }
    }

    @Test
    fun energyAfterTheRideEndsHasNoCalories() {
        energy(android, null) { id ->
            listOf(sample(id, "a", android.activeEnergy, 6.0, 12.0, 14.0, HealthRepresentation.intervalRecord))
        }
    }

    @Test
    fun aHeartRateBarrierLeavesTheAverageOutAndKeepsTheMaximum() {
        val scenario = Scenario()
        scenario.test {
            val rideId =
                scenario.healthRide(iphone) { id ->
                    listOf(
                        sample(id, "a", iphone.heartRate, 100.0, 0.0, origin = "app.a"),
                        sample(id, "b", iphone.heartRate, null, 1.0, origin = "app.a"),
                        sample(id, "c", iphone.heartRate, 100.0, 2.0, origin = "app.a"),
                    )
                }
            assertEquals(LaunchResult.Ready, scenario.launch())
            val session = scenario.summary(rideId)
            assertEquals(100.0, session["maxHeartRateBpm"])
            assertNull(session["averageHeartRateBpm"])
        }
    }

    @Test
    fun theLatestHeartRateOriginTakesPrecedenceOverRawSamples() {
        val scenario = Scenario()
        scenario.test {
            val rideId =
                scenario.healthRide(iphone) { id ->
                    val latest = HealthRepresentation.builderMostRecent
                    listOf(
                        sample(id, "a", iphone.heartRate, 100.0, 0.0, representation = latest, origin = "app.a"),
                        sample(id, "b", iphone.heartRate, 200.0, 1.0, origin = "app.b"),
                        sample(id, "c", iphone.heartRate, 100.0, 2.0, representation = latest, origin = "app.a"),
                        sample(id, "d", iphone.heartRate, 200.0, 3.0, origin = "app.b"),
                    )
                }
            assertEquals(LaunchResult.Ready, scenario.launch())
            val session = scenario.summary(rideId)
            assertEquals(100.0, session["maxHeartRateBpm"])
            assertEquals(100.0, session["averageHeartRateBpm"])
        }
    }

    private fun interrupted(resumed: Boolean, expectedWorkJ: Double, expectedTimerUs: Double) {
        val scenario = Scenario()
        scenario.test {
            val events =
                listOf(RideEventKind.pause to 1_000_000L) +
                    if (resumed) listOf(RideEventKind.resume to 1_000_000L) else emptyList()
            val seeded =
                scenario.seedRide(
                    phase = RidePhase.running,
                    endUs = 3_000_000,
                    readingsUs = listOf(0L, 1_000_000L),
                    columns = powerAndCadence,
                    events = events,
                    value = power(100.0),
                )
            val rideId = seeded.ride.id
            val mutations = mutableListOf<Mutation>()
            if (resumed) {
                val samples =
                    listOf(1_000_000L, 2_000_000L).mapIndexed { index, elapsedUs ->
                        val quanta =
                            mapOf(MetricId.humanPowerW to 300.0, MetricId.cadenceRpm to 80.0).mapValues {
                                (it.value * MetricCatalog.of(it.key).quantum).roundToLong()
                            }
                        TelemetrySample(
                            rideId,
                            2,
                            index + 1L,
                            elapsedUs,
                            Scenario.START_UTC_US + elapsedUs,
                            2,
                            2,
                            quanta,
                        )
                    }
                mutations +=
                    Mutation.OpenControllerSession(
                        ControllerSession(rideId, 2, seededIdentity, ControllerCapabilities(powerAndCadence, false))
                    )
                mutations += Mutation.AppendTelemetry(rideId, TelemetryRows.of(samples))
            }
            val stopUtcUs = Scenario.START_UTC_US + 3_000_000
            val stop = RideEvent(EventId("seeded-stored-ride-stop"), RideEventKind.stop, stopUtcUs, 3_000_000)
            mutations += Mutation.AppendEvents(rideId, listOf(stop))
            val end = RideTiming(stopUtcUs, 3_000_000, expectedTimerUs.toLong())
            mutations += Mutation.EndRide(rideId, end)
            mutations += Mutation.SetPhase(rideId, RidePhase.completed)
            scenario.archive.seed(WriteBatch(mutations))
            assertEquals(LaunchResult.Ready, scenario.launch())
            val session = scenario.summary(rideId)
            assertEquals(expectedWorkJ, session["workJ"])
            assertEquals(expectedTimerUs, session["timerUs"])
        }
    }

    @Test fun anInterruptionFollowedByStopEndsTheWorkAndTimerAtThePause() = interrupted(false, 100.0, 1_000_000.0)

    @Test fun anInterruptionFollowedByResumeCountsBothConnections() = interrupted(true, 400.0, 3_000_000.0)

    private fun zipHealth(watch: Boolean, check: (ExportSessionView, ExportPage) -> Unit) {
        val scenario = Scenario()
        scenario.test {
            val rideId =
                scenario.healthRide(iphone) { id ->
                    listOfNotNull(
                        if (watch) sample(id, "w", iphone.heartRate, 90.0, 1.0, producer = Producer.watch) else null,
                        sample(id, "t", null, null, 3.0, representation = HealthRepresentation.healthTombstone),
                        sample(id, "a", null, null, 4.0, representation = HealthRepresentation.workoutAssociation),
                    )
                }
            assertEquals(LaunchResult.Ready, scenario.launch())
            val open = ExportOpenInput(ExportSource.Ride(rideId), ExportKind.zip, FitPowerSource.rider)
            val view = assertIs<Outcome.Ok<ExportSessionView>>(scenario.api.exports.open(open)).value
            val input = ExportPageInput(view.sessionId, "healthZip", null)
            val page = assertIs<Outcome.Ok<ExportPage>>(scenario.api.exports.page(input)).value
            assertEquals(Outcome.Ok(Unit), scenario.api.exports.close(ExportCloseInput(view.sessionId, null)))
            check(view, page)
        }
    }

    private fun ExportPage.texts(name: String) = (columns.getValue(name) as ExportColumn.Texts).values

    private fun ExportPage.numbers(name: String) =
        (columns.getValue(name) as ExportColumn.Numbers).values.map { it.takeUnless(Double::isNaN) }

    @Test
    fun aZipKeepsThePhonesTombstoneAndWorkoutAssociationBesideTheWatchsSamples() =
        zipHealth(watch = true) { view, page ->
            assertEquals(listOf(Producer.phone, Producer.watch), view.producers.health)
            assertEquals(listOf("watch", "phone", "phone"), page.texts("producer"))
            assertEquals(listOf("rawQuantity", "healthTombstone", "workoutAssociation"), page.texts("representation"))
            assertEquals(listOf(90.0, null, null), page.numbers("value"))
            assertEquals(listOf("w", "t", "a"), page.texts("originalEventId"))
        }

    @Test
    fun aZipWhoseLastPhoneSampleWasDeletedListsOnlyThePhone() =
        zipHealth(watch = false) { view, page ->
            assertEquals(listOf(Producer.phone), view.producers.health)
            assertEquals(listOf("healthTombstone", "workoutAssociation"), page.texts("representation"))
            assertEquals(listOf(null, null), page.numbers("value"))
        }

    @Test
    fun anIphoneRawSamplePrintsItsTimestampToTheMicrosecondAndABuilderRowToTheMillisecond() {
        val scenario = Scenario()
        scenario.test {
            val rideId =
                scenario.healthRide(iphone) { id ->
                    listOf(
                        sample(id, "raw", iphone.heartRate, 120.0, 1.323262),
                        sample(
                            id,
                            "builder",
                            iphone.heartRate,
                            121.0,
                            2.323262,
                            representation = HealthRepresentation.builderMostRecent,
                        ),
                    )
                }
            assertEquals(LaunchResult.Ready, scenario.launch())
            val open = ExportOpenInput(ExportSource.Ride(rideId), ExportKind.zip, FitPowerSource.rider)
            val view = assertIs<Outcome.Ok<ExportSessionView>>(scenario.api.exports.open(open)).value
            val input = ExportPageInput(view.sessionId, "healthZip", null)
            val page = assertIs<Outcome.Ok<ExportPage>>(scenario.api.exports.page(input)).value
            assertEquals(Outcome.Ok(Unit), scenario.api.exports.close(ExportCloseInput(view.sessionId, null)))
            assertEquals(
                listOf("2026-01-01T00:00:01.323262000Z", "2026-01-01T00:00:02.323Z"),
                page.texts("timestamp"),
            )
        }
    }
}
