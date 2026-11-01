package com.zcodeapp.remote.protocol

/**
 * 协议管线：WS 文本帧 → 信封 → rpc 重组 → IPC 值流 → Channel 消息（含 listen/call 身份解析）。
 * 纯 Kotlin，JVM 可测。bridge 会话切换时 reset，防止跨代次混装。
 */
enum class Direction { RECV, SEND }

data class EnvelopeEvent(
    val direction: Direction,
    val zcodeType: String?,
    val payload: JsonValue.Obj?,
)

data class ChannelEvent(
    val direction: Direction,
    /** 已解析的服务名（经 listen/call 表回溯），解析失败为 null */
    val service: String?,
    val method: String?,
    val kind: String,
    val arg: IpcValue?,
    val typeCode: Long,
    val messageSeq: Long,
)

data class PipelineHealth(
    val decodedFrames: Long,
    val rpcComplete: Long,
    val rpcDiscarded: Long,
    val decodeFailures: Long,
    val needsResync: Boolean,
)

interface PipelineListener {
    fun onEnvelope(event: EnvelopeEvent) {}
    fun onChannelEvent(event: ChannelEvent) {}
    fun onHealth(health: PipelineHealth) {}
}

class ProtocolPipeline(private val listener: PipelineListener) {
    private val assembler = RpcAssembler()
    private val listenCalls = HashMap<Long, Pair<String?, String?>>()   // listen callId -> (service, event)
    private val callRegistry = HashMap<Long, Pair<String?, String?>>() // callId -> (service, method)
    private val listenerMap = HashMap<Long, Pair<String?, String?>>()  // 服务端另分配的 listenerId -> (service, event)

    private var decodedFrames = 0L
    private var rpcComplete = 0L
    private var decodeFailures = 0L
    private var currentBridgeSession: String? = null

    fun onWebSocketText(direction: Direction, text: String) {
        val frame = EnvelopeDecoder.decode(text)
        when (frame) {
            is RelayFrame.WsClosed -> listener.onHealth(health())
            is RelayFrame.Malformed -> {
                decodeFailures++
                listener.onHealth(health())
            }
            is RelayFrame.AuthAck, is RelayFrame.PairStatusAck, is RelayFrame.Other -> {
                decodedFrames++
                if (frame is RelayFrame.AuthAck) {
                    listener.onEnvelope(EnvelopeEvent(direction, "auth_ack", null))
                }
            }
            is RelayFrame.Data -> {
                decodedFrames++
                val zt = frame.zcodeType
                if (zt == "rpc-frame" || zt == "rpc-frame-ack") {
                    if (zt == "rpc-frame-ack") return // ACK 无需解码
                    onRpcFrame(direction, frame.payload)
                } else {
                    listener.onEnvelope(EnvelopeEvent(direction, zt, frame.payload))
                }
            }
        }
    }

    private fun onRpcFrame(direction: Direction, payload: JsonValue.Obj?) {
        val bs = payload?.str("bridgeSessionId")
        if (bs != null && bs != currentBridgeSession) {
            currentBridgeSession = bs
            assembler.reset()
        }
        when (val r = assembler.onFrame(payload)) {
            is RpcAssembler.Result.NeedMore -> Unit
            is RpcAssembler.Result.Discarded -> listener.onHealth(health())
            is RpcAssembler.Result.Complete -> {
                rpcComplete++
                val values = try {
                    IpcCodec.decode(r.bytes)
                } catch (e: IpcCodecException) {
                    decodeFailures++
                    listener.onHealth(health())
                    return
                }
                val msg = try {
                    ChannelDecoder.decode(values)
                } catch (e: IpcCodecException) {
                    decodeFailures++
                    listener.onHealth(health())
                    return
                }
                dispatch(direction, msg, r.messageSeq)
            }
        }
    }

