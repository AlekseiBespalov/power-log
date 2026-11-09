package app.powerlog.platform.scheduler

import android.os.UserManager
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import app.powerlog.application.api.ApiFailure
import app.powerlog.application.api.LaunchContext
import app.powerlog.application.health.HealthWorkResult
import app.powerlog.application.port.Clock
import app.powerlog.application.port.ScheduleKind
import app.powerlog.application.recording.RideCardIntent
import app.powerlog.domain.core.failure.RefusalDetail
import app.powerlog.domain.core.time.ClockReading
import app.powerlog.platform.host.HealthWorker
import app.powerlog.platform.host.PowerLogAndroid
import app.powerlog.runtime.host.HostFacade
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class AndroidBackgroundSchedulerTest {
    private val context = RuntimeEnvironment.getApplication()
    private val clock =
        object : Clock {
            var utc = 1_700_000_000_000_000L

            override fun monoUs(): Long = 0

            override fun utcUs(): Long = utc

            override fun read(): ClockReading = ClockReading(monoUs(), utcUs())
        }
    private val scheduler = AndroidBackgroundScheduler(context, HealthWorker::class.java, clock)
    private val ride = "11111111-1111-4111-8111-111111111111"
    private lateinit var work: WorkManager

    @Before
    fun startWorkManager() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        work = WorkManager.getInstance(context)
    }

    @After
    fun forgetHost() {
        PowerLogAndroid.host = null
    }

    private fun infos(): List<WorkInfo> = work.getWorkInfosForUniqueWork(AndroidBackgroundScheduler.name(ride)).get()

    private fun pending(): List<WorkInfo> = infos().filter { !it.state.isFinished }

    @Test
    fun keepLeavesPendingWorkUnchanged() {
        scheduler.schedule(ride, clock.utc + 900_000_000, ScheduleKind.keep)
        val first = pending().single()
        assertEquals(900_000L, first.initialDelayMillis)
        scheduler.schedule(ride, null, ScheduleKind.keep)
        assertEquals(listOf(first.id), pending().map { it.id })
        assertEquals(900_000L, pending().single().initialDelayMillis)
    }

    @Test
    fun aRetriedRunWaitsTheTenSecondBackOff() {
        PowerLogAndroid.host = AnsweringHost(HealthWorkResult.retry, mutableListOf())
        scheduler.schedule(ride, null, ScheduleKind.replace)
        val retried = pending().single()
        assertEquals(1, retried.runAttemptCount)
        val wait = retried.nextScheduleTimeMillis - System.currentTimeMillis()
        assertTrue("next run in $wait ms", wait in 8_000..10_000)
    }

    @Test
    fun replaceCancelsPendingWorkForAnImmediateRun() {
        scheduler.schedule(ride, clock.utc + 900_000_000, ScheduleKind.keep)
        val first = pending().single().id
        scheduler.schedule(ride, null, ScheduleKind.replace)
        val replaced = pending().single()
        assertEquals(0L, replaced.initialDelayMillis)
        assertTrue(work.getWorkInfoById(first).get()?.state.let { it == null || it == WorkInfo.State.CANCELLED })
    }

    @Test
    fun afterCurrentAppendsBehindTheRunningWork() {
        scheduler.schedule(ride, clock.utc + 900_000_000, ScheduleKind.keep)
        val first = pending().single().id
        scheduler.schedule(ride, clock.utc + 3_600_000_000, ScheduleKind.afterCurrent)
        val states = pending().associate { it.id to it.state }
        assertEquals(2, states.size)
        assertEquals(WorkInfo.State.ENQUEUED, states[first])
        assertEquals(WorkInfo.State.BLOCKED, states.filterKeys { it != first }.values.single())
    }

    @Test
    fun aPastNotBeforeRunsWithoutDelay() {
        scheduler.schedule(ride, clock.utc - 5_000_000, ScheduleKind.replace)
        assertEquals(0L, pending().single().initialDelayMillis)
    }

    @Test
    fun cancelCancelsTheRidesWork() {
        scheduler.schedule(ride, clock.utc + 900_000_000, ScheduleKind.keep)
        scheduler.cancel(ride)
        assertEquals(listOf(WorkInfo.State.CANCELLED), infos().map { it.state })
    }

    @Test
    fun theWorkerAnswersTheHostsResultAndPassesItsAttempt() {
        val calls = mutableListOf<Pair<String, Int>>()
        for ((answer, expected) in
            listOf(
                HealthWorkResult.success to ListenableWorker.Result.success(),
                HealthWorkResult.retry to ListenableWorker.Result.retry(),
                HealthWorkResult.failure to ListenableWorker.Result.failure(),
            )) {
            PowerLogAndroid.host = AnsweringHost(answer, calls)
            val worker =
                TestListenableWorkerBuilder<HealthWorker>(context)
                    .setInputData(workDataOf(AndroidBackgroundScheduler.RIDE to ride))
                    .setRunAttemptCount(2)
                    .build()
            assertEquals(expected, runBlocking { worker.doWork() })
        }
        assertEquals(List(3) { ride to 2 }, calls)
    }

    @Test
    fun theWorkerRetriesBeforeTheFirstUnlockWithoutCallingTheHost() {
        val calls = mutableListOf<Pair<String, Int>>()
        PowerLogAndroid.host = AnsweringHost(HealthWorkResult.success, calls)
        shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(false)
        val worker =
            TestListenableWorkerBuilder<HealthWorker>(context)
                .setInputData(workDataOf(AndroidBackgroundScheduler.RIDE to ride))
                .build()
        assertEquals(ListenableWorker.Result.retry(), runBlocking { worker.doWork() })
        assertEquals(emptyList<Pair<String, Int>>(), calls)
    }

    @Test
    fun theWorkerFailsWithoutARide() {
        PowerLogAndroid.host = AnsweringHost(HealthWorkResult.success, mutableListOf())
        val worker = TestListenableWorkerBuilder<HealthWorker>(context).setId(UUID.randomUUID()).build()
        assertEquals(ListenableWorker.Result.failure(), runBlocking { worker.doWork() })
    }

    private class AnsweringHost(
        private val answer: HealthWorkResult,
        private val calls: MutableList<Pair<String, Int>>,
    ) : HostFacade {
        override fun launch(context: LaunchContext, done: (RefusalDetail?) -> Unit) {}

        override fun foregroundChanged(foreground: Boolean) {}

        override fun didEnterBackground(done: () -> Unit) {}

        override fun willTerminate(timeoutUs: Long): Boolean = true

        override fun rideCardIntent(intent: RideCardIntent, done: (ApiFailure?) -> Unit) {}

        override fun runHealthWork(rideId: String, attempt: Int, done: (HealthWorkResult) -> Unit) {
            calls += rideId to attempt
            done(answer)
        }
    }
}
