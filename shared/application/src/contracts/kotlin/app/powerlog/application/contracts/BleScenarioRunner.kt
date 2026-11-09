package app.powerlog.application.contracts

import app.powerlog.application.port.Advertisement
import app.powerlog.application.port.BleCentral
import app.powerlog.application.port.BleEvents
import app.powerlog.application.port.GattCharacteristic
import app.powerlog.application.port.GattReady
import app.powerlog.application.port.GattService
import app.powerlog.application.port.PortError
import app.powerlog.application.port.RadioState
import app.powerlog.domain.core.controller.ControllerFrame
import app.powerlog.domain.core.controller.ControllerRequest
import app.powerlog.domain.core.controller.TransportProfile
import app.powerlog.domain.core.failure.PlatformCode
import app.powerlog.domain.core.time.ClockReading
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull

data class BleOsCall(val name: String, val bike: String)

interface BleScenarioHarness {
    val central: BleCentral

    val osCalls: List<BleOsCall>
        get() = error("this harness records no OS calls")

    fun os(occurrence: String, args: BleOsArgs)

    fun drain()

    fun frame(request: ControllerRequest): ControllerFrame
}

class BleOsArgs(private val args: JsonObject) {
    val names: Set<String>
        get() = args.keys

    fun text(name: String): String = args.text(name)

    fun textOrNull(name: String): String? = args.textOrNull(name)

    fun long(name: String): Long = args.long(name)

    fun intOrNull(name: String): Int? = args.intOrNull(name)

    fun flag(name: String): Boolean = args.flag(name)

    fun texts(name: String): List<String> = args.texts(name)

    fun bytes(name: String): ByteArray = args.bytes(name)

    fun bytesOrNull(name: String): ByteArray? = args.bytesOrNull(name)

    fun platformCodeOrNull(name: String): PlatformCode? = args.platformCodeOrNull(name)

    fun gattServices(name: String): List<GattService> = args.gattServices(name)

    override fun toString(): String = args.toString()
}

class BleScenario(val name: String, val platforms: List<String>, val steps: List<JsonObject>)

data class BleScenarioResult(val scenario: String, val platform: String, val failure: String?)

class BleScenarioRunner(document: String) {
    private val root = Json.parseToJsonElement(document).jsonObject
    private val vocabulary: Map<String, Set<String>> =
        root.getValue("os").jsonObject.mapValues { (_, names) -> names.jsonArray.map { it.text() }.toSet() }
    private val callVocabulary: Set<String> = root["osCalls"]?.jsonArray?.map { it.text() }?.toSet().orEmpty()

    val profiles: Map<String, TransportProfile> =
        root.getValue("profiles").jsonObject.mapValues { (_, profile) ->
            val fields = profile.jsonObject
            TransportProfile(
                service = fields.text("service"),
                notify = fields.text("notify"),
                write = fields.text("write"),
                namePrefixes = fields.getValue("namePrefixes").jsonArray.map { it.text() },
            )
        }

    val scenarios: List<BleScenario> =
        root.getValue("scenarios").jsonArray.map { element ->
            val fields = element.jsonObject
            BleScenario(
                name = fields.text("name"),
                platforms = fields.getValue("platforms").jsonArray.map { it.text() },
                steps = fields.getValue("steps").jsonArray.map { it.jsonObject },
            )
        }

    fun run(platform: String, harness: (BleScenario) -> BleScenarioHarness): List<BleScenarioResult> =
        scenarios
            .filter { platform in it.platforms }
            .map { scenario ->
                val failure =
                    try {
                        Replay(harness(scenario)).play(scenario.steps)
                    } catch (failure: Throwable) {
                        "${failure::class.simpleName}: ${failure.message}"
                    }
                BleScenarioResult(scenario.name, platform, failure)
            }

