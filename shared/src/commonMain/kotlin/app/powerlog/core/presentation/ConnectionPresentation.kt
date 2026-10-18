package app.powerlog.core.presentation

import app.powerlog.core.StreamStatus

object ReadingFreshness {
    fun available(acquiredAt: Double?, now: Double?, cutoff: Double): Boolean =
        acquiredAt != null &&
            acquiredAt.isFinite() &&
            now != null &&
            now.isFinite() &&
            cutoff.isFinite() &&
            cutoff > 0 &&
            StreamStatus.available(now - acquiredAt, cutoff, "running")
}

data class ControllerPreviewObservation(
    val connection: String,
    val sequence: Long,
    val acquiredAt: Double,
    val faultCode: Double,
)

data class ControllerPreviewView(val display: String, val faultCode: Double?, val nextDeadline: Double?)

class ControllerPreview {
    var observation: ControllerPreviewObservation? = null
        private set

    fun clear() {
        observation = null
    }

    fun observe(
        connection: String,
        sequence: Long,
        acquiredAt: Double,
        faultCode: Double,
        mappedAcquisition: Double?,
        now: Double,
    ): Boolean {
        if (
            sequence < 0 ||
                !acquiredAt.isFinite() ||
                acquiredAt < 0 ||
                !faultCode.isFinite() ||
                !now.isFinite() ||
                now < 0 ||
                (mappedAcquisition != null &&
                    (!mappedAcquisition.isFinite() || mappedAcquisition < 0 || mappedAcquisition > now))
        )
            return false
        val old = observation
        val sameConnection = old?.connection == connection
        when (
            ObservationOrder.admit(
                sameConnection && old?.sequence == sequence,
                old != null,
                if (sameConnection) old?.sequence else null,
                if (sameConnection) sequence else null,
                if (old == null) 1 else acquiredAt.compareTo(old.acquiredAt),
                false,
            )
        ) {
            ObservationAdmission.retain -> return false
            ObservationAdmission.earlierDuplicate -> observation = old!!.copy(acquiredAt = acquiredAt)
            ObservationAdmission.replace ->
                observation = ControllerPreviewObservation(connection, sequence, acquiredAt, faultCode)
        }
        return true
    }

    fun view(connection: String, mappedAcquisition: Double?, now: Double): ControllerPreviewView {
        val acquired = if (observation == null) null else mappedAcquisition
        val display = StreamStatus.connectionDisplay(connection, acquired?.let { now - it })
        return ControllerPreviewView(
            display,
            if (display == "unavailable") null else observation?.faultCode,
            StreamStatus.connectionDeadline(connection, acquired, now),
        )
    }
}
