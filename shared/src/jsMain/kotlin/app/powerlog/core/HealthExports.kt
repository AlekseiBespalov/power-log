@file:OptIn(ExperimentalJsExport::class)

package app.powerlog.core

import app.powerlog.core.health.*
import kotlin.js.JsExport

@JsExport
class HealthEnergyConsumption(source: String, endUs: String, dataOrigin: String?, metric: String = "active") {
    private val reducer = app.powerlog.core.analytics.EnergyReducer(metric, endUs.toLong(), source, dataOrigin)

    fun append(evidence: AnalyticsEnergy) {
        val item = evidence.core()
        if (app.powerlog.core.analytics.summaryCollectionAvailable(item.collectionUs)) reducer.append(item)
    }

    fun value(): Double? = reducer.valueQ()?.let { app.powerlog.core.analytics.FixedPoint.divide(it, 1000000) }
}