    private inner class Replay(private val harness: BleScenarioHarness) {
        private val recorder = Recorder()
        private var checkedCalls = 0

        fun play(steps: List<JsonObject>): String? {
            harness.central.attach(recorder)
            steps.forEachIndexed { index, step ->
                val failure = step(step)
                if (failure != null) return "step ${index + 1} $step: $failure"
                recorder.reentered?.let {
                    return "step ${index + 1} $step: $it"
                }
            }
            return null
        }

        private fun step(step: JsonObject): String? {
            val args = step["args"]?.jsonObject ?: JsonObject(emptyMap())
            return when {
                "call" in step -> {
                    val returned = recorder.during { invoke(step.text("call"), args) }
                    harness.drain()
                    step["returns"]?.let { expected -> mismatch(expected, returned) }
                }
                "os" in step -> {
                    val occurrence = step.text("os")
                    val allowed = vocabulary[occurrence] ?: return "unknown OS occurrence"
                    val unknown = args.keys - allowed
                    if (unknown.isNotEmpty()) return "arguments $unknown aren't in the vocabulary of $occurrence"
                    harness.os(occurrence, BleOsArgs(args))
                    harness.drain()
                    null
                }
                "expect" in step -> {
                    harness.drain()
                    val event = recorder.events.removeFirstOrNull() ?: return "nothing arrived"
                    val name = step.text("expect")
                    if (event.first != name) "got ${event.first} ${event.second}"
                    else mismatch(args, event.second)?.let { "$it in ${event.second}" }
                }
                "expectOs" in step || "expectNoOs" in step -> {
                    harness.drain()
                    val wanted = step["expectOs"] ?: step.getValue("expectNoOs")
                    val name = wanted.text()
                    if (name !in callVocabulary) return "unknown OS call $name"
                    val bike = args.textOrNull("bike")
                    val calls = harness.osCalls
                    val found =
                        (checkedCalls until calls.size).firstOrNull {
                            calls[it].name == name && (bike == null || calls[it].bike == bike)
                        }
                    when {
                        "expectOs" in step && found == null ->
                            "no OS call $name $args after ${calls.take(checkedCalls)}: ${calls.drop(checkedCalls)}"
                        "expectOs" in step -> {
                            checkedCalls = found!! + 1
                            null
                        }
                        found != null -> "unexpected OS call ${calls[found]}"
                        else -> {
                            checkedCalls = calls.size
                            null
                        }
                    }
                }
                step["expectNone"]?.jsonPrimitive?.booleanOrNull == true -> {
                    harness.drain()
                    recorder.events.takeIf { it.isNotEmpty() }?.let { "unexpected ${it.toList()}" }
                }
                else -> "unknown step"
            }
        }

        private fun invoke(command: String, args: JsonObject): Any? {
            val central = harness.central
            return when (command) {
                "prepare" -> central.prepare()
                "radio" -> central.radio()
                "startSearch" ->
                    central.startSearch(
                        args.getValue("profiles").jsonArray.map { profile(it.text()) },
                        args.flag("inventory"),
                    )
                "stopSearch" -> central.stopSearch()
                "connect" -> central.connect(args.text("bike"), profile(args.text("profile")), args.flag("adopt"))
                "ready" -> central.ready(args.long("link"), args.flag("inventory"))
                "send" ->
                    central.send(args.long("link"), harness.frame(ControllerRequest.valueOf(args.text("request"))))
                "cancel" -> central.cancel(args.long("link"))
                "release" -> central.release(args.long("link"))
                else -> error("unknown command $command")
            }
        }

        private fun profile(name: String): TransportProfile = profiles[name] ?: error("unknown profile $name")
    }

    private class Recorder : BleEvents {
        val events = ArrayDeque<Pair<String, Map<String, Any?>>>()
        var reentered: String? = null
        private var calling: String? = null

        fun during(block: () -> Any?): Any? {
            calling = "a command"
            try {
                return block()
            } finally {
                calling = null
            }
        }

        private fun record(name: String, vararg fields: Pair<String, Any?>) {
            if (calling != null && reentered == null) reentered = "$name arrived inside the command that caused it"
            events.addLast(name to mapOf(*fields))
        }

        override fun radioChanged(state: RadioState) = record("radioChanged", "state" to state)

        override fun advertised(advertisement: Advertisement) =
            record("advertised", "advertisement" to advertisement.tree())

        override fun searchEnded(error: PortError?) = record("searchEnded", "error" to error?.tree())

        override fun linkUp(link: Long, bike: String) = record("linkUp", "link" to link, "bike" to bike)

        override fun linkFailed(link: Long, error: PortError) =
            record("linkFailed", "link" to link, "error" to error.tree())

        override fun linkDown(link: Long, error: PortError?, systemReconnecting: Boolean) =
            record("linkDown", "link" to link, "error" to error?.tree(), "systemReconnecting" to systemReconnecting)

        override fun linkReady(link: Long, result: GattReady) =
            record("linkReady", "link" to link, "result" to result.tree())

        override fun servicesInvalidated(link: Long) = record("servicesInvalidated", "link" to link)

        override fun canSend(link: Long) = record("canSend", "link" to link)

        override fun notified(link: Long, bytes: ByteArray, at: ClockReading) =
            record("notified", "link" to link, "bytes" to bytes, "at" to at.tree())

        override fun notificationFailed(link: Long, error: PortError) =
            record("notificationFailed", "link" to link, "error" to error.tree())

        override fun writeFailed(link: Long, error: PortError) =
            record("writeFailed", "link" to link, "error" to error.tree())
    }
}

