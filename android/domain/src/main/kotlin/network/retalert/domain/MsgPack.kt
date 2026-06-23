package network.retalert.domain

import java.nio.ByteBuffer

/** Minimal msgpack codec for RetAlert wire types (str, int, bin, array).
 *  Only what `MediaChunk` needs — byte-identical to Python `umsgpack.packb/unpackb`
 *  for the 6-tuple `[str, str, int, int, str, bin]`. */
object MsgPack {
    fun pack(value: Any?): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        write(value, out)
        return out.toByteArray()
    }

    private fun write(value: Any?, out: java.io.ByteArrayOutputStream) {
        when (value) {
            null -> out.write(0xc0)
            is Boolean -> out.write(if (value) 0xc3 else 0xc2)
            is ByteArray -> writeBin(value, out)
            is Int -> writeInt(value.toLong(), out)
            is Long -> writeInt(value, out)
            is String -> writeStr(value, out)
            is List<*> -> {
                val n = value.size
                if (n <= 15) out.write(0x90 or n)
                else if (n <= 65535) { out.write(0xdc); writeU16(n, out) }
                else { out.write(0xdd); writeU32(n, out) }
                value.forEach { write(it, out) }
            }
            else -> throw IllegalArgumentException("msgpack: unsupported ${value!!::class}")
        }
    }

    private fun writeStr(s: String, out: java.io.ByteArrayOutputStream) {
        val b = s.toByteArray(Charsets.UTF_8)
        val n = b.size
        when {
            n <= 31 -> out.write(0xa0 or n)
            n <= 255 -> { out.write(0xd9); out.write(n) }
            n <= 65535 -> { out.write(0xda); writeU16(n, out) }
            else -> { out.write(0xdb); writeU32(n, out) }
        }
        out.write(b)
    }

    private fun writeBin(b: ByteArray, out: java.io.ByteArrayOutputStream) {
        val n = b.size
        when {
            n <= 255 -> { out.write(0xc4); out.write(n) }
            n <= 65535 -> { out.write(0xc5); writeU16(n, out) }
            else -> { out.write(0xc6); writeU32(n, out) }
        }
        out.write(b)
    }

    private fun writeInt(v: Long, out: java.io.ByteArrayOutputStream) {
        when {
            v in 0..127 -> out.write(v.toInt())
            v in -32..-1 -> out.write((v.toInt() and 0xff))
            v in 0..255 -> { out.write(0xcc); out.write(v.toInt()) }
            v in -128..127 -> { out.write(0xd0); out.write(v.toInt()) }
            v in -32768..32767 -> { out.write(0xd1); writeI16(v.toInt(), out) }
            v in 0..65535 -> { out.write(0xcd); writeU16(v.toInt(), out) }
            v in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() -> { out.write(0xd2); writeI32(v.toInt(), out) }
            v in 0..0xFFFFFFFFL -> { out.write(0xce); writeU32(v.toInt(), out) }
            else -> { out.write(0xd3); writeI64(v, out) }
        }
    }

    private fun writeU16(v: Int, out: java.io.ByteArrayOutputStream) {
        out.write((v ushr 8) and 0xff); out.write(v and 0xff)
    }
    private fun writeI16(v: Int, out: java.io.ByteArrayOutputStream) = writeU16(v and 0xffff, out)
    private fun writeU32(v: Int, out: java.io.ByteArrayOutputStream) {
        out.write((v ushr 24) and 0xff); out.write((v ushr 16) and 0xff)
        out.write((v ushr 8) and 0xff); out.write(v and 0xff)
    }
    private fun writeI32(v: Int, out: java.io.ByteArrayOutputStream) = writeU32(v, out)
    private fun writeI64(v: Long, out: java.io.ByteArrayOutputStream) {
        val bb = ByteBuffer.allocate(8); bb.putLong(v); out.write(bb.array())
    }

    // -- unpack ----------------------------------------------------------

    fun unpack(data: ByteArray): Any? {
        val cur = intArrayOf(0)
        return read(data, cur)
    }

    private fun read(data: ByteArray, cur: IntArray): Any? {
        val b = data[cur[0]++].toInt() and 0xff
        return when {
            b <= 0x7f -> b                                  // positive fixint
            b in 0x80..0x8f -> {                            // fixmap (not produced by us)
                val n = b and 0x0f
                val map = LinkedHashMap<String, Any?>()
                for (i in 0 until n) {
                    val k = read(data, cur); val v = read(data, cur); map[k.toString()] = v
                }
                map
            }
            b in 0x90..0x9f -> readArray(data, cur, b and 0x0f)  // fixarray
            b in 0xa0..0xbf -> readStr(data, cur, b and 0x1f)   // fixstr
            b in 0xe0..0xff -> b - 256                       // negative fixint
            b == 0xc0 -> null
            b == 0xc2 -> false
            b == 0xc3 -> true
            b == 0xc4 -> readBin(data, cur, readU8(data, cur))       // bin8
            b == 0xc5 -> readBin(data, cur, readU16(data, cur))      // bin16
            b == 0xc6 -> readBin(data, cur, readU32(data, cur).toInt()) // bin32
            b == 0xca -> { cur[0] += 4; 0f }                         // float32 (unused)
            b == 0xcb -> { cur[0] += 8; 0.0 }                         // float64 (unused)
            b == 0xcc -> readU8(data, cur).toLong()                  // uint8
            b == 0xcd -> readU16(data, cur).toLong()                 // uint16
            b == 0xce -> readU32(data, cur)                          // uint32
            b == 0xcf -> readU64(data, cur)                          // uint64
            b == 0xd0 -> readI8(data, cur).toLong()                   // int8
            b == 0xd1 -> readI16(data, cur).toLong()                  // int16
            b == 0xd2 -> readI32(data, cur).toLong()                  // int32
            b == 0xd3 -> readI64(data, cur)                          // int64
            b == 0xd9 -> readStr(data, cur, readU8(data, cur))        // str8
            b == 0xda -> readStr(data, cur, readU16(data, cur))       // str16
            b == 0xdb -> readStr(data, cur, readU32(data, cur).toInt()) // str32
            b == 0xdc -> readArray(data, cur, readU16(data, cur))    // array16
            b == 0xdd -> readArray(data, cur, readU32(data, cur).toInt()) // array32
            else -> throw IllegalArgumentException("msgpack: unknown tag 0x${b.toString(16)}")
        }
    }

    private fun readU8(d: ByteArray, c: IntArray): Int = d[c[0]++].toInt() and 0xff
    private fun readU16(d: ByteArray, c: IntArray): Int =
        ((d[c[0]++].toInt() and 0xff) shl 8) or (d[c[0]++].toInt() and 0xff)
    private fun readU32(d: ByteArray, c: IntArray): Long =
        ((d[c[0]++].toInt() and 0xff).toLong() shl 24) or
        ((d[c[0]++].toInt() and 0xff).toLong() shl 16) or
        ((d[c[0]++].toInt() and 0xff).toLong() shl 8) or
        (d[c[0]++].toInt() and 0xff).toLong()
    private fun readU64(d: ByteArray, c: IntArray): Long {
        val hi = readU32(d, c); val lo = readU32(d, c)
        return (hi shl 32) or lo
    }
    private fun readI8(d: ByteArray, c: IntArray): Int = d[c[0]++].toInt()
    private fun readI16(d: ByteArray, c: IntArray): Int {
        val hi = (d[c[0]++].toInt() and 0xff) shl 8
        val lo = d[c[0]++].toInt() and 0xff
        val v = hi or lo
        return if (v >= 0x8000) v or 0xffff0000.toInt() else v  // sign-extend
    }
    private fun readI32(d: ByteArray, c: IntArray): Int =
        ((d[c[0]++].toInt() and 0xff) shl 24) or
        ((d[c[0]++].toInt() and 0xff) shl 16) or
        ((d[c[0]++].toInt() and 0xff) shl 8) or
        (d[c[0]++].toInt() and 0xff)
    private fun readI64(d: ByteArray, c: IntArray): Long {
        val hi = readI32(d, c).toLong() and 0xffffffffL
        val lo = readI32(d, c).toLong() and 0xffffffffL
        return (hi shl 32) or lo
    }
    private fun readStr(d: ByteArray, c: IntArray, len: Int): String {
        val s = d.copyOfRange(c[0], c[0] + len); c[0] += len
        return String(s, Charsets.UTF_8)
    }
    private fun readBin(d: ByteArray, c: IntArray, len: Int): ByteArray {
        val b = d.copyOfRange(c[0], c[0] + len); c[0] += len; return b
    }
    private fun readArray(d: ByteArray, c: IntArray, len: Int): List<Any?> {
        val out = ArrayList<Any?>(len)
        for (i in 0 until len) out.add(read(d, c))
        return out
    }
}