package app.powerlog.application.slice

import app.powerlog.application.api.Empty
import app.powerlog.application.api.Outcome
import app.powerlog.application.store.LogValue
import app.powerlog.domain.core.ride.SampleRate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay

class CaptureCostScenarioTest {
    @Test
    fun anEightHertzRideCommitsAboutOnceASecondWithOneWriterHopAndNoWorkPerBatchElsewhere() {
        val scenario = Scenario()
        scenario.test {
            with(scenario) { ready(SampleRate.hz8) }
            delay(5_000)
            val previewFrom = scenario.dispatches["ride"] ?: 0
            delay(60_000)
            val preview = (scenario.dispatches["ride"] ?: 0) - previewFrom
            assertEquals(
                app.powerlog.application.api.Outcome.Ok(Unit),
                scenario.api.recording.start(app.powerlog.application.api.Empty),
            )
            delay(5_000)
            val commitsBefore = scenario.archive.committed.size
            val before = scenario.dispatches.toMap()
            val logBefore = scenario.archive.log.size
            delay(60_000)
            val commits = scenario.archive.committed.size - commitsBefore
            fun delta(name: String) = (scenario.dispatches[name] ?: 0) - (before[name] ?: 0)
            assertTrue(commits in 55..65, "$commits commits")
            assertEquals(commits, delta("store"))
            val perCommit = (delta("ride") - preview).toDouble() / commits
            assertTrue(perCommit <= 3.5, "$perCommit ride tasks per commit beyond the preview's")
            assertEquals(0, delta("health"))
            assertEquals(0, delta("reads"))
            assertEquals(2, delta("maintenance"))
            assertEquals(
                listOf("captureSummary", "captureSummary"),
                scenario.archive.log.drop(logBefore).map { it.event },
            )
        }
    }

    @Test
    fun commitMaxMsRunsFromTheOldestReadingsAcquisitionToTheEndOfItsCommit() {
        val scenario = Scenario()
        scenario.test {
            with(scenario) { ready(SampleRate.hz8) }
            assertEquals(Outcome.Ok(Unit), scenario.api.recording.start(Empty))
            scenario.archive.onCommit = {
                delay(400)
                null
            }
            delay(65_000)
            val summary = scenario.archive.log.last { it.event == "captureSummary" }
            val commitMaxMs = (summary.fields.first { it.key == "commitMaxMs" }.value as LogValue.Integer).value
            val archive = scenario.archive
            assertEquals(archive.attempts.size, archive.committed.size)
            val ownMaxMs =
                archive.committedAtUs.zip(archive.attemptedAtUs).maxOf { (end, begin) -> end - begin } / 1_000
            assertEquals(400L, ownMaxMs)
            assertTrue(commitMaxMs in 1_000L..1_525L, "commitMaxMs $commitMaxMs")
        }
    }
}
