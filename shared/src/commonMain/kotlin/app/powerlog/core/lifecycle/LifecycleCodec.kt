package app.powerlog.core.lifecycle

import app.powerlog.core.presentation.*
import app.powerlog.core.protocol.*

object LifecycleCodec {
    @Throws(IllegalArgumentException::class)
    fun encodeState(state: LifecycleState): String = JsonCodec.encode(stateJson(state))

    @Throws(IllegalArgumentException::class)
    fun decodeState(text: String): LifecycleState {
        val o = parse(text)
        o.closed("generation ride")
        return LifecycleState(
            decimal(o.required("generation"), maximum = MAX_REVISION),
            o["ride"].present()?.obj()?.let(::ride),
        )
    }

    @Throws(IllegalArgumentException::class)
    fun encodeIntent(intent: LifecycleIntent): String = JsonCodec.encode(intentJson(intent))

    @Throws(IllegalArgumentException::class) fun decodeIntent(text: String): LifecycleIntent = intent(parse(text))

    @Throws(IllegalArgumentException::class)
    fun encodeMutation(mutation: LifecycleMutation): String =
        JsonCodec.encode(
            obj(
                "state" to stateJson(mutation.state),
                "intent" to mutation.intent?.let(::intentJson),
                "events" to
                    Arr(
                        mutation.events.map { event ->
                            obj(
                                "eventId" to Str(event.eventId),
                                "key" to keyJson(event.key),
                                "action" to Str(event.action),
                                "timing" to event.timing.json(),
                                "interrupted" to Bool(event.interrupted),
                                "clockEpoch" to event.clockEpoch?.let(::Str),
                            )
                        }
                    ),
                "resetLive" to Bool(mutation.resetLive),
                "resetIntegration" to Bool(mutation.resetIntegration),
                "failedCommand" to mutation.failedCommand?.let(::intentJson),
            )
        )

    @Throws(IllegalArgumentException::class)
    fun encodeBatch(batch: CaptureBatch): String =
        JsonCodec.encode(
            obj(
                "originals" to Arr(batch.originals.map(::originalJson)),
                "fault" to batch.fault?.let(::faultJson),
            )
        )

    @Throws(IllegalArgumentException::class)
    fun decodeBatch(text: String): CaptureBatch {
        val o = parse(text)
        o.closed("originals", "fault")
        return CaptureBatch(
            o.required("originals").array().map { original(it.obj()) },
            o["fault"].present()?.obj()?.let(::fault),
        )
    }

    @Throws(IllegalArgumentException::class) fun decodeOwner(text: String): OwnerSnapshot = Schema.owner(parse(text))

    @Throws(IllegalArgumentException::class)
    fun decodeOwnerEvents(text: String): List<LifecycleOwnerEvent> =
        JsonCodec.parse(text, wireNumbers = false).array().map {
            val o = it.obj()
            o.closed("action timestamp", "commandId timing nativeEventId")
            LifecycleOwnerEvent(
                RideAction.valueOf(o.required("action").string()),
                Timestamps.parse(o.required("timestamp").string()),
                o["commandId"]?.let(::uuid),
                o["timing"]?.let(Schema::timing),
                o["nativeEventId"]?.let(::uuid),
            )
        }

    private fun stateJson(state: LifecycleState): Obj =
        Obj(
            mapOf(
                "generation" to Str(state.generation.toString()),
                "ride" to (state.ride?.let(::rideJson) ?: Nil),
            )
        )

    private fun rideJson(ride: LifecycleRide): Obj =
        obj(
            "key" to keyJson(ride.key),
            "options" to optionsJson(ride.options),
            "startedAt" to Str(ride.startedAt.original),
            "phase" to Str(ride.phase.name),
            "timing" to ride.timing.json(),
            "epoch" to Str(ride.epoch),
            "elapsedOriginUs" to ride.elapsedOriginUs?.toString()?.let(::Str),
            "runningOriginUs" to ride.runningOriginUs?.toString()?.let(::Str),
            "ownerSnapshot" to ride.ownerSnapshot?.json(),
            "pending" to ride.pending?.let(::intentJson),
            "terminal" to ride.terminal?.let(::intentJson),
            "recovery" to Str(ride.recovery.name),
            "uncertainEpoch" to Bool(ride.uncertainEpoch),
            "error" to ride.error?.let(::Str),
            "collectionClosed" to Bool(ride.collectionClosed),
            "deleted" to Bool(ride.deleted),
            "storageInterrupted" to Bool(ride.storageInterrupted),
            "ownerRecovery" to Str(ride.ownerRecovery.name),
            "nativeEventIds" to Arr(ride.nativeEventIds.map(::Str)),
        )

    private fun ride(o: Obj): LifecycleRide {
        o.closed(
            "key options startedAt phase timing epoch recovery uncertainEpoch collectionClosed deleted storageInterrupted ownerRecovery nativeEventIds",
            "elapsedOriginUs runningOriginUs ownerSnapshot pending terminal error",
        )
        return LifecycleRide(
            key(o.required("key").obj()),
            options(o.required("options").obj()),
            Timestamps.parse(o.required("startedAt").string()),
            LifecyclePhase.valueOf(o.required("phase").string()),
            Schema.timing(o.required("timing")),
            o.required("epoch").string(),
            o["elapsedOriginUs"]?.let { decimal(it) },
            o["runningOriginUs"]?.let { decimal(it) },
            o["ownerSnapshot"]?.let(Schema::owner),
            o["pending"]?.obj()?.let(::intent),
            o["terminal"]?.obj()?.let(::intent),
            LifecycleRecovery.valueOf(o.required("recovery").string()),
            o.required("uncertainEpoch").boolean(),
            o["error"]?.string(),
            o.required("collectionClosed").boolean(),
            o.required("deleted").boolean(),
            o.required("storageInterrupted").boolean(),
            LifecycleOwnerRecovery.valueOf(o.required("ownerRecovery").string()),
            o.required("nativeEventIds").array().map { it.string() },
        )
    }

