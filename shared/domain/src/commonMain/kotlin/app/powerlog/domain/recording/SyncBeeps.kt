package app.powerlog.domain.recording

import app.powerlog.domain.core.ride.RideAction
import app.powerlog.domain.core.ride.RidePhase
import app.powerlog.domain.core.time.ClockReading
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlinx.serialization.Serializable

@Serializable
public enum class CueAction {
    start,
    resume,
    finish,
}

public class Tone(public val atMonoUs: Long, public val samples: FloatArray, public val sampleRateHz: Int)

public object SyncBeeps {
    public const val sampleRateHz: Int = 48_000
    public const val startLeadUs: Long = 3_500_000L
    public const val controlLeadUs: Long = 1_000_000L
    public const val finishRequestLeadUs: Long = 450_000L
    public const val testLeadUs: Long = 300_000L

    private const val SECOND_US = 1_000_000L
    private const val PIP_HZ = 1_000
    private const val PIP_US = 100_000L
    private const val SYNC_HZ = 2_000
    private const val SYNC_US = 200_000L
    private const val RAMP_US = 1_000L
    private const val LEVEL_DB = -1.0

    private val pip: FloatArray by lazy { pcm(PIP_HZ, PIP_US) }
    private val sync: FloatArray by lazy { pcm(SYNC_HZ, SYNC_US) }

    public fun startAt(press: ClockReading): ClockReading {
        val utcUs = ceilDiv(press.utcUs + startLeadUs, SECOND_US) * SECOND_US
        return ClockReading(press.monoUs + (utcUs - press.utcUs), utcUs)
    }

    public fun controlAt(press: ClockReading, originMonoUs: Long): ClockReading {
        val monoUs = originMonoUs + ceilDiv(press.monoUs + controlLeadUs - originMonoUs, SECOND_US) * SECOND_US
        return ClockReading(monoUs, press.utcUs + (monoUs - press.monoUs))
    }

    public fun cued(phase: RidePhase, action: RideAction): Boolean =
        phase == RidePhase.paused && action == RideAction.resume ||
            phase == RidePhase.running && action == RideAction.finish

    public fun requestAt(action: CueAction, atMonoUs: Long): Long =
        if (action == CueAction.finish) atMonoUs - finishRequestLeadUs else atMonoUs

    public fun startTones(atMonoUs: Long): List<Tone> =
        listOf(
            Tone(atMonoUs - 3 * SECOND_US, pip, sampleRateHz),
            Tone(atMonoUs - 2 * SECOND_US, pip, sampleRateHz),
            Tone(atMonoUs - SECOND_US, pip, sampleRateHz),
            Tone(atMonoUs, sync, sampleRateHz),
        )

    public fun syncTones(atMonoUs: Long): List<Tone> = listOf(Tone(atMonoUs, sync, sampleRateHz))

    internal fun pcm(frequencyHz: Int, durationUs: Long): FloatArray {
        val count = (durationUs * sampleRateHz / SECOND_US).toInt()
        val ramp = (RAMP_US * sampleRateHz / SECOND_US).toInt()
        val amplitude = 10.0.pow(LEVEL_DB / 20)
        return FloatArray(count) { i ->
            val edge = minOf(i, count - 1 - i)
            val envelope = if (edge < ramp) 0.5 - 0.5 * cos(PI * edge / ramp) else 1.0
            (amplitude * envelope * sin(2 * PI * frequencyHz * i / sampleRateHz)).toFloat()
        }
    }

    private fun ceilDiv(value: Long, divisor: Long): Long = -(-value).floorDiv(divisor)
}
