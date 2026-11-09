package app.powerlog.kit

import app.powerlog.domain.core.metric.MetricCatalog
import app.powerlog.domain.core.metric.MetricId
import kotlin.test.Test
import kotlin.test.assertEquals

class AppearanceTest {
    private val catalog = fixture("telemetry/metrics.json")["metrics"].list().associateBy { it["id"].text() }

    @Test
    fun everyMetricLooksAsInTodaysCatalog() {
        for (id in MetricId.entries) {
            val today = catalog.getValue(id.name)
            val appearance = MetricAppearance.of(id)
            assertEquals(today["label"].text(), appearance.label, id.name)
            assertEquals(today["shortLabel"].optionalText(), appearance.shortLabel, id.name)
            assertEquals(today["color"].text(), appearance.color, id.name)
            assertEquals(today["group"].text().lowercase(), appearance.group.name, id.name)
            assertEquals(today["kind"].optionalText() == "step", appearance.step, id.name)
            assertEquals(today["zero"] == J.Bool(true), appearance.zero, id.name)
            val definition = MetricCatalog.of(id)
            assertEquals(definition.selectable, appearance.selectable, id.name)
            assertEquals(definition.semantics, appearance.semantics, id.name)
            assertEquals(definition.allowsMean, appearance.allowsMean, id.name)
            assertEquals(definition.origin, appearance.origin, id.name)
        }
        assertEquals(33, MetricId.entries.count { MetricAppearance.of(it).selectable })
    }
}
