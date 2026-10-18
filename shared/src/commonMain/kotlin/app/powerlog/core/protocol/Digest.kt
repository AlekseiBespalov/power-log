package app.powerlog.core.protocol

import app.powerlog.core.Sha256Accumulator

object ContentDigest {
    const val empty = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    @Throws(ProtocolException::class)
    fun events(events: Array<Event>): String {
        val accumulator = Sha256Accumulator()
        for (event in events) accumulator.update((event.canonicalUtf8 + "\n").encodeToByteArray())
        return accumulator.digest()
    }

    @Throws(ProtocolException::class, IllegalArgumentException::class)
    fun seconds(records: Array<ControllerSecond>): String {
        val digest = Sha256Accumulator()
        for (record in records) digest.update((record.canonicalUtf8 + "\n").encodeToByteArray())
        return digest.digest()
    }
}
