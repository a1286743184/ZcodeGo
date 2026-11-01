package com.zcodeapp.remote.protocol

/**
 * 极简 JSON 解析器：协议层零第三方依赖，保证 JVM 单测可跑。
 * 仅供机器生成的协议帧使用，不追求性能极致，但严格拒绝畸形输入。
 */
sealed class JsonValue {
    object Null : JsonValue()
    data class Bool(val value: Boolean) : JsonValue()
    data class Num(val raw: String) : JsonValue() {
        val long: Long? get() = raw.toLongOrNull()
        val int: Int? get() = long?.toInt()
        val double: Double get() = raw.toDouble()
    }
    data class Str(val value: String) : JsonValue()
    data class Arr(val items: List<JsonValue>) : JsonValue()
    data class Obj(val fields: Map<String, JsonValue>) : JsonValue() {
        fun str(key: String): String? = (fields[key] as? Str)?.value
        fun num(key: String): Long? = (fields[key] as? Num)?.long
        fun obj(key: String): Obj? = fields[key] as? Obj
        fun arr(key: String): Arr? = fields[key] as? Arr
        fun value(key: String): JsonValue? = fields[key]
    }

    companion object {
        fun parse(text: String): JsonValue = JsonParser(text).parseDocument()
    }
}

class JsonParseException(message: String) : Exception(message)

internal class JsonParser(private val s: String) {
    private var i = 0
    private var depth = 0

    fun parseDocument(): JsonValue {
        val v = parseValue()
        skipWs()
        if (i < s.length) throw JsonParseException("trailing content at $i")
        return v
    }

    private fun skipWs() {
        while (i < s.length && s[i].let { it == ' ' || it == '\t' || it == '\n' || it == '\r' }) i++
    }

    private fun parseValue(): JsonValue {
        if (depth > 64) throw JsonParseException("depth limit")
        skipWs()
        if (i >= s.length) throw JsonParseException("unexpected end")
        return when (s[i]) {
            '{' -> parseObj()
            '[' -> parseArr()
            '"' -> JsonValue.Str(parseString())
            't' -> literal("true", JsonValue.Bool(true))
            'f' -> literal("false", JsonValue.Bool(false))
            'n' -> literal("null", JsonValue.Null)
            else -> parseNumber()
        }
    }

    private fun literal(word: String, v: JsonValue): JsonValue {
        if (!s.startsWith(word, i)) throw JsonParseException("bad literal at $i")
        i += word.length
        return v
    }

    private fun parseObj(): JsonValue.Obj {
        depth++
        i++ // {
        val map = LinkedHashMap<String, JsonValue>()
        skipWs()
        if (i < s.length && s[i] == '}') { i++; depth--; return JsonValue.Obj(map) }
        while (true) {
            skipWs()
            if (i >= s.length || s[i] != '"') throw JsonParseException("expected key at $i")
            val key = parseString()
            skipWs()
            if (i >= s.length || s[i] != ':') throw JsonParseException("expected ':' at $i")
            i++
            map[key] = parseValue()
            if (map.size > 1_000_000) throw JsonParseException("object too large")
            skipWs()
            if (i >= s.length) throw JsonParseException("unterminated object")
            when (s[i]) {
                ',' -> i++
                '}' -> { i++; depth--; return JsonValue.Obj(map) }
                else -> throw JsonParseException("expected ',' or '}' at $i")
            }
        }
    }

    private fun parseArr(): JsonValue.Arr {
        depth++
        i++ // [
        val list = ArrayList<JsonValue>()
        skipWs()
        if (i < s.length && s[i] == ']') { i++; depth--; return JsonValue.Arr(list) }
        while (true) {
            list.add(parseValue())
            if (list.size > 1_000_000) throw JsonParseException("array too large")
            skipWs()
            if (i >= s.length) throw JsonParseException("unterminated array")
            when (s[i]) {
                ',' -> i++
                ']' -> { i++; depth--; return JsonValue.Arr(list) }
                else -> throw JsonParseException("expected ',' or ']' at $i")
            }
        }
    }

    private fun parseString(): String {
        val sb = StringBuilder()
        i++ // opening quote
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> { i++; return sb.toString() }
                c == '\\' -> {
                    i++
                    if (i >= s.length) throw JsonParseException("unterminated escape")
                    when (val e = s[i]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (i + 4 >= s.length) throw JsonParseException("bad unicode escape")
                            val hex = s.substring(i + 1, i + 5)
                            val code = hex.toIntOrNull(16) ?: throw JsonParseException("bad unicode escape")
                            sb.append(code.toChar())
                            i += 4
                        }
                        else -> throw JsonParseException("bad escape '\\$e'")
                    }
                    i++
                }
                else -> {
                    if (c.code < 0x20) throw JsonParseException("raw control char in string")
                    sb.append(c)
                    i++
                }
            }
        }
        throw JsonParseException("unterminated string")
    }

    private fun parseNumber(): JsonValue {
        val start = i
        if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
        while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '-' || s[i] == '+')) i++
        if (i == start) throw JsonParseException("bad value at $start")
        val raw = s.substring(start, i)
        raw.toDoubleOrNull() ?: throw JsonParseException("bad number '$raw'")
        return JsonValue.Num(raw)
    }
}
