package app.powerlog.runtime.diagnostics

internal actual fun stackFrames(error: Throwable): StackFrames =
    StackFrames(error.stackTraceToString().lines().drop(1).map { it.trim() }.filter { it.isNotEmpty() }, null, null)
