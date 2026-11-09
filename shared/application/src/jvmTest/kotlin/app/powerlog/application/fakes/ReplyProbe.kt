package app.powerlog.application.fakes

import app.powerlog.application.port.PortError
import app.powerlog.application.port.PortErrorKind
import app.powerlog.application.port.Reply

class ReplyProbe<T> {
    val values = mutableListOf<T>()
    val errors = mutableListOf<PortError>()
    val reply = Reply<T>(onOk = { values += it }, onFailed = { errors += it })

    val kinds: List<PortErrorKind>
        get() = errors.map { it.kind }

    val settled: Boolean
        get() = values.isNotEmpty() || errors.isNotEmpty()
}
