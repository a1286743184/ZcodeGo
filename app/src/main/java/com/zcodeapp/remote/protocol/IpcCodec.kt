package com.zcodeapp.remote.protocol

/**
 * IPC 值编码解码（v2 附录 B.3）。实测证明 rpc-frame payload 是「无固定头」的值序列：
 * 样本 BAEGyAEA → [Arr([Int 200]), Undefined]，因此按值流解码，不剥离任何前缀。
 */
sealed class IpcValue {
    object Undefined : IpcValue()
    data class IntV(val value: Long) : IpcValue()
    data class StrV(val value: String) : IpcValue()
    data class BufV(val bytes: ByteArray) : IpcValue()
    data class VsBufV(val bytes: ByteArray) : IpcValue()
    data class ArrV(val items: List<IpcValue>) : IpcValue()
    data class JsonV(val text: String) : IpcValue() {
        val parsed: JsonValue? by lazy { runCatching { JsonValue.parse(text) }.getOrNull() }
    }

    fun asInt(): Long? = (this as? IntV)?.value
    fun asStr(): String? = (this as? StrV)?.value
    fun asArr(): List<IpcValue>? = (this as? ArrV)?.items

    override fun toString(): String = when (this) {
        Undefined -> "Undefined"
        is IntV -> "Int($value)"
        is StrV -> "Str(${value.take(48)})"
        is BufV -> "Buf(${bytes.size})"
        is VsBufV -> "VsBuf(${bytes.size})"
        is ArrV -> "Arr(${items.size})"
        is JsonV -> "Json(${text.take(48)})"
    }
}

class IpcCodecException(message: String) : Exception(message)

object IpcCodec {
    const val TAG_UNDEFINED = 0
    const val TAG_STRING = 1
    const val TAG_BUFFER = 2
    const val TAG_VSBUFFER = 3
    const val TAG_ARRAY = 4
    const val TAG_JSON = 5
    const val TAG_INT = 6

    const val MAX_DEPTH = 32
    const val MAX_CONTAINER = 100_000
    const val MAX_VALUE_BYTES = 16 * 1024 * 1024

    /** 解码整个 buffer 为值序列（值流），直到耗尽。 */
    fun decode(bytes: ByteArray): List<IpcValue> {
        val out = ArrayList<IpcValue>()
        var pos = 0
        while (pos < bytes.size) {
            val (v, next) = decodeValue(bytes, pos, 0)
            out.add(v)
            pos = next
        }
        return out
    }

    private fun decodeValue(b: ByteArray, start: Int, depth: Int): Pair<IpcValue, Int> {
        if (depth > MAX_DEPTH) throw IpcCodecException("depth limit")
        if (start >= b.size) throw IpcCodecException("read past end")
        val tag = b[start].toInt() and 0xFF
        var pos = start + 1
        return when (tag) {
            TAG_UNDEFINED -> IpcValue.Undefined to pos
            TAG_INT -> {
                val (v, next) = readVarint(b, pos)
                IpcValue.IntV(v) to next
            }
            TAG_STRING -> {
                val (v, next) = readLen(b, pos)
                val str = utf8(b, v.first, v.second)
                IpcValue.StrV(str) to next
            }
            TAG_BUFFER, TAG_VSBUFFER -> {
                val (v, next) = readLen(b, pos)
                val bytes = b.copyOfRange(v.first, v.first + v.second)
                (if (tag == TAG_BUFFER) IpcValue.BufV(bytes) else IpcValue.VsBufV(bytes)) to next
            }
            TAG_ARRAY -> {
                val (count, p) = readVarint(b, pos)
                pos = p
                if (count > MAX_CONTAINER) throw IpcCodecException("array too large")
                val items = ArrayList<IpcValue>(if (count < 1024) count.toInt() else 1024)
                var k = 0L
                while (k < count) {
                    val (item, next) = decodeValue(b, pos, depth + 1)
                    items.add(item)
                    pos = next
                    k++
                }
                IpcValue.ArrV(items) to pos
            }
            TAG_JSON -> {
                val (v, next) = readLen(b, pos)
                val str = utf8(b, v.first, v.second)
                IpcValue.JsonV(str) to next
            }
            else -> throw IpcCodecException("unknown tag $tag at $start")
        }
    }

    private fun readLen(b: ByteArray, pos: Int): Pair<Pair<Int, Int>, Int> {
        val (len, next) = readVarint(b, pos)
        if (len < 0 || len > MAX_VALUE_BYTES) throw IpcCodecException("length out of range: $len")
        val end = next + len.toInt()
        if (end > b.size) throw IpcCodecException("value truncated")
        return (next to len.toInt()) to end
    }

    /** 7-bit LEB128 varint，最多 5 字节（31 位非负）。 */
    private fun readVarint(b: ByteArray, pos: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var p = pos
        var count = 0
        while (true) {
            if (p >= b.size) throw IpcCodecException("varint truncated")
            if (count >= 5) throw IpcCodecException("varint overflow")
            val byte = b[p].toInt() and 0xFF
            p++
            count++
            result = result or ((byte and 0x7F).toLong() shl shift)
            if (byte and 0x80 == 0) {
                if (result > Int.MAX_VALUE.toLong()) throw IpcCodecException("varint exceeds 31 bits")
                return result to p
            }
            shift += 7
        }
    }

    private fun utf8(b: ByteArray, start: Int, len: Int): String {
        if (len > MAX_VALUE_BYTES) throw IpcCodecException("string too large")
        return String(b, start, len, Charsets.UTF_8)
    }
}
