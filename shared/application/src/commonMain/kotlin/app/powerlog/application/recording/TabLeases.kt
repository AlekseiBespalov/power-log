package app.powerlog.application.recording

import app.powerlog.application.api.lease
import app.powerlog.application.port.TabLease
import app.powerlog.application.port.TabLock
import app.powerlog.application.store.ArchiveWriter
import app.powerlog.application.store.StorageError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

internal class TabLeases(
    private val scope: CoroutineScope,
    private val ride: CoroutineDispatcher,
    private val lock: TabLock,
    private val writer: ArchiveWriter,
    private val rides: OpenRides,
    private val state: RecordingState,
    private val live: LivePublisher,
) {
    private var held: TabLease? = null
    private var taking = false
    private var queued = false
    private var checking: CompletableDeferred<Unit>? = null
    private val requests = Channel<Unit>(Channel.CONFLATED)

    suspend fun launch(): StorageError? = reconcile(atLaunch = true)

    fun watch() {
        scope.launch(ride) { for (request in requests) checked { reconcile(atLaunch = false) } }
        scope.launch(ride, CoroutineStart.UNDISPATCHED) {
            writer.historyCommits.collect { if (it.otherTab) check() }
        }
    }

    fun check() {
        requests.trySend(Unit)
    }

    suspend fun take(waitIfHeld: Boolean): Boolean {
        checking?.await()
        if (held != null) return true
        taking = true
        try {
            held = lock.lease(LOCK, waitIfHeld)
        } finally {
            taking = false
        }
        return held != null
    }

    fun release() {
        held?.release()
        held = null
    }

    private suspend fun checked(check: suspend () -> Unit) {
        if (queued || taking || held != null) return
        val running = CompletableDeferred<Unit>()
        checking = running
        try {
            check()
        } finally {
            checking = null
            running.complete(Unit)
        }
    }

    private suspend fun reconcile(atLaunch: Boolean): StorageError? {
        val stored =
            when (val read = rides.read(if (atLaunch) "launch" else "read")) {
                is OpenRides.Read.Failed -> return read.error.takeIf { atLaunch }
                is OpenRides.Read.Found -> read.ride
            }
        if (stored == null || stored.phase !in RecordingState.recordingPhases) {
            settle(stored)
            return null
        }
        val lease = lock.lease(LOCK, waitIfHeld = false) ?: return null.also { queue() }
        return underLease(lease, atLaunch)
    }

    private fun queue() {
        if (state.open != null) rides.restore(null)
        state.otherTab = true
        live.publish(urgent = true)
        queued = true
        scope.launch(ride) {
            val lease = lock.lease(LOCK, waitIfHeld = true)
            val running = CompletableDeferred<Unit>()
            checking = running
            try {
                if (lease != null) underLease(lease, atLaunch = false)
            } finally {
                queued = false
                checking = null
                running.complete(Unit)
            }
        }
    }

    private suspend fun underLease(lease: TabLease, atLaunch: Boolean): StorageError? {
        try {
            val stored =
                when (val read = rides.read(if (atLaunch) "launch" else "read")) {
                    is OpenRides.Read.Failed -> return read.error.takeIf { atLaunch }
                    is OpenRides.Read.Found -> read.ride
                }
            if (stored == null || stored.phase !in RecordingState.recordingPhases) {
                settle(stored)
                return null
            }
            state.otherTab = false
            return rides.interrupt(stored, atLaunch)
        } finally {
            lease.release()
        }
    }

    private fun settle(stored: app.powerlog.domain.core.ride.Ride?) {
        state.otherTab = false
        if (rides.holds(stored)) live.publish(urgent = true) else rides.restore(stored)
    }

    companion object {
        const val LOCK = "power-log-recording"
    }
}
