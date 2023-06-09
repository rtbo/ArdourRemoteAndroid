package rtbo.ardourremote.osc

import java.nio.ByteBuffer
import java.nio.ByteOrder

sealed class OscAtomic {
    abstract val tagLen: Pair<Char, Int>
    abstract fun encode(buffer: ByteBuffer)

    open val isInt = false
    open val isLong = false
    open val isFloat = false
    open val isDouble = false
    open val isString = false
    open val isBlob = false

    open val int: Int? = null
    open val long: Long? = null
    open val float: Float? = null
    open val double: Double? = null
    open val string: String? = null
    open val blob: ByteArray? = null

    companion object {
        fun decode(typeTag: Char, buffer: ByteBuffer): OscAtomic {
            return when (typeTag) {
                'i' -> OscInt(buffer.int)
                'h' -> OscLong(buffer.long)
                'f' -> OscFloat(buffer.float)
                'd' -> OscDouble(buffer.double)
                's' -> OscString.decode(buffer)
                'b' -> OscBlob.decode(buffer)
                else -> throw Exception("Unsupported OSC atomic type: '$typeTag'")
            }
        }
    }
}

data class OscInt(val value: Int) : OscAtomic() {
    override val tagLen = Pair('i', 4)
    override fun encode(buffer: ByteBuffer) {
        buffer.putInt(value)
    }

    override val isInt = true
    override val int = value
    override val long = value.toLong()
    override val float = value.toFloat()
    override val double = value.toDouble()

    override fun toString(): String {
        return "i:$value"
    }
}

data class OscLong(val value: Long) : OscAtomic() {
    override val tagLen = Pair('h', 8)
    override fun encode(buffer: ByteBuffer) {
        buffer.putLong(value)
    }

    override val isLong = true
    override val long = value
    override val int = value.toInt()
    override val float = value.toFloat()
    override val double = value.toDouble()

    override fun toString(): String {
        return "h:$value"
    }
}

data class OscFloat(val value: Float) : OscAtomic() {
    override val tagLen = Pair('f', 4)
    override fun encode(buffer: ByteBuffer) {
        buffer.putFloat(value)
    }

    override val isFloat = true
    override val float = value
    override val double = value.toDouble()

    override fun toString(): String {
        return "f:$value"
    }
}

data class OscDouble(val value: Double) : OscAtomic() {
    override val tagLen = Pair('d', 8)
    override fun encode(buffer: ByteBuffer) {
        buffer.putDouble(value)
    }

    override val isDouble = true
    override val double = value
    override val float = value.toFloat()

    override fun toString(): String {
        return "d:$value"
    }
}

data class OscString(val value: String) : OscAtomic() {

    override val tagLen = Pair(
        's',
        alignUp(value.length + 1)
    )

    override fun encode(buffer: ByteBuffer) {
        var numNulls = alignUp(value.length + 1) - value.length
        buffer.put(value.toByteArray(Charsets.UTF_8))
        while ((numNulls--) > 0) {
            buffer.put(0.toByte())
        }
    }

    override val isString = true
    override val string = value

    override fun toString(): String {
        return "s:\"$value\""
    }

    companion object {
        fun decode(buffer: ByteBuffer): OscString {
            val list = mutableListOf<ByteArray>()
            var sz = 0
            while (true) {
                val buf = ByteArray(4)
                buffer.get(buf)
                if (buf.last().toInt() != 0) {
                    list.add(buf)
                    sz += 4
                } else {
                    val numChars = buf.indexOfFirst { it.toInt() == 0 }
                    if (numChars != 0) {
                        list.add(buf.sliceArray(IntRange(0, numChars - 1)))
                        sz += numChars
                    }
                    break
                }
            }
            val ascii = ByteArray(sz)
            var offset = 0
            for (ba in list) {
                ba.copyInto(ascii, offset)
                offset += ba.size
            }
            return OscString(ascii.toString(Charsets.US_ASCII))
        }
    }
}

data class OscBlob(val value: ByteArray) : OscAtomic() {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as OscBlob

        if (!value.contentEquals(other.value)) return false

        return true
    }

    override fun hashCode(): Int {
        return value.contentHashCode()
    }

    override val tagLen = Pair('b', 4 + alignUp(value.size))

    override fun encode(buffer: ByteBuffer) {
        buffer.putInt(value.size)
        buffer.put(value)
        var numNulls = alignUp(value.size) - value.size
        while (numNulls > 0) {
            buffer.put(0.toByte())
            numNulls--
        }
    }

    override val isBlob = true
    override val blob = value

    override fun toString(): String {
        return "b:[${value.size} bytes]"
    }

    companion object {
        fun decode(buffer: ByteBuffer): OscBlob {
            val len = buffer.int
            val value = ByteArray(len)
            buffer.get(value)
            var numNulls = alignUp(value.size) - value.size
            while (numNulls > 0) {
                if (buffer.get() != 0.toByte()) {
                    throw Exception("Inconsistent blob state")
                }
                numNulls--
            }
            return OscBlob(value)
        }
    }
}

fun Int.toOsc() = OscInt(this)
fun Float.toOsc() = OscFloat(this)
fun String.toOsc() = OscString(this)
fun ByteArray.toOsc() = OscBlob(this)

data class OscMessage(val address: OscString, val args: Array<OscAtomic> = emptyArray()) {

    constructor(address: String, args: Array<OscAtomic> = emptyArray()) : this(
        address.toOsc(), args
    )

    constructor(address: OscString, arg: OscAtomic) : this(
        address, arrayOf(arg)
    )

    constructor(address: String, arg: OscAtomic) : this(
        address.toOsc(), arrayOf(arg)
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as OscMessage

        if (address != other.address) return false
        if (!args.contentEquals(other.args)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = address.hashCode()
        result = 31 * result + args.contentHashCode()
        return result
    }

    override fun toString(): String {
        val argStr = args.joinToString(" ") { it.toString() }
        return "OscMessage(${address.value}${ if (argStr.isNotEmpty()) " " else "" }$argStr)"
    }
}

fun oscMessageToPacket(msg: OscMessage): ByteArray {
    var (_, msgSz) = msg.address.tagLen
    var argsTypeTags = ","
    for (arg in msg.args) {
        val (tag, len) = arg.tagLen
        argsTypeTags += tag
        msgSz += len
    }
    val attAtomic = OscString(argsTypeTags)
    msgSz += attAtomic.tagLen.second

    val buffer = ByteBuffer.allocate(msgSz)
    buffer.order(ByteOrder.BIG_ENDIAN)
    msg.address.encode(buffer)
    attAtomic.encode(buffer)
    msg.args.forEach { it.encode(buffer) }

    return buffer.array()
}

fun oscPacketToMessage(pkt: ByteArray, offset: Int, length: Int): OscMessage {
    val buffer = ByteBuffer.wrap(pkt, offset, length)
    buffer.order(ByteOrder.BIG_ENDIAN)
    val address = OscString.decode(buffer)
    val typeTags = OscString.decode(buffer)
    if (typeTags.value.isEmpty() || typeTags.value[0] != ',') {
        throw Exception("Ill-formed Message for $address: missing ',' in arguments type tags")
    }
    val args = Array<OscAtomic>(typeTags.value.length - 1) {
        OscAtomic.decode(
            typeTags.value[it + 1],
            buffer
        )
    }
    return OscMessage(address, args)
}

private fun alignUp(len: Int): Int {
    val rem = len % 4
    return if (rem == 0) len else len + 4 - rem
}
