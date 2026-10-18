package app.powerlog.bridge

import org.json.JSONObject

/** One append-only publication cursor per ride; never a log of individual provider mutations. */
internal data class AndroidHealthJob(
    val rideId: String,
    val pinRevision: Long,
    val phase: String = "seconds",
    val position: Long = 0,
    val status: String = "ready",
    val reason: String? = null,
    val written: Int = 0,
    val workoutId: String? = null,
    val resumeAtUs: Long? = null,
    val omittedComponents: Set<String> = emptySet(),
    val writtenComponents: Set<String> = emptySet(),
    val attempts: Int = 0,
) {
    init {
        require(phase in setOf("seconds", "distance", "workout", "done"))
        require(status in setOf("ready", "submitted", "saved", "failed", "uncertain"))
        require(position >= 0 && pinRevision >= 0 && written >= 0 && attempts >= 0)
    }

    fun encode(): String =
        JSONObject()
            .apply {
                put("rideId", rideId)
                put("pinRevision", pinRevision.toString())
                put("phase", phase)
                put("position", position.toString())
                put("status", status)
                put("reason", reason ?: JSONObject.NULL)
                put("written", written)
                put("workoutId", workoutId ?: JSONObject.NULL)
                put("resumeAtUs", resumeAtUs?.toString() ?: JSONObject.NULL)
                put("omittedComponents", org.json.JSONArray(omittedComponents.toList()))
                put("writtenComponents", org.json.JSONArray(writtenComponents.toList()))
                put("attempts", attempts)
            }
            .toString()

    companion object {
        fun decode(value: String): AndroidHealthJob =
            JSONObject(value).let { json ->
                AndroidHealthJob(
                    json.getString("rideId"),
                    json.getString("pinRevision").toLong(),
                    json.getString("phase"),
                    json.getString("position").toLong(),
                    json.getString("status"),
                    if (json.isNull("reason")) null else json.getString("reason"),
                    json.getInt("written"),
                    if (json.isNull("workoutId")) null else json.getString("workoutId"),
                    if (json.isNull("resumeAtUs")) null else json.getString("resumeAtUs").toLong(),
                    json.getJSONArray("omittedComponents").let { a ->
                        (0 until a.length()).map { a.getString(it) }.toSet()
                    },
                    json.getJSONArray("writtenComponents").let { a ->
                        (0 until a.length()).map { a.getString(it) }.toSet()
                    },
                    json.getInt("attempts"),
                )
            }
    }
}
