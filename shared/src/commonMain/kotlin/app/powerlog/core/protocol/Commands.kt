package app.powerlog.core.protocol

class Decision(val outcome: String, val rejection: Rejection? = null)

object OwnerSnapshots {
    fun admit(retained: OwnerSnapshot?, incoming: OwnerSnapshot): Decision =
        try {
            Schema.owner(incoming.json())
            if (retained == null) Decision("apply")
            else if (incoming.ownerRevision < retained.ownerRevision) Decision("ignore")
            else if (incoming.owner != retained.owner) Decision("reject", Rejection.invalidValue)
            else if (incoming.ownerRevision == retained.ownerRevision) {
                if (incoming == retained) Decision("duplicate") else Decision("reject", Rejection.invalidValue)
            } else {
                val invalid =
                    retained.healthOutcome == "unavailable" &&
                        (incoming.healthOutcome != "unavailable" || incoming.healthReason != retained.healthReason) ||
                        retained.healthOutcome == "notRequested" &&
                            incoming.healthOutcome !in listOf("notRequested", "discarded") ||
                        retained.healthOutcome == "discarded" && incoming.healthOutcome != "discarded" ||
                        retained.stopCutoff != null &&
                            (incoming.stopCutoff != retained.stopCutoff || incoming.timing != retained.timing) ||
                        (retained.stopCutoff != null || retained.phase in listOf("completed", "failed")) &&
                            incoming.phase in listOf("running", "paused", "preparing")
                if (invalid) Decision("reject", Rejection.invalidValue) else Decision("apply")
            }
        } catch (e: ProtocolException) {
            Decision("reject", e.reason)
        }
}
