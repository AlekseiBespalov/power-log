package app.powerlog.core

object HealthIdentity {
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    fun validRide(ride: String): Boolean = uuid.matches(ride)
}
