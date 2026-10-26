package app.powerlog.core.analytics

import kotlin.random.Random

internal sealed class RideInput(val time: Long, val channel: Int) {
    class Distance(val startUs: Long, val endUs: Long, val meters: Double, val speed0: Double?, val speed1: Double?) :
        RideInput(endUs, 0)

    class Battery(
        val startUs: Long,
        val endUs: Long,
        val wattHours: Double,
        val rate0: Double?,
        val rate1: Double?,
        val indivisible: Boolean,
        val timestamp: String,
    ) : RideInput(endUs, 1)

    class Speed(val timeUs: Long, val mps: Double?, val accuracy: Double?, val active: Int?) : RideInput(timeUs, 2)

    class Telemetry(
        val timeUs: Long,
        val watts: Double?,
        val batteryWatts: Double?,
        val speedMps: Double?,
        val active: Int?,
        val continuity: String?,
        val timestamp: String,
    ) : RideInput(timeUs, 3)

    fun feed(query: EnergyPerDistanceQuery) =
        when (this) {
            is Distance -> query.appendDistance(startUs, endUs, meters, speed0, speed1)
            is Battery -> query.appendBattery(startUs, endUs, wattHours, rate0, rate1, indivisible, timestamp)
            is Speed -> query.appendSpeed(timeUs, mps, accuracy, active)
            is Telemetry -> query.appendTelemetry(timeUs, watts, batteryWatts, speedMps, active, continuity, timestamp)
        }

    fun feed(read: EnergyPerDistanceSeriesRead) =
        when (this) {
            is Distance -> read.appendDistance(startUs, endUs, meters, speed0, speed1)
            is Battery -> read.appendBattery(startUs, endUs, wattHours, rate0, rate1, indivisible, timestamp)
            is Speed -> read.appendSpeed(timeUs, mps, accuracy, active)
            is Telemetry -> read.appendTelemetry(timeUs, watts, batteryWatts, speedMps, active, continuity, timestamp)
        }
}

internal object EnergyRides {
    fun stamp(timeUs: Long): String {
        val seconds = timeUs / 1_000_000
        fun digits(value: Long) = value.toString().padStart(2, '0')
        return "2026-01-01T${digits(seconds / 3600)}:${digits(seconds / 60 % 60)}:${digits(seconds % 60)}Z"
    }

    fun synthetic(random: Random, seconds: Int, battery: Boolean = random.nextInt(6) != 0): List<RideInput> {
        val rideEndUs = seconds * 1_000_000L
        val inputs = mutableListOf<RideInput>()
        val activity = mutableListOf<Pair<Long, Int?>>()
        var boundary = 0L
        var interval = 0
        while (boundary <= rideEndUs) {
            activity += boundary to interval++
            boundary += random.nextLong(5_000_000, 90_000_000)
            if (random.nextInt(3) == 0) {
                activity += boundary to null
                boundary += random.nextLong(1_000_000, 20_000_000)
            }
        }
        fun active(time: Long) = activity.last { it.first <= time }.second
        val reconnects = List(seconds / 20 + 1) { random.nextLong(0, rideEndUs + 1) }

        val period = longArrayOf(125_000, 250_000, 500_000, 1_000_000)[random.nextInt(4)]
        val riderPower = random.nextInt(6) != 0
        var speed = random.nextDouble(0.0, 12.0)
        var time = 0L
        while (time <= rideEndUs) {
            speed = (speed + random.nextDouble(-1.5, 1.5)).coerceIn(0.0, 15.0)
            val watts =
                when {
                    !riderPower || random.nextInt(25) == 0 -> null
                    random.nextInt(50) == 0 -> -5.0
                    else -> random.nextDouble(0.0, 450.0)
                }
            val batteryWatts =
                when (random.nextInt(25)) {
                    0 -> null
                    1 -> -80.0
                    else -> random.nextDouble(0.0, 1500.0)
                }
            val controllerSpeed =
                when (random.nextInt(30)) {
                    0 -> null
                    1 -> 0.0
                    2 -> 45.0
                    else -> speed
                }
            val continuity =
                when (random.nextInt(40)) {
                    0 -> null
                    1 -> ""
                    else -> "clock|${reconnects.count { it <= time }}"
                }
            inputs +=
                RideInput.Telemetry(time, watts, batteryWatts, controllerSpeed, active(time), continuity, stamp(time))
            time +=
                if (random.nextInt(150) == 0) random.nextLong(3_000_000, 30_000_000)
                else period + random.nextLong(-period / 4, period / 4 + 1)
        }

        if (battery) {
            val counter = random.nextInt(3)
            var last = 0L
            while (true) {
                val start = last + if (random.nextInt(30) == 0) random.nextLong(1, 25_000_000) else 0
                val end = start + if (random.nextInt(40) == 0) 0 else random.nextLong(100_000, 2_500_001)
                if (end > rideEndUs) break
                val indivisible = if (counter == 2) random.nextBoolean() else counter == 0
                val wattHours = random.nextDouble(0.0, 0.6)
                val rate0 = if (indivisible) null else random.nextDouble(-100.0, 1800.0)
                val rate1 = if (indivisible) null else random.nextDouble(-100.0, 1800.0)
                inputs += RideInput.Battery(start, end, wattHours, rate0, rate1, indivisible, stamp(end))
                last = end
            }
        }

        val controllerDistance = random.nextBoolean()
        var distanceEnd = 0L
        while (true) {
            val start = distanceEnd + if (random.nextInt(25) == 0) random.nextLong(1, 30_000_000) else 0
            val end =
                start +
                    if (controllerDistance) random.nextLong(50_000, 3_000_001) else random.nextLong(500_000, 10_000_001)
            if (end > rideEndUs) break
            val meters = random.nextDouble(0.0, 12.0) * (end - start) / 1e6
            val speed0 = if (controllerDistance && random.nextInt(20) != 0) random.nextDouble(0.0, 15.0) else null
            val speed1 = speed0?.let { random.nextDouble(0.0, 15.0) }
            inputs += RideInput.Distance(start, end, meters, speed0, speed1)
            distanceEnd = end
        }

        var gpsTime = random.nextLong(0, 1_000_000)
        while (gpsTime <= rideEndUs) {
            val mps = if (random.nextInt(20) == 0) null else random.nextDouble(0.0, 15.0)
            val accuracy =
                when (random.nextInt(20)) {
                    0 -> null
                    1 -> -1.0
                    else -> random.nextDouble(0.1, 3.0)
                }
            inputs += RideInput.Speed(gpsTime, mps, accuracy, active(gpsTime))
            gpsTime +=
                random.nextLong(800_000, 1_200_000) +
                    if (random.nextInt(60) == 0) random.nextLong(2_000_000, 15_000_000) else 0
        }
        return inputs.sortedWith(compareBy<RideInput>({ it.time }, { it.channel }))
    }

    fun steady(seconds: Int): List<RideInput> = buildList {
        for (quarter in 0..seconds * 4) {
            val time = quarter * 250_000L
            val timestamp = stamp(time)
            if (quarter > 0 && quarter % 16 == 0) add(RideInput.Distance(time - 4_000_000, time, 20.0, null, null))
            if (quarter > 0 && quarter % 4 == 0) {
                add(RideInput.Battery(time - 1_000_000, time, 0.25, null, null, true, timestamp))
                add(RideInput.Speed(time, 5.0, 0.5, 0))
            }
            add(RideInput.Telemetry(time, 150.0 + quarter % 7, 900.0 + quarter % 11, 5.0, 0, "clock|0", timestamp))
        }
    }
}
