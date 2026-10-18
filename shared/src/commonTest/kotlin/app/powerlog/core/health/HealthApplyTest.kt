package app.powerlog.core.health

import app.powerlog.core.analytics.AnalysisRange
import kotlin.test.Test
import kotlin.test.assertEquals

class HealthApplyTest {
    @Test
    fun healthCoverageKeepsAggregateRepresentationsOutOfIntervals() {
        val interval =
            EnergyInterval(
                Source.phone,
                Origin(Provider.appleHealth, "app.intervals", PID, PID, Source.phone, 1, null),
                1,
                0,
                7000000,
                "2026-01-01T00:00:00Z",
                "2026-01-01T00:00:07Z",
                10.0,
            )
        val point =
            EnergyPoint(
                Source.phone,
                Origin(Provider.appleHealth, "app.aggregate", PID, PID, Source.phone, 2, null),
                1,
                10000000,
                "2026-01-01T00:00:10Z",
                20.0,
                Association.Workout(HealthIds.workout(R)),
            )
        val preparation = EnergyOriginPreparation("phone", AnalysisRange(0, 10000000))
        preparation.append(EnergyEvidence.IntervalRecord("active", interval))
        preparation.append(EnergyEvidence.FinalWorkoutTotal("active", point))
        preparation.append(EnergyEvidence.CumulativeSnapshot("active", point))
        assertEquals("app.intervals", preparation.selected()?.dataOrigin)
        assertEquals(7000000L, preparation.selected()?.coveredUs)
    }
}
