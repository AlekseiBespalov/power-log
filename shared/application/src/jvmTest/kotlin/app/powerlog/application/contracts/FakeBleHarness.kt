package app.powerlog.application.contracts

import app.powerlog.application.fakes.FakeBleCentral
import app.powerlog.application.fakes.FakeClock
import app.powerlog.application.fakes.FakePortQueue
import app.powerlog.application.port.RadioState
import app.powerlog.domain.core.controller.ControllerFrame
import app.powerlog.domain.core.controller.ControllerRequest
import app.powerlog.domain.core.ride.Platform
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class FakeBleHarness(platform: Platform) : BleScenarioHarness {
    private val clock = FakeClock()
    private val queue = FakePortQueue()

    override val central: FakeBleCentral = FakeBleCentral(platform, clock, queue::post)

    override val osCalls: List<BleOsCall>
        get() =
            central.osCalls.mapNotNull { call ->
                val words = call.split(" ")
                when (words.first()) {
                    "connect" -> BleOsCall("connect", words.last())
                    "cancel",
                    "disconnect" -> BleOsCall("cancel", words.last())
                    else -> null
                }
            }

    override fun os(occurrence: String, args: BleOsArgs) {
        val os = central.os
        when (occurrence) {
            "radio" -> os.radio(RadioState.valueOf(args.text("state")))
            "remembered" -> os.remembered(args.text("bike"))
            "restored" -> os.restored(args.text("bike"), FakeBleCentral.Restored.valueOf(args.text("state")))
            "discovered" -> {
                clock.advanceTo(args.long("atMonoUs"))
                os.discovered(
                    bike = args.text("bike"),
                    name = args.textOrNull("name"),
                    rssi = args.intOrNull("rssi"),
                    services = args.texts("services"),
                    manufacturerData = args.bytesOrNull("manufacturerData"),
                )
            }
            "scanFailed" -> os.scanFailed(args.text("code"))
            "chooserCancelled" -> os.chooserCancelled()
            "noActivation" -> os.noActivation()
            "connected" -> os.connected(args.text("bike"))
            "connectFailed" -> os.connectFailed(args.text("bike"), args.platformCodeOrNull("platform"))
            "disconnected" ->
                os.disconnected(args.text("bike"), args.flag("systemReconnecting"), args.platformCodeOrNull("platform"))
            "database" ->
                os.database(
                    bike = args.text("bike"),
                    services = args.gattServices("services"),
                    maxWriteBytes = args.intOrNull("maxWriteBytes"),
                    notifyFails = args.flag("notifyFails"),
                )
            "servicesModified" -> os.servicesModified(args.text("bike"))
            "notify" -> {
                clock.advanceTo(args.long("atMonoUs"))
                os.notify(args.bytes("bytes"))
            }
            "notifyError" -> os.notifyError(args.platformCodeOrNull("platform"))
            "writeBusy" -> os.writeBusy()
            "writeReady" -> os.writeReady()
            "writeError" -> os.writeError(args.platformCodeOrNull("platform"))
            else -> error("the fake has no OS occurrence $occurrence")
        }
    }

    override fun drain() {
        queue.drain()
    }

    override fun frame(request: ControllerRequest): ControllerFrame = frameOf(request)

    companion object {
        private val requests =
            Json.parseToJsonElement(File("../../tests/fixtures/protocol.json").readText())
                .jsonObject
                .getValue("requests")
                .jsonObject

        @OptIn(ExperimentalStdlibApi::class)
        fun bytesOf(request: ControllerRequest): ByteArray {
            val name =
                when (request) {
                    ControllerRequest.identity -> "identity"
                    ControllerRequest.telemetry -> "selective"
                }
            return requests.getValue(name).jsonPrimitive.content.hexToByteArray()
        }

        // ControllerDecoders.all is not built yet and ControllerFrame's constructor is internal to domain; its JVM
        // constructor is public, so reflection reaches it for the two request frames.
        fun frameOf(request: ControllerRequest): ControllerFrame =
            ControllerFrame::class.java.getConstructor(ByteArray::class.java).newInstance(bytesOf(request))
    }
}