private fun Advertisement.tree(): Map<String, Any?> =
    mapOf(
        "bike" to bike,
        "name" to name,
        "rssi" to rssi,
        "services" to services,
        "manufacturerData" to manufacturerData,
        "at" to at.tree(),
    )

private fun GattReady.tree(): Map<String, Any?> =
    mapOf(
        "outcome" to outcome,
        "maxWriteBytes" to maxWriteBytes,
        "inventory" to inventory?.map { it.tree() },
        "error" to error?.tree(),
    )

private fun GattService.tree(): Map<String, Any?> =
    mapOf(
        "uuid" to uuid,
        "characteristics" to characteristics.map { mapOf("uuid" to it.uuid, "properties" to it.properties) },
    )

private fun PortError.tree(): Map<String, Any?> = mapOf("kind" to kind, "platform" to platform?.tree())

private fun PlatformCode.tree(): Map<String, Any?> = mapOf("domain" to domain, "code" to code)

private fun ClockReading.tree(): Map<String, Any?> = mapOf("monoUs" to monoUs, "utcUs" to utcUs)

@OptIn(ExperimentalEncodingApi::class)
private fun mismatch(expected: JsonElement, actual: Any?): String? =
    when {
        expected is JsonNull -> if (actual == null) null else "expected null, got ${shown(actual)}"
        actual == null -> "expected $expected, got null"
        expected is JsonObject -> {
            val fields = actual as? Map<*, *> ?: return "expected an object, got ${shown(actual)}"
            expected.entries.firstNotNullOfOrNull { (key, value) ->
                if (key !in fields) "no field $key" else mismatch(value, fields[key])?.let { "$key: $it" }
            }
        }
        expected is JsonArray -> {
            val items = actual as? List<*> ?: return "expected an array, got ${shown(actual)}"
            if (items.size != expected.size) "expected ${expected.size} items, got ${items.size}"
            else
                expected.indices.firstNotNullOfOrNull { index ->
                    mismatch(expected[index], items[index])?.let { "[$index] $it" }
                }
        }
        actual is ByteArray ->
            if (Base64.decode(expected.text()).contentEquals(actual)) null
            else "expected bytes $expected, got ${shown(actual)}"
        actual is Boolean -> if (expected.jsonPrimitive.boolean == actual) null else "expected $expected, got $actual"
        actual is Int || actual is Long ->
            if (expected.jsonPrimitive.longOrNull == (actual as Number).toLong()) null
            else "expected $expected, got $actual"
        actual is Enum<*> -> if (expected.text() == actual.name) null else "expected $expected, got ${actual.name}"
        actual is String -> if (expected.text() == actual) null else "expected $expected, got \"$actual\""
        else -> "can't compare $expected with ${shown(actual)}"
    }

@OptIn(ExperimentalEncodingApi::class)
private fun shown(value: Any?): String =
    when (value) {
        is ByteArray -> "bytes ${Base64.encode(value)}"
        else -> value.toString()
    }

private fun JsonElement.text(): String {
    val primitive = jsonPrimitive
    require(primitive.isString) { "expected a string, got $this" }
    return primitive.content
}

private fun JsonObject.text(name: String): String = getValue(name).text()

private fun JsonObject.textOrNull(name: String): String? = this[name]?.takeUnless { it is JsonNull }?.text()

private fun JsonObject.long(name: String): Long = getValue(name).jsonPrimitive.long

private fun JsonObject.intOrNull(name: String): Int? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.int

private fun JsonObject.flag(name: String): Boolean = this[name]?.jsonPrimitive?.boolean ?: false

private fun JsonObject.texts(name: String): List<String> = getValue(name).jsonArray.map { it.text() }

@OptIn(ExperimentalEncodingApi::class) private fun JsonObject.bytes(name: String): ByteArray = Base64.decode(text(name))

@OptIn(ExperimentalEncodingApi::class)
private fun JsonObject.bytesOrNull(name: String): ByteArray? = textOrNull(name)?.let { Base64.decode(it) }

private fun JsonObject.platformCodeOrNull(name: String): PlatformCode? =
    this[name]?.takeUnless { it is JsonNull }?.jsonObject?.let { PlatformCode(it.text("domain"), it.text("code")) }

private fun JsonObject.gattServices(name: String): List<GattService> =
    getValue(name).jsonArray.map { element ->
        val service = element.jsonObject
        GattService(
            uuid = service.text("uuid"),
            characteristics =
                service.getValue("characteristics").jsonArray.map {
                    GattCharacteristic(
                        it.jsonObject.text("uuid"),
                        it.jsonObject.getValue("properties").jsonPrimitive.int,
                    )
                },
        )
    }
