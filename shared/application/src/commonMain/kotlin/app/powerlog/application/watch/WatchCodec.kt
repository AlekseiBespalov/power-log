package app.powerlog.application.watch

public interface WatchCodec {
    public fun decode(bytes: ByteArray): WatchDecodeResult

    public fun encode(message: WatchMessage): ByteArray
}

public sealed interface WatchDecodeResult {
    public data class Valid(public val message: WatchMessage) : WatchDecodeResult

    public data object VersionMismatch : WatchDecodeResult

    public data object Invalid : WatchDecodeResult
}
