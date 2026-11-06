package app.deckr.ble

import java.util.UUID

/** Wire format shared with the Windows receiver. See Deckr/README.md. */
object Protocol {
    val SERVICE: UUID = UUID.fromString("6b1d0001-7c3e-4d8a-9f2b-5e4d3c2b1a00")
    val INPUT: UUID = UUID.fromString("6b1d0002-7c3e-4d8a-9f2b-5e4d3c2b1a00")
    val STATUS: UUID = UUID.fromString("6b1d0003-7c3e-4d8a-9f2b-5e4d3c2b1a00")

    const val HELLO: Byte = 0x00
    const val MOVE: Byte = 0x01
    const val BUTTON: Byte = 0x02
    const val SCROLL: Byte = 0x03
    const val TEXT: Byte = 0x04
    const val KEY: Byte = 0x05
    const val CLICK: Byte = 0x06
    const val PAD: Byte = 0x10
    const val PING: Byte = 0x7F

    const val MOUSE_LEFT = 0
    const val MOUSE_RIGHT = 1
    const val MOUSE_MIDDLE = 2

    enum class Key(val id: Int) {
        Backspace(1), Enter(2), Tab(3), Esc(4), Left(5), Right(6), Up(7), Down(8), Delete(9)
    }

    /** Status characteristic values. */
    const val STATUS_OK = 1
    const val STATUS_WAITING = 2
    const val STATUS_DENIED = 3

    /** HELLO: this phone's 16-byte install id, then its name (at most 40 UTF-8 bytes). */
    fun hello(id: UUID, name: String): ByteArray {
        val idBytes = java.nio.ByteBuffer.allocate(16)
            .putLong(id.mostSignificantBits).putLong(id.leastSignificantBits).array()
        var n = name.trim()
        while (n.toByteArray(Charsets.UTF_8).size > 40) n = n.dropLast(1)
        return byteArrayOf(HELLO) + idBytes + n.toByteArray(Charsets.UTF_8)
    }

    fun move(dx: Int, dy: Int): ByteArray = int16Packet(MOVE, dx, dy)

    fun scroll(vertical: Int, horizontal: Int): ByteArray = int16Packet(SCROLL, vertical, horizontal)

    fun button(button: Int, down: Boolean) = byteArrayOf(BUTTON, button.toByte(), if (down) 1 else 0)

    fun click(button: Int) = byteArrayOf(CLICK, button.toByte())

    fun key(key: Key) = byteArrayOf(KEY, key.id.toByte())

    fun ping() = byteArrayOf(PING)

    /** Splits text into TEXT packets that each fit in [maxPacket] bytes, never splitting a character. */
    fun text(text: String, maxPacket: Int): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        val limit = (maxPacket - 1).coerceAtLeast(4)
        var chunk = StringBuilder()
        var chunkBytes = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val s = String(Character.toChars(cp))
            val n = s.toByteArray(Charsets.UTF_8).size
            if (chunkBytes + n > limit && chunk.isNotEmpty()) {
                out += byteArrayOf(TEXT) + chunk.toString().toByteArray(Charsets.UTF_8)
                chunk = StringBuilder()
                chunkBytes = 0
            }
            chunk.append(s)
            chunkBytes += n
            i += Character.charCount(cp)
        }
        if (chunk.isNotEmpty()) out += byteArrayOf(TEXT) + chunk.toString().toByteArray(Charsets.UTF_8)
        return out
    }

    fun pad(state: PadState): ByteArray = byteArrayOf(
        PAD,
        (state.buttons and 0xFF).toByte(),
        ((state.buttons shr 8) and 0xFF).toByte(),
        state.dpad.toByte(),
        axis(state.lx), axis(state.ly), axis(state.rx), axis(state.ry),
        trigger(state.l2), trigger(state.r2),
    )

    private fun axis(v: Float): Byte = ((v.coerceIn(-1f, 1f) * 127f) + 128f).toInt().coerceIn(0, 255).toByte()
    private fun trigger(v: Float): Byte = (v.coerceIn(0f, 1f) * 255f).toInt().toByte()

    private fun int16Packet(type: Byte, a: Int, b: Int): ByteArray {
        val x = a.coerceIn(-32768, 32767)
        val y = b.coerceIn(-32768, 32767)
        return byteArrayOf(
            type,
            (x and 0xFF).toByte(), ((x shr 8) and 0xFF).toByte(),
            (y and 0xFF).toByte(), ((y shr 8) and 0xFF).toByte(),
        )
    }
}

/** Full controller state. Sticks are -1..1 (y down is +), triggers 0..1. */
data class PadState(
    val buttons: Int = 0,
    val dpad: Int = 0,
    val lx: Float = 0f,
    val ly: Float = 0f,
    val rx: Float = 0f,
    val ry: Float = 0f,
    val l2: Float = 0f,
    val r2: Float = 0f,
) {
    fun withButton(bit: Int, down: Boolean) =
        copy(buttons = if (down) buttons or (1 shl bit) else buttons and (1 shl bit).inv())

    fun withDpad(bit: Int, down: Boolean) =
        copy(dpad = if (down) dpad or bit else dpad and bit.inv())

    companion object {
        const val CROSS = 0
        const val CIRCLE = 1
        const val SQUARE = 2
        const val TRIANGLE = 3
        const val L1 = 4
        const val R1 = 5
        const val L3 = 6
        const val R3 = 7
        const val CREATE = 8
        const val OPTIONS = 9
        const val PS = 10
        const val TOUCHPAD = 11

        const val DPAD_UP = 1
        const val DPAD_RIGHT = 2
        const val DPAD_DOWN = 4
        const val DPAD_LEFT = 8
    }
}
