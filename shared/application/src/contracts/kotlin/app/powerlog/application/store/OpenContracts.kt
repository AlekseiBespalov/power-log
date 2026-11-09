package app.powerlog.application.store

import app.powerlog.application.store.ContractData.START_UTC_US
import app.powerlog.application.store.ContractData.append
import app.powerlog.application.store.ContractData.fix
import app.powerlog.application.store.ContractData.health
import app.powerlog.application.store.ContractData.readings
import app.powerlog.application.store.ContractData.ride
import app.powerlog.application.store.ContractData.rideId
import app.powerlog.application.store.ContractData.session
import app.powerlog.application.store.ContractData.timing
import app.powerlog.domain.core.failure.RefusalDetail
import app.powerlog.domain.core.ids.RideId
import app.powerlog.domain.core.ride.RidePhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

abstract class RefusedArchiveContract : ArchiveContract() {
    @Test
    fun anArchiveOfAnotherShapeIsRefusedAsUnreadableAndLeftByteForByte() = runTest {
        assertEquals(harness.foreignKinds, harness.foreignArchives.map { it.kind }.toSet())
        for (foreign in harness.foreignArchives) {
            val case = "${foreign.kind}: ${foreign.name}"
            val store = harness.create(this)
            foreign.write(store)
            val before = store.snapshot().mapValues { it.value.toList() }
            assertEquals(StoreOpen.Refused(RefusalDetail.unreadable), store.host.open().ok(), case)
            assertEquals(before, store.snapshot().mapValues { it.value.toList() }, case)
            store.rides.marker().assertFailed(StorageError.Kind.closed)
            store.answer(Mutation.CreateRide(ride(rideId(1)), health)).assertFailed(StorageError.Kind.closed)
            assertEquals(before, store.snapshot().mapValues { it.value.toList() }, case)
        }
    }

    @Test
    fun callsBeforeOpenAnswerClosed() = runTest {
        val store = harness.create(this)
        store.rides.marker().assertFailed(StorageError.Kind.closed)
        store.answer(Mutation.CreateRide(ride(rideId(1)), health)).assertFailed(StorageError.Kind.closed)
    }
}

abstract class CorruptArchiveContract : SqliteArchiveContract() {
    @Test
    fun aCorruptPageAnswersCorruptForEveryLaterCallAndKeepsTheFile() = sqlite { store ->
        val id = rideId(1)
        store.create(ride(id))
        store.injectCorruption()
        store.rides.ride(id).assertFailed(StorageError.Kind.corrupt)
        store.rides.page(null, 10).assertFailed(StorageError.Kind.corrupt)
        store.measurements.extent(id).assertFailed(StorageError.Kind.corrupt)
        store.answer(Mutation.Checkpoint(id, timing(START_UTC_US, 1_000_000))).assertFailed(StorageError.Kind.corrupt)
        store.rides.marker().assertFailed(StorageError.Kind.corrupt)
        assertTrue(archiveFile in store.archiveFiles())
    }

    private val archiveFile: String
        get() = "power-log-archive-${harness.archiveVersion}.sqlite3"
}

abstract class PragmaContract : SqliteArchiveContract() {
    @Test
    fun aNewArchiveIsNamedForItsVersionAndBothWritersRunWalWithFullSyncAndIncrementalVacuum() = runTest {
        val store = harness.create(this)
        val opened = assertIs<StoreOpen.Opened>(store.host.open().ok())
        val expected =
            listOf(FilePragmas(StoreFile.archive, 2, "wal", 2, 1_000), FilePragmas(StoreFile.cache, 2, "wal", 2, 1_000))
        assertEquals(expected, opened.pragmas)
        assertEquals(expected, store.writerPragmas())
        assertTrue("power-log-archive-${harness.archiveVersion}.sqlite3" in store.archiveFiles())
    }
}

