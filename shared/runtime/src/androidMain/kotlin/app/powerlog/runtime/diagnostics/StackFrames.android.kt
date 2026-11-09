package app.powerlog.runtime.diagnostics

internal actual fun stackFrames(error: Throwable): StackFrames =
    StackFrames(
        error.stackTrace.map { "${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})" },
        null,
        null,
    )
