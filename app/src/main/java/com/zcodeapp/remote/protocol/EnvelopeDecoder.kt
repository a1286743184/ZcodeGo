package com.zcodeapp.remote.protocol

/**
 * WS 文本帧（明文 JSON 信封）解码。不抛异常：畸形输入返回 Malformed，由上层计数。
 */
sealed class RelayFrame {
    data class AuthAck(
        val pairStatus: String?,
        val deviceSid: String?,
        val terminalSid: String?,
    ) : RelayFrame()

    data class PairStatusAck(val pairStatus: String?) : RelayFrame()
    data class Data(val zcodeType: String?, val payload: JsonValue.Obj?) : RelayFrame()
    object WsClosed : RelayFrame()
    data class Other(val type: String?) : RelayFrame()
    data class Malformed(val reason: String) : RelayFrame()
}

object EnvelopeDecoder {
    fun decode(text: String): RelayFrame {
        val root = try {
            JsonValue.parse(text)
        } catch (e: JsonParseException) {
            return RelayFrame.Malformed("json: ${e.message}")
        }
        if (root !is JsonValue.Obj) return RelayFrame.Malformed("root not object")
        return when (root.str("type")) {
            "__ws_close__" -> RelayFrame.WsClosed
            "auth_ack" -> RelayFrame.AuthAck(
                pairStatus = root.str("pair_status"),
                deviceSid = root.str("device_sid"),
                terminalSid = root.str("terminal_sid"),
            )
            "pair_status_ack" -> RelayFrame.PairStatusAck(root.str("pair_status"))
            "data" -> {
                val payload = root.obj("payload")
                RelayFrame.Data(zcodeType = payload?.str("zcode_type"), payload = payload)
            }
            else -> RelayFrame.Other(root.str("type"))
        }
    }
}
