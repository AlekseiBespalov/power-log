package app.powerlog.runtime.diagnostics

import app.powerlog.application.store.LogField
import app.powerlog.application.store.LogLevel
import app.powerlog.application.store.LogLine
import app.powerlog.application.store.LogValue
import app.powerlog.domain.core.time.ClockReading

internal class StackFrames(val frames: List<String>, val image: String?, val load: String?)

internal expect fun stackFrames(error: Throwable): StackFrames

internal object Unexpected {
    private const val maxFrames = 64
    // What a line holds besides its frames stays well under this share of the log's 4,096-byte line limit.
    private const val frameBytes = 3_072

    fun line(component: String, error: Throwable, at: ClockReading): LogLine {
        val trace = stackFrames(error)
        val fields = buildList {
            add(LogField("component", LogValue.Text(component)))
            add(LogField("exception", LogValue.Text(simpleName(error))))
            add(LogField("frames", LogValue.Texts(fit(trace.frames))))
            trace.image?.let { add(LogField("image", LogValue.Text(it))) }
            trace.load?.let { add(LogField("load", LogValue.Text(it))) }
        }
        return LogLine(LogLevel.error, at.utcUs, at.monoUs, "unexpected", fields)
    }

    fun crash(error: Throwable, at: ClockReading, thread: String): LogLine {
        val trace = stackFrames(error)
        val fields = buildList {
            add(LogField("exception", LogValue.Text(simpleName(error))))
            trace.image?.let { add(LogField("image", LogValue.Text(it))) }
            trace.load?.let { add(LogField("load", LogValue.Text(it))) }
            add(LogField("frames", LogValue.Texts(fit(trace.frames))))
            add(LogField("thread", LogValue.Text(thread)))
        }
        return LogLine(LogLevel.error, at.utcUs, at.monoUs, "crash", fields)
    }

    private fun simpleName(error: Throwable): String =
        error::class
            .simpleName
            .orEmpty()
            .filter { it.isLetterOrDigit() || it == '_' || it == '$' }
            .take(128)
            .ifEmpty {
                "Throwable"
            }

    private fun fit(frames: List<String>): List<String> {
        var bytes = 0
        return frames.take(maxFrames).takeWhile { frame ->
            bytes += frame.encodeToByteArray().size + 3
            bytes <= frameBytes
        }
    }
}
