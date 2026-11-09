package app.powerlog.application.slice

import app.powerlog.application.api.LaunchReason
import app.powerlog.application.port.LocationProviderState
import app.powerlog.application.port.PortErrorKind
import app.powerlog.application.port.RadioState
import app.powerlog.application.port.ThermalLevel
import app.powerlog.application.store.LogLine
import app.powerlog.application.store.LogValue
import app.powerlog.application.store.StorageError
import app.powerlog.domain.core.controller.ControllerRequest
import app.powerlog.domain.core.controller.GattResult
import app.powerlog.domain.core.failure.FailureCode
import app.powerlog.domain.core.failure.RefusalDetail
import app.powerlog.domain.core.health.HealthOutcome
import app.powerlog.domain.core.health.HealthProviderKind
import app.powerlog.domain.core.health.HealthReason
import app.powerlog.domain.core.ride.RidePhase
import app.powerlog.domain.recording.CommandIssuer
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object LogWhitelist {
    private val enums: Map<String, Set<String>> =
        mapOf(
            "LaunchReason" to LaunchReason.entries.names(),
            "RefusalDetail" to RefusalDetail.entries.names(),
            "GattResult" to GattResult.entries.names(),
            "ControllerRequest" to ControllerRequest.entries.names(),
            "LocationProviderState" to LocationProviderState.entries.names(),
            "FailureCode" to FailureCode.entries.names(),
            "RadioState" to RadioState.entries.names(),
            "ThermalLevel" to ThermalLevel.entries.names(),
            "StorageErrorKind" to StorageError.Kind.entries.names(),
            "RidePhase" to RidePhase.entries.names(),
            "CommandIssuer" to CommandIssuer.entries.names(),
            "HealthOutcome" to HealthOutcome.entries.names(),
            "HealthReason" to HealthReason.entries.names(),
            "HealthProviderKind" to HealthProviderKind.entries.names(),
            "PortErrorKind" to PortErrorKind.entries.names(),
        )

    private val events: JsonObject by lazy {
        val root = File(requireNotNull(System.getProperty("powerlog.repositoryRoot")))
        val fixture = File(root, "tests/fixtures/contract/telemetry/diagnostic-log.json")
        val whitelist = Json.parseToJsonElement(fixture.readText()).jsonObject
        requireNotNull(whitelist["v2"]).jsonObject.getValue("events").jsonObject
    }

    fun violations(lines: List<LogLine>): List<String> = lines.flatMap { line ->
        val fields = events[line.event]?.jsonObject ?: return@flatMap listOf("unknown event ${line.event}")
        line.fields.mapNotNull { field ->
            val type = fields[field.key] ?: return@mapNotNull "${line.event}.${field.key} is not whitelisted"
            "${line.event}.${field.key} = ${field.value}".takeUnless { allowed(type, field.value) }
        }
    }

    private fun allowed(type: kotlinx.serialization.json.JsonElement, value: LogValue): Boolean =
        when (type) {
            is JsonArray ->
                type.any {
                    val allowed = it.jsonPrimitive
                    when (value) {
                        is LogValue.Text -> allowed.isString && allowed.content == value.value
                        is LogValue.Integer -> !allowed.isString && allowed.content == value.value.toString()
                        else -> false
                    }
                }
            is JsonPrimitive ->
                type.content.split('|').any { kind ->
                    when {
                        kind == "integer" -> value is LogValue.Integer
                        kind == "decimal" -> value is LogValue.Decimal || value is LogValue.Integer
                        kind == "flag" -> value is LogValue.Flag
                        kind == "text" -> value is LogValue.Text
                        kind == "texts" -> value is LogValue.Texts
                        kind == "object" -> value is LogValue.Fields
                        kind == "platform" ->
                            value is LogValue.Fields && value.fields.map { it.key }.toSet() == setOf("domain", "code")
                        kind.startsWith("enum:") ->
                            value is LogValue.Text && value.value in requireNotNull(enums[kind.removePrefix("enum:")])
                        else -> value is LogValue.Text && value.value == kind
                    }
                }
            else -> false
        }

    private fun <T : Enum<T>> List<T>.names(): Set<String> = mapTo(mutableSetOf()) { it.name }
}
