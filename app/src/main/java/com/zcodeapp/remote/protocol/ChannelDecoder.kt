package com.zcodeapp.remote.protocol

/**
 * Channel 层解码（v2 附录 B.3），字段位置以 2026-10-25 的 3.16.3 实抓为准：
 *
 * 值流固定是「头数组 + 一个尾值」：
 * - 请求：head = [type, callId, service, method]，尾值 = 参数
 * - 响应：head = [type, id]，尾值 = 结果
 * 实测样例（tools/capture/raw/cap-192020.jsonl）：
 * - listen 102: Arr(4)[102, 0, "broadcast", "onMessage"] + Undefined
 * - call 100:   Arr(4)[100, 40, "zcode-task", "getTaskMeta"] + Arr(1)[Json]
 * - 201:        Arr(2)[201, 40] + Json
 * - 204:        Arr(2)[204, 14] + Json
 *
 * 204 的 id 就是对应 listen 的 callId（实抓里 18 个 listen 与 10 个 204 通道完全对应，
 * 不存在服务端另发监听器 id 的情况）。因此这里只做字段定位，不做身份推断。
 */
object ChannelDecoder {
    const val REQ_PROMISE = 100L
    const val REQ_PROMISE_CANCEL = 101L
    const val REQ_EVENT_LISTEN = 102L
    const val REQ_EVENT_DISPOSE = 103L
    const val RES_INITIALIZE = 200L
    const val RES_PROMISE_SUCCESS = 201L
    const val RES_PROMISE_ERROR = 202L
    const val RES_PROMISE_ERROR_OBJ = 203L
    const val RES_EVENT_FIRE = 204L

    sealed class ChannelMessage {
        abstract val typeCode: Long

        data class Call(
            val callId: Long,
            val service: String?,
            val method: String?,
            val arg: IpcValue?,
            override val typeCode: Long = REQ_PROMISE,
        ) : ChannelMessage()

        data class Cancel(val callId: Long, override val typeCode: Long = REQ_PROMISE_CANCEL) : ChannelMessage()
        data class Listen(
            val callId: Long,
            val service: String?,
            val event: String?,
            val arg: IpcValue?,
            override val typeCode: Long = REQ_EVENT_LISTEN,
        ) : ChannelMessage()

        data class Dispose(val callId: Long, override val typeCode: Long = REQ_EVENT_DISPOSE) : ChannelMessage()
        data class Initialize(val values: List<IpcValue>, override val typeCode: Long = RES_INITIALIZE) : ChannelMessage()
        data class PromiseSuccess(val callId: Long, val result: IpcValue?, override val typeCode: Long = RES_PROMISE_SUCCESS) : ChannelMessage()
        data class PromiseError(val callId: Long, val message: String?, override val typeCode: Long = RES_PROMISE_ERROR) : ChannelMessage()
        data class PromiseErrorObj(val callId: Long, val obj: IpcValue?, override val typeCode: Long = RES_PROMISE_ERROR_OBJ) : ChannelMessage()
        data class EventFire(val listenerId: Long, val arg: IpcValue?, override val typeCode: Long = RES_EVENT_FIRE) : ChannelMessage()
        data class Unknown(val type: Long, val values: List<IpcValue>, override val typeCode: Long = -1) : ChannelMessage()
    }

    fun decode(values: List<IpcValue>): ChannelMessage {
        if (values.isEmpty()) throw IpcCodecException("empty value stream")
        val head = values[0].asArr() ?: throw IpcCodecException("head value is not array")
        if (head.isEmpty()) throw IpcCodecException("head array empty")
        val type = head[0].asInt() ?: throw IpcCodecException("head[0] not int")

        val id = head.getOrNull(1)?.asInt() ?: -1L
        val service = head.getOrNull(2)?.asStr()
        val method = head.getOrNull(3)?.asStr()
        // 尾值即该调用的参数/结果；实测恒为一个值（可能是 Undefined）
        val payload = values.getOrNull(1)

        return when (type) {
            REQ_PROMISE -> ChannelMessage.Call(callId = id, service = service, method = method, arg = payload)
            REQ_PROMISE_CANCEL -> ChannelMessage.Cancel(callId = id)
            REQ_EVENT_LISTEN -> ChannelMessage.Listen(callId = id, service = service, event = method, arg = payload)
            REQ_EVENT_DISPOSE -> ChannelMessage.Dispose(callId = id)
            RES_INITIALIZE -> ChannelMessage.Initialize(values.drop(1))
            RES_PROMISE_SUCCESS -> ChannelMessage.PromiseSuccess(callId = id, result = payload)
            RES_PROMISE_ERROR -> ChannelMessage.PromiseError(callId = id, message = payloadText(payload))
            RES_PROMISE_ERROR_OBJ -> ChannelMessage.PromiseErrorObj(callId = id, obj = payload)
            RES_EVENT_FIRE -> ChannelMessage.EventFire(listenerId = id, arg = payload)
            else -> ChannelMessage.Unknown(type, values)
        }
    }

    /** 202 的错误文本可能直接是字符串，也可能包在 JSON 里；两者都取文本，取不到留 null。 */
    private fun payloadText(v: IpcValue?): String? = when (v) {
        is IpcValue.StrV -> v.value
        is IpcValue.JsonV -> v.text
        null, IpcValue.Undefined -> null
        else -> v.toString()
    }
}
