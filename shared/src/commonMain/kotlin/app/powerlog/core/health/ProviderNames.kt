package app.powerlog.core.health

object ProviderNames {
    fun displayName(provider: Provider): String =
        when (provider) {
            Provider.appleHealth -> "Apple Health"
            Provider.healthConnect -> "Health Connect"
        }

    fun fromWire(value: String?): String =
        Provider.entries.firstOrNull { it.name == value }?.let(::displayName) ?: "Health"

    fun label(source: String, descriptors: List<MeasurementSource>): String =
        displayName(descriptors.firstOrNull { it.source.name == source }?.provider ?: Provider.appleHealth)
}
