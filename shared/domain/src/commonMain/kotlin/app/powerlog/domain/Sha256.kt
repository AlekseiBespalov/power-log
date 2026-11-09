package app.powerlog.domain

object Sha256 {
    internal val constants =
        longArrayOf(
                0x428a2f98,
                0x71374491,
                0xb5c0fbcf,
                0xe9b5dba5,
                0x3956c25b,
                0x59f111f1,
                0x923f82a4,
                0xab1c5ed5,
                0xd807aa98,
                0x12835b01,
                0x243185be,
                0x550c7dc3,
                0x72be5d74,
                0x80deb1fe,
                0x9bdc06a7,
                0xc19bf174,
                0xe49b69c1,
                0xefbe4786,
                0x0fc19dc6,
                0x240ca1cc,
                0x2de92c6f,
                0x4a7484aa,
                0x5cb0a9dc,
                0x76f988da,
                0x983e5152,
                0xa831c66d,
                0xb00327c8,
                0xbf597fc7,
                0xc6e00bf3,
                0xd5a79147,
                0x06ca6351,
                0x14292967,
                0x27b70a85,
                0x2e1b2138,
                0x4d2c6dfc,
                0x53380d13,
                0x650a7354,
                0x766a0abb,
                0x81c2c92e,
                0x92722c85,
                0xa2bfe8a1,
                0xa81a664b,
                0xc24b8b70,
                0xc76c51a3,
                0xd192e819,
                0xd6990624,
                0xf40e3585,
                0x106aa070,
                0x19a4c116,
                0x1e376c08,
                0x2748774c,
                0x34b0bcb5,
                0x391c0cb3,
                0x4ed8aa4a,
                0x5b9cca4f,
                0x682e6ff3,
                0x748f82ee,
                0x78a5636f,
                0x84c87814,
                0x8cc70208,
                0x90befffa,
                0xa4506ceb,
                0xbef9a3f7,
                0xc67178f2,
            )
            .map { it.toInt() }
            .toIntArray()

    fun hash(data: ByteArray): String {
        val digest = Sha256Accumulator()
        digest.update(data)
        return digest.digest()
    }
}

class Sha256Accumulator {
    private val state =
        longArrayOf(0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19)
            .map { it.toInt() }
            .toIntArray()
    private val buffer = ByteArray(64)
    private val words = IntArray(64)
    private var buffered = 0
    private var byteCount = 0L

    @Throws(IllegalArgumentException::class)
    fun update(data: ByteArray) {
        require(byteCount <= Long.MAX_VALUE / 8 - data.size)
        byteCount += data.size
        for (byte in data) {
            buffer[buffered++] = byte
            if (buffered == 64) {
                block(buffer)
                buffered = 0
            }
        }
    }

    @Throws(IllegalArgumentException::class)
    fun digest(): String {
        val copy = Sha256Accumulator()
        state.copyInto(copy.state)
        buffer.copyInto(copy.buffer)
        copy.buffered = buffered
        copy.byteCount = byteCount
        val padding = ByteArray(if (buffered < 56) 64 - buffered else 128 - buffered)
        padding[0] = 0x80.toByte()
        val bits = byteCount * 8
        for (i in 0..7) padding[padding.lastIndex - i] = (bits ushr (i * 8)).toByte()
        copy.update(padding)
        return copy.state.joinToString("") { it.toUInt().toString(16).padStart(8, '0') }
    }

    private fun block(padded: ByteArray) {
        for (index in 0..15) {
            var word = 0
            for (byte in 0..3) word = (word shl 8) or (padded[index * 4 + byte].toInt() and 255)
            words[index] = word
        }
        for (index in 16..63) {
            val first = words[index - 15]
            val second = words[index - 2]
            val s0 = first.rotateRight(7) xor first.rotateRight(18) xor (first ushr 3)
            val s1 = second.rotateRight(17) xor second.rotateRight(19) xor (second ushr 10)
            words[index] = words[index - 16] + s0 + words[index - 7] + s1
        }
        var a = state[0]
        var b = state[1]
        var c = state[2]
        var d = state[3]
        var e = state[4]
        var f = state[5]
        var g = state[6]
        var h = state[7]
        for (index in 0..63) {
            val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
            val choice = (e and f) xor (e.inv() and g)
            val first = h + s1 + choice + Sha256.constants[index] + words[index]
            val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
            val majority = (a and b) xor (a and c) xor (b and c)
            h = g
            g = f
            f = e
            e = d + first
            d = c
            c = b
            b = a
            a = first + s0 + majority
        }
        state[0] += a
        state[1] += b
        state[2] += c
        state[3] += d
        state[4] += e
        state[5] += f
        state[6] += g
        state[7] += h
    }
}
