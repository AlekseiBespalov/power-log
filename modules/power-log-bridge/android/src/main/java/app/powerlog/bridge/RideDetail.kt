package app.powerlog.bridge

import app.powerlog.core.analytics.*
import app.powerlog.core.health.EnergyEvidence
import expo.modules.kotlin.exception.CodedException

internal class RideDetail(
    private val store: RideStore,
    private val distance: RideDistance,
) {
    fun detail(id: String, source: String): Payload =
        store.withReadPin(id, source) {
            val analysis = RideAnalysis(store)
            val pin = analysis.pin(id, source)
            check(!Thread.currentThread().isInterrupted) { "Analysis cancelled" }
            store.summaries.read(pin)?.let {
                return@withReadPin mapOf("metadata" to store.metadata(id), "summary" to it)
            }
            val health = HealthReadings(store)
            val origins = health.origins(id)
            val selections = health.selections(id, origins)
            val preparation = SummaryPreparation(pin)
            analysis.eachSummary(id, preparation::append)
            val window = AnalysisRange(0, elapsedUs(store.timing(id).elapsed))
            fun energies(body: (EnergyEvidence) -> Unit) =
                store.healthOriginalRows(id, "activeEnergy") { row ->
                    if (row.evidence["measure"] == "activeEnergy")
                        health.interval(row, window)?.let { body(EnergyEvidence.IntervalRecord("active", it)) }
                }
            energies(preparation::energy)
            val info = distance.coreInfo(id, source)
            val prepared = preparation.finish(info)
            val composer = SummaryComposer(prepared, info, selections)
            analysis.eachSummary(id, composer::append)
            energies(composer::energy)
            val result =
                when (val outcome = composer.finish()) {
                    is SummaryOutcome.Complete -> outcome.summary
                    is SummaryOutcome.Overflow ->
                        throw CodedException(
                            "ERR_NUMERIC_OVERFLOW",
                            "${outcome.failure.metric}:${outcome.failure.quantity}",
                            null,
                        )
                    is SummaryOutcome.InvalidEvidence ->
                        throw CodedException("ERR_INVALID_RECORD", outcome.reason, null)
                    SummaryOutcome.Cancelled -> throw CodedException("ERR_READ_CANCELLED", "Analysis cancelled", null)
                }
            val summary =
                (SummaryWire.fields(result, store.lifecycleCount(id)) +
                        mapOf(
                            "healthOrigins" to origins,
                            "batteryConsumption" to RideConsumption(store).summary(id),
                            "physiologyProvenance" to "backfilled",
                        ))
                    .filterValues { it != null }
            check(!Thread.currentThread().isInterrupted) { "Analysis cancelled" }
            store.publishSummary(pin, summary)
            mapOf("metadata" to store.metadata(id), "summary" to summary)
        }
}

internal fun DistanceProfile.payload(): Payload =
    mapOf(
        "source" to source,
        "label" to label,
        "estimated" to estimated,
        "partial" to partial,
        "coveredSeconds" to coveredSeconds,
        "uncoveredSeconds" to uncoveredSeconds,
        "distanceMeters" to distanceMeters,
    )

internal fun DistanceInfo.payload(): Payload =
    mapOf(
        "selection" to selection,
        "selected" to selected?.payload(),
        "available" to available.map { it.payload() },
    )