abstract class OtherTabContract : WebArchiveContract() {
    @Test
    fun aCommitInOneConnectionArrivesInTheOtherAsAnOtherTabNotice() = web { store ->
        val id = rideId(1)
        store.create(ride(id))
        val other = opened(store.secondConnection())
        val commits = Channel<CommitNotice>(Channel.UNLIMITED)
        val history = Channel<CommitNotice>(Channel.UNLIMITED)
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        backgroundScope.launch(dispatcher) { other.writer.commits.collect { if (it.otherTab) commits.send(it) } }
        backgroundScope.launch(dispatcher) { other.writer.historyCommits.collect { if (it.otherTab) history.send(it) } }
        val committed = store.commit(Mutation.SetPhase(id, RidePhase.paused))
        val expected = CommitNotice(committed.revisions, committed.historyRevision, setOf(id), otherTab = true)
        assertEquals(expected, commits.receive())
        assertEquals(expected, history.receive())
    }

    @Test
    fun aDeleteOfARideTheOtherConnectionHasOpenAnswersRideOpen() = web { store ->
        val other = opened(store.secondConnection())
        val id = rideId(1)
        store.create(ride(id))
        assertEquals(
            Commit.RideOpen(id),
            other
                .answer(
                    Mutation.MarkRideDeleted(id, START_UTC_US),
                    Mutation.RemoveHealthWork(id, null),
                    closedRide = id,
                )
                .ok(),
        )
        assertEquals(RidePhase.running, store.rides.ride(id).ok()?.ride?.phase)
    }
}

abstract class CapturePlanContract : SqliteArchiveContract() {
    @Test
    fun aCaptureCommitAtHourEightRunsTheStatementsOfMinuteOneEachAnIndexSearch() =
        sqlite(timeout = 10.minutes) { store ->
            val id = rideId(1)
            store.create(ride(id))
            store.commit(Mutation.OpenControllerSession(session(id)))
            val minute = 60_000_000L
            fill(store, id, 0, minute)
            val minuteOne = store.statementPlans { store.commit(*capture(id, minute)) }
            val hours = 8 * 60 * minute
            fill(store, id, minute + SECOND, hours)
            val hourEight = store.statementPlans { store.commit(*capture(id, hours + SECOND)) }
            assertEquals(minuteOne.map { it.sql }, hourEight.map { it.sql })
            for (statement in minuteOne + hourEight) {
                val scans = statement.plan.filter { it.trimStart().startsWith("SCAN") }
                assertEquals(emptyList(), scans, statement.sql)
            }
        }

    private suspend fun fill(store: StoreUnderTest, id: RideId, fromUs: Long, toUs: Long) {
        val rows = ((toUs - fromUs) / READING_US).toInt()
        for (first in 0 until rows step 4_096) {
            val count = minOf(4_096, rows - first)
            val startUs = fromUs + first * READING_US
            store.commit(
                append(id, readings(count, fromUs = startUs, stepUs = READING_US, firstSeq = seqAt(startUs))),
                Mutation.AppendLocations(
                    id,
                    (0 until count / 8).map { fix(id, seqAt(startUs) / 8 + it + 1, startUs + it * SECOND) },
                ),
                Mutation.Checkpoint(id, timing(START_UTC_US, startUs + count * READING_US)),
            )
        }
    }

    private fun capture(id: RideId, atUs: Long): Array<Mutation> =
        arrayOf(
            append(id, readings(8, fromUs = atUs, stepUs = READING_US, firstSeq = seqAt(atUs))),
            Mutation.AppendLocations(id, listOf(fix(id, seqAt(atUs) / 8 + 1, atUs))),
            Mutation.Checkpoint(id, timing(START_UTC_US, atUs + SECOND)),
        )

    private fun seqAt(elapsedUs: Long): Long = elapsedUs / READING_US + 1

    private companion object {
        const val SECOND = 1_000_000L
        const val READING_US = 125_000L
    }
}

abstract class ReadersDuringWriteContract : SqliteArchiveContract() {
    @Test
    fun rideOpenRideAndPageCompleteWhileTheWriterHoldsATransactionOpen() = sqlite { store ->
        val id = rideId(1)
        store.create(ride(id))
        store.commit(Mutation.OpenControllerSession(session(id)), append(id, readings(3)))
        val record = store.rides.ride(id).ok()
        val page = store.rides.page(null, 10).ok()
        val held = store.holdWriteTransaction()
        try {
            assertEquals(record, store.rides.ride(id).ok())
            assertEquals(record, store.rides.openRide().ok())
            assertEquals(page, store.rides.page(null, 10).ok())
        } finally {
            held.release()
        }
    }
}
