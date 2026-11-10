package app.powerlog.data

import app.powerlog.application.store.SettingsLoad
import app.powerlog.domain.core.analysis.battery.BatteryMethod
import app.powerlog.domain.core.analysis.distance.DistanceSelection
import app.powerlog.domain.core.metric.TemperatureUnit
import app.powerlog.domain.core.metric.UnitSystem
import app.powerlog.domain.core.ride.FitPowerSource
import app.powerlog.domain.core.ride.SampleRate
import app.powerlog.domain.settings.AppSettingsDraft
import app.powerlog.domain.settings.ChartRange
import app.powerlog.domain.settings.RideSettingsDraft
import app.powerlog.domain.settings.SettingsDocument
import app.powerlog.domain.settings.SettingsDraft
import app.powerlog.domain.settings.ViewId
import app.powerlog.domain.settings.ViewPresetDraft
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

internal object SettingsCodec {
    private val json = Json { explicitNulls = false }
    private val sampleRate = Vocabulary(SampleRate.serializer(), SampleRate.entries)
    private val distance = Vocabulary(DistanceSelection.serializer(), DistanceSelection.entries)
    private val battery = Vocabulary(BatteryMethod.serializer(), BatteryMethod.entries)
    private val units = Vocabulary(UnitSystem.serializer(), UnitSystem.entries)
    private val temperature = Vocabulary(TemperatureUnit.serializer(), TemperatureUnit.entries)
    private val fitPower = Vocabulary(FitPowerSource.serializer(), FitPowerSource.entries)
    private val view = Vocabulary(ViewId.serializer(), ViewId.entries)
    private val range = Vocabulary(ChartRange.serializer(), ChartRange.entries)

    fun encode(document: SettingsDocument): String = json.encodeToString(SettingsDocument.serializer(), document)

    fun decode(text: String?): SettingsLoad {
        if (text == null) return SettingsLoad.Missing
        val root =
            try {
                Json.parseToJsonElement(text)
            } catch (_: IllegalArgumentException) {
                return SettingsLoad.Unreadable
            }
        if (root !is JsonObject) return SettingsLoad.Unreadable
        return SettingsLoad.Loaded(
            SettingsDraft(ride = root.obj("ride")?.let(::ride), app = root.obj("app")?.let(::app))
        )
    }

    private fun ride(fields: JsonObject) =
        RideSettingsDraft(
            sampleRate = fields.text("sampleRate")?.let(sampleRate::valueOrNull),
            distance = fields.text("distance")?.let(distance::valueOrNull),
            battery = fields.text("battery")?.let(battery::valueOrNull),
            indoor = fields.flag("indoor"),
            useWatch = fields.flag("useWatch"),
            saveToHealth = fields.flag("saveToHealth"),
            recordGps = fields.flag("recordGps"),
            syncBeeps = fields.flag("syncBeeps"),
        )

    private fun app(fields: JsonObject) =
        AppSettingsDraft(
            units = fields.text("units")?.let(units::valueOrNull),
            temperature = fields.text("temperature")?.let(temperature::valueOrNull),
            fitPower = fields.text("fitPower")?.let(fitPower::valueOrNull),
            views = fields.obj("views")?.let(::views),
            activeView = fields.text("activeView")?.let(view::valueOrNull),
            historyView = fields.text("historyView")?.let(view::valueOrNull),
            historyRange = fields.text("historyRange")?.let(range::valueOrNull),
        )

    private fun views(fields: JsonObject): Map<ViewId, ViewPresetDraft> =
        fields.entries
            .mapNotNull { (name, value) ->
                val id = view.valueOrNull(name) ?: return@mapNotNull null
                val preset = value as? JsonObject ?: return@mapNotNull null
                id to
                    ViewPresetDraft(
                        numbers = preset.texts("numbers"),
                        charts = preset.texts("charts"),
                        range = preset.text("range")?.let(range::valueOrNull),
                        webColumns = preset.number("webColumns"),
                    )
            }
            .toMap()

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.primitive(key: String): JsonPrimitive? = this[key] as? JsonPrimitive

    private fun JsonObject.text(key: String): String? = primitive(key)?.takeIf { it.isString }?.content

    private fun JsonObject.flag(key: String): Boolean? = primitive(key)?.takeUnless { it.isString }?.booleanOrNull

    private fun JsonObject.number(key: String): Int? = primitive(key)?.takeUnless { it.isString }?.intOrNull

    private fun JsonObject.texts(key: String): List<String>? {
        val items = this[key] as? JsonArray ?: return null
        return items.map { item -> item.textOrNull() ?: return null }
    }

    private fun JsonElement.textOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
}
