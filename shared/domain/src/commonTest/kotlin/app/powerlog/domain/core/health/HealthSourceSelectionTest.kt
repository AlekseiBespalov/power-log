package app.powerlog.domain.core.health

import app.powerlog.domain.analytics.fixture
import kotlin.test.Test
import kotlin.test.assertEquals

class HealthSourceSelectionTest {
    @Test
    fun readbackKeepsTheOriginThatCoveredTheRideBest() {
        val cases = fixture("domain/readback-origins.json")["cases"].list()
        assertEquals(5, cases.size)
        for (case in cases) {
            val origins = case["origins"].list().map { OriginCoverage(it["dataOrigin"].text(), it["coveredUs"].long()) }
            assertEquals(
                case["selected"].optionalText(),
                HealthSourceSelection.select(origins)?.dataOrigin,
                case["name"].text(),
            )
            assertEquals(
                HealthSourceSelection.select(origins),
                HealthSourceSelection.select(origins.reversed()),
                case["name"].text(),
            )
        }
    }
}