    private fun dispatch(direction: Direction, msg: ChannelDecoder.ChannelMessage, messageSeq: Long) {
        when (msg) {
            is ChannelDecoder.ChannelMessage.Listen -> {
                listenCalls[msg.callId] = (msg.service to msg.event)
                emit(direction, msg.service, msg.event, "listen", msg.arg, msg.typeCode, messageSeq)
            }
            is ChannelDecoder.ChannelMessage.Call -> {
                callRegistry[msg.callId] = (msg.service to msg.method)
                emit(direction, msg.service, msg.method, "call", msg.arg, msg.typeCode, messageSeq)
            }
            is ChannelDecoder.ChannelMessage.PromiseSuccess -> {
                // 实测 201 只回 REQ_PROMISE 的 callId；listen 不产生 201。
                // 若某版服务端改用 201 分配监听器 id，则回退到 listenCalls 并登记映射。
                val callTarget = callRegistry.remove(msg.callId)
                val listenTarget = if (callTarget == null) listenCalls.remove(msg.callId) else null
                val target = callTarget ?: listenTarget
                val listenerId = msg.result?.asInt()
                if (listenTarget != null && listenerId != null) {
                    listenerMap[listenerId] = listenTarget
                }
                val (svc, mth) = target ?: (null to null)
                emit(direction, svc, mth, "promise-success", msg.result, msg.typeCode, messageSeq)
            }
            is ChannelDecoder.ChannelMessage.PromiseError, is ChannelDecoder.ChannelMessage.PromiseErrorObj -> {
                // 两类错误帧共用 callId 清理；不能强转为其中一种（PromiseErrorObj 会 ClassCastException）。
                val callId = when (msg) {
                    is ChannelDecoder.ChannelMessage.PromiseError -> msg.callId
                    is ChannelDecoder.ChannelMessage.PromiseErrorObj -> msg.callId
                    else -> -1L
                }
                callRegistry.remove(callId)
                listenCalls.remove(callId)
                emit(direction, null, null, "promise-error", null, msg.typeCode, messageSeq)
            }
            is ChannelDecoder.ChannelMessage.EventFire -> {
                // 实测 204 的 id 就是 listen 时用的 callId；listenerMap 仅为兼容其他版本保留。
                val target = listenCalls[msg.listenerId] ?: listenerMap[msg.listenerId]
                emit(direction, target?.first, target?.second, "event", msg.arg, msg.typeCode, messageSeq)
            }
            is ChannelDecoder.ChannelMessage.Cancel -> {
                listenCalls.remove(msg.callId)
                callRegistry.remove(msg.callId)
                emit(direction, null, null, "cancel", null, msg.typeCode, messageSeq)
            }
            is ChannelDecoder.ChannelMessage.Dispose -> {
                listenCalls.remove(msg.callId)
                emit(direction, null, null, "dispose", null, msg.typeCode, messageSeq)
            }
            is ChannelDecoder.ChannelMessage.Initialize -> {
                emit(direction, null, null, "initialize", null, msg.typeCode, messageSeq)
            }
            is ChannelDecoder.ChannelMessage.Unknown -> {
                emit(direction, null, null, "unknown", null, msg.typeCode, messageSeq)
            }
        }
    }

    private fun emit(
        direction: Direction,
        service: String?,
        method: String?,
        kind: String,
        arg: IpcValue?,
        typeCode: Long,
        messageSeq: Long,
    ) {
        listener.onChannelEvent(
            ChannelEvent(
                direction = direction,
                service = service,
                method = method,
                kind = kind,
                arg = arg,
                typeCode = typeCode,
                messageSeq = messageSeq,
            )
        )
    }

    fun reset() {
        assembler.reset()
        listenCalls.clear()
        callRegistry.clear()
        listenerMap.clear()
        currentBridgeSession = null
    }

    fun health(): PipelineHealth =
        PipelineHealth(
            decodedFrames = decodedFrames,
            rpcComplete = rpcComplete,
            rpcDiscarded = assembler.discardCount,
            decodeFailures = decodeFailures,
            needsResync = decodeFailures > RESYNC_FAILURE_THRESHOLD,
        )

    companion object {
        const val RESYNC_FAILURE_THRESHOLD = 8L
    }
}
