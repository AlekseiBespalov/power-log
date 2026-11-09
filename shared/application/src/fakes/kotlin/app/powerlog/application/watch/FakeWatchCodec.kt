package app.powerlog.application.watch

class FakeWatchCodec : WatchCodec {
    private val results = mutableMapOf<String, WatchDecodeResult>()
    private val sent = mutableListOf<WatchMessage>()

    val encoded: List<WatchMessage>
        get() = sent.toList()

    fun bytesFor(result: WatchDecodeResult): ByteArray {
        val key = "fake-watch-message-${results.size + 1}"
        results[key] = result
        return key.encodeToByteArray()
    }

    override fun decode(bytes: ByteArray): WatchDecodeResult =
        results[bytes.decodeToString()] ?: WatchDecodeResult.Invalid

    override fun encode(message: WatchMessage): ByteArray {
        sent += message
        return bytesFor(WatchDecodeResult.Valid(message))
    }
}
