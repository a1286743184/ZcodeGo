package com.zcodeapp.remote.web

import com.zcodeapp.remote.diag.DiagLog
import com.zcodeapp.remote.event.EventNormalizer
import com.zcodeapp.remote.event.NotificationIntent
import com.zcodeapp.remote.event.SourceLeaseCoordinator
import com.zcodeapp.remote.event.SourceOwner
import com.zcodeapp.remote.event.TaskReducer
import com.zcodeapp.remote.notify.NotificationDispatcher
import com.zcodeapp.remote.notify.RouteTarget
import com.zcodeapp.remote.protocol.ChannelEvent
import com.zcodeapp.remote.protocol.EnvelopeEvent
import com.zcodeapp.remote.protocol.PipelineListener
import com.zcodeapp.remote.protocol.ProtocolPipeline

/**
 * 一次远控连接的会话对象：持有该连接的协议管线、归一器与 reducer，
 * 是通知意图的唯一出口。连接切换时整段弃置，epoch 使旧回调失效。
 */
class RemoteSession(
    val connectionId: String,
    val connectionUrl: String,
    private val reducer: TaskReducer,
    private val dispatcher: NotificationDispatcher,
    private val leases: SourceLeaseCoordinator,
    private val onStateChange: (PageState) -> Unit = {},
) : PipelineListener {

    private val lease = leases.acquire(connectionId, SourceOwner.PRIMARY_PAGE)
    private val normalizer = EventNormalizer({ connectionId }, { lease.epoch })
    val pipeline = ProtocolPipeline(this)

    private var current = PageState.IDLE

    private fun setState(next: PageState) {
        if (current != next) {
            current = next
            onStateChange(next)
        }
    }

    fun onWsText(direction: com.zcodeapp.remote.protocol.Direction, text: String) {
        if (!lease.isCurrent(leases)) return
        pipeline.onWebSocketText(direction, text)
    }

    override fun onChannelEvent(event: ChannelEvent) {
        if (!lease.isCurrent(leases)) return
        val observations = normalizer.fromChannelEvent(event)
        if (observations.isEmpty()) return
        val intents = ArrayList<NotificationIntent>(observations.size)
        for (obs in observations) intents.addAll(reducer.reduce(obs))
        if (intents.isNotEmpty()) {
            // M3 时延口径起点：原生收到完整有效事件并产出通知意图的这一刻。
            // 只记类别、任务 id 与数量，不记正文；t= 与派发行的 t= 同进程可直接相减。
            // 意图身份也记下来：dispatch ops=[] 时靠它区分「正确去重」与「丢了通知」。
            if (com.zcodeapp.remote.BuildConfig.DEBUG) {
                val ids = intents.joinToString(",") { it ->
                    when (it) {
                        is NotificationIntent.TaskTerminal ->
                            "T:${it.runKey.take(12)}:${it.failed}"
                        is NotificationIntent.Attention ->
                            "A:${it.interactionKey.take(12)}:${it.kind}"
                        is NotificationIntent.ResolveAttention ->
                            "R:${it.interactionKey.take(12)}"
                    }
                }
                android.util.Log.i(
                    "ZcodeRemoteDiag",
                    "m3-reduce obs=${observations.joinToString(",") { it.kind.name }}" +
                        " intents=${intents.size} ids=$ids" +
                        " task=${observations.firstOrNull()?.taskKey?.taskId}" +
                        " t=${System.currentTimeMillis()}",
                )
            }
            dispatcher.submit(intents) { addr -> RouteTarget(connectionUrl, addr.taskId, null) }
        }
    }

    override fun onEnvelope(event: EnvelopeEvent) {
        when (event.zcodeType) {
            "auth_ack" -> {
                setState(PageState.PAIRED)
                reducer.reset(connectionId) // 新连接代次：重建基线
            }
            // 【实测 2026-10-25】bridge-degraded 是服务端桥代次切换的正常生命周期事件：
            // 真实样本里带 reason=terminal-owner-ended / rpc-transport-fault 与 recoveryId，
            // 服务端随后自行恢复（bridgeGeneration 递增）。把它当成永久 DEGRADED 会让
            // 页面明明正常工作却一直显示降级状态。
            // 只有「没有 recoveryId 的降级」才视为需要用户介入的异常。
            "bridge-degraded" -> {
                val recovering = event.payload?.str("recoveryId") != null
                if (!recovering) setState(PageState.DEGRADED)
            }
        }
    }

    override fun onHealth(health: com.zcodeapp.remote.protocol.PipelineHealth) {
        if (health.needsResync) setState(PageState.DEGRADED)
    }

    fun onDomCandidate(candidate: String, taskHint: String?) {
        if (!lease.isCurrent(leases)) return
        val obs = normalizer.domCandidate(candidate, taskHint, null) ?: return
        val intents = reducer.reduce(obs)
        if (intents.isNotEmpty()) {
            dispatcher.submit(intents) { addr -> RouteTarget(connectionUrl, addr.taskId, null) }
        }
    }

    /**
     * 页面 WS 健康事件。
     *
     * 【2026-10-26 实测修正】close 是页面生命周期的一部分，不是故障结论：
     * 页面重载、桥代次切换、切后台再回来都会先 close 再 open。实测一轮里 health 事件十几条。
     * 旧实现把任何一次 close 都立刻置为 CLOSED，UI 顶栏就出现「正在重连…」红条，
     * 而此时页面内容完全正常——用户看到的就是「明明能用却报错」。
     *
     * 【2026-10-26 二次修正】还有一类误报发生在**首次建连**阶段：
     * 页面刚加载时 WS 会先被创建再被替换/关闭一次，此时一次 open 都还没发生过。
     * 那种 close 不代表「连接断了」，只代表「还没连上」。所以：
     * 只有成功连上过（everConnected）之后的 close 才进入断线确认流程。
     */
    fun onBridgeHealth(state: String, reasonCode: String? = null, detail: String = "") {
        // 落盘：这是定位「进入远控页时闪多次重连」的关键时序。
        // 每次 open/closed 都带时间戳与是否已连上过，事后可从 diag.log 还原完整序列。
        // 【2026-10-27】补上 reasonCode 与桥侧计数：旧实现只记 ever/pending，
        // 队列溢出（QUEUE_CAP）与积压（QUEUE_BACKLOG）在日志里完全看不出来。
        DiagLog.i(
            "ws.$state",
            buildString {
                append("ever=").append(everConnected).append(" pending=").append(pendingCloseSince != 0L)
                if (!reasonCode.isNullOrEmpty()) append(" reason=").append(reasonCode)
                if (detail.isNotEmpty()) append(' ').append(detail)
            },
        )
        when (state) {
            "open" -> {
                everConnected = true
                pendingCloseSince = 0L
                if (current != PageState.PAIRED) setState(PageState.CONNECTING)
            }
            "closed" -> {
                // 首次建连阶段的 close 直接忽略：那不是「断了」，是「还没连上」。
                if (!everConnected) return
                if (pendingCloseSince == 0L) pendingCloseSince = System.currentTimeMillis()
            }
            "degraded" -> {
                // 同理：还没连上过时的 error 事件不当作降级故障提示给用户。
                if (!everConnected) return
                // QUEUE_CAP/QUEUE_BACKLOG 是桥侧积压，不是连接故障：置 DEGRADED 会误报。
                if (reasonCode == "QUEUE_CAP" || reasonCode == "QUEUE_BACKLOG") return
                if (current != PageState.CLOSED) setState(PageState.DEGRADED)
            }
        }
    }

    /**
     * 断线确认：宽限期过了仍未恢复才置为 CLOSED。
     * 由控制器在页面加载完成时调用，不依赖常驻定时器。
     */
    fun confirmClosedIfStillDown() {
        if (!everConnected) return
        val since = pendingCloseSince
        if (since == 0L) return
        if (System.currentTimeMillis() - since < CLOSE_CONFIRM_GRACE_MS) return
        pendingCloseSince = 0L
        setState(PageState.CLOSED)
    }

    /** 是否成功连上过。首次建连期间的 close/error 不算故障。 */
    private var everConnected = false
    private var pendingCloseSince = 0L

    /**
     * 页面自报「在会话里且连接是通的」时调用：把状态升级为 PAIRED。
     *
     * 【2026-10-31 面板假报「已断开」根因】PAIRED 原本只由协议管线的
     * `auth_ack` 信封设置。但 auth_ack 只在页面**全新配对**时出现：
     * 恢复、重连、后台重建后，页面用已存的会话凭据重新握手，
     * 服务端不再重发 auth_ack——于是重建出来的会话对象永远停在
     * CONNECTING，壳子面板一直显示「已断开」，即使用户正在会话里
     * 正常聊天（0.4.1 真机日志 11:07-11:08 实录）。
     *
     * 页面自报的 hasSession + ws=open（SessionProbe 的 in-session 判定）
     * 就是「已连接」的直接证据，比等一个不会再来的信封可靠。
     */
    fun markPaired() {
        everConnected = true
        pendingCloseSince = 0L
        if (current != PageState.PAIRED) setState(PageState.PAIRED)
    }

    fun dispose() {
        pipeline.reset()
        reducer.reset(connectionId)
    }

    private companion object {
        /** 4 秒：实测页面 WS 重连多在 1-2 秒内完成，留出余量避免误报。 */
        const val CLOSE_CONFIRM_GRACE_MS = 4_000L
    }
}