    private fun optionsJson(options: FrozenRideOptions): Obj =
        obj(
            "indoor" to Bool(options.indoor),
            "useWatch" to Bool(options.useWatch),
            "saveToHealth" to Bool(options.saveToHealth),
            "recordGPS" to Bool(options.recordGPS),
            "sampleHz" to Num(options.sampleHz.toDouble()),
            "distanceSource" to Str(options.distanceSource),
            "batteryConsumptionSource" to Str(options.batteryConsumptionSource),
        )

    private fun options(o: Obj): FrozenRideOptions {
        o.closed("indoor useWatch saveToHealth recordGPS sampleHz distanceSource batteryConsumptionSource")
        return RideOptions.restore(
            o.required("indoor").boolean(),
            o.required("useWatch").boolean(),
            o.required("saveToHealth").boolean(),
            o.required("recordGPS").boolean(),
            o.required("sampleHz").number(),
            o.required("distanceSource").string(),
            o.required("batteryConsumptionSource").string(),
        )
    }

    private fun keyJson(key: LifecycleKey): Obj =
        obj(
            "rideId" to Str(key.rideId),
            "owner" to Str(key.owner.name),
            "generation" to Str(key.generation.toString()),
        )

    private fun key(o: Obj): LifecycleKey {
        o.closed("rideId owner generation")
        return LifecycleKey(
            uuid(o.required("rideId")),
            Role.valueOf(o.required("owner").string()),
            decimal(o.required("generation"), true, MAX_REVISION),
        )
    }

    private fun intentJson(intent: LifecycleIntent): Obj =
        obj(
            "commandId" to Str(intent.commandId),
            "key" to keyJson(intent.key),
            "action" to Str(intent.action.name),
            "timing" to intent.timing.json(),
            "options" to optionsJson(intent.options),
            "effect" to Str(intent.effect.name),
            "status" to Str(intent.status.name),
            "error" to intent.error?.let(::Str),
        )

    private fun intent(o: Obj): LifecycleIntent {
        o.closed("commandId key action timing options effect status", "error")
        return LifecycleIntent(
            uuid(o.required("commandId")),
            key(o.required("key").obj()),
            RideAction.valueOf(o.required("action").string()),
            Schema.timing(o.required("timing")),
            options(o.required("options").obj()),
            LifecycleEffectKind.valueOf(o.required("effect").string()),
            LifecycleIntentStatus.valueOf(o.required("status").string()),
            o["error"]?.string(),
        )
    }

    private fun membershipJson(value: CaptureMembership): Obj =
        obj(
            "collectionId" to Str(value.collectionId),
            "elapsedSeconds" to Num(value.elapsedSeconds),
            "generation" to Str(value.generation.toString()),
        )

    private fun membership(o: Obj): CaptureMembership {
        o.closed("collectionId elapsedSeconds generation")
        return CaptureMembership(
            uuid(o.required("collectionId")),
            o.required("elapsedSeconds").number(),
            decimal(o.required("generation"), true, MAX_REVISION),
        )
    }

    private fun originalJson(value: CaptureOriginal): Obj =
        obj(
            "observationId" to Str(value.observationId),
            "producer" to Str(value.producer),
            "sequence" to Str(value.sequence.toString()),
            "timestamp" to Str(value.timestamp.original),
            "clockEpoch" to Str(value.clockEpoch),
            "acquisitionUs" to Str(value.acquisitionUs.toString()),
            "connectionEpoch" to value.connectionEpoch?.let(::Str),
            "live" to value.live?.let(::membershipJson),
            "ride" to value.ride?.let(::membershipJson),
            "content" to Str(value.content),
        )

    private fun original(o: Obj): CaptureOriginal {
        o.closed(
            "observationId producer sequence timestamp clockEpoch acquisitionUs content",
            "connectionEpoch live ride",
        )
        return CaptureOriginal(
            uuid(o.required("observationId")),
            o.required("producer").string(),
            decimal(o.required("sequence"), true),
            Timestamps.parse(o.required("timestamp").string()),
            uuid(o.required("clockEpoch")),
            decimal(o.required("acquisitionUs")),
            o["connectionEpoch"]?.let(::uuid),
            o["live"]?.obj()?.let(::membership),
            o["ride"]?.obj()?.let(::membership),
            o.required("content").string(),
        )
    }

    private fun faultJson(value: CaptureFault): Obj =
        obj(
            "faultId" to Str(value.faultId),
            "key" to value.key?.let(::keyJson),
            "timestamp" to Str(value.timestamp.original),
            "observationId" to value.observationId?.let(::Str),
            "error" to Str(value.error),
        )

    private fun fault(o: Obj): CaptureFault {
        o.closed("faultId timestamp error", "key observationId")
        return CaptureFault(
            uuid(o.required("faultId")),
            o["key"]?.obj()?.let(::key),
            Timestamps.parse(o.required("timestamp").string()),
            o["observationId"]?.let(::uuid),
            o.required("error").string(),
        )
    }

    private fun parse(text: String): Obj =
        JsonCodec.parse(text, arrayLimit = 128, byteLimit = Int.MAX_VALUE, wireNumbers = false).obj()

    private fun Json?.present(): Json? = takeUnless { it == null || it == Nil }
}
