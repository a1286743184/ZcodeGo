package com.zcodeapp.remote.web

import com.zcodeapp.remote.protocol.JsonValue

/**
 * 后台连接健康判据。
 *
 * 为什么需要：息屏后有两种「看起来没事、实际已死」的状态，原生侧都拿不到回调——
 *
 * 1. **CPU 被冻结**：页面 JS 停摆，WS 心跳发不出去，服务端判死连接。
 *    页面自己并不知道，`__zrWsState.last` 会一直停在 open。
 * 2. **渲染进程被 ROM 回收**：部分厂商在后台杀掉 WebView 渲染进程但保留宿主进程
 *    （前台服务把进程保住了，页面却没了）。此时 evaluateJavascript 不再有回调。
 *
 * 判据分成两部分：一部分来自页面自报（本类），另一部分来自「探测没有回调」
 * （由调用方的超时守卫判定）。
 *
 * ## 2026-10-30 重写：删掉「业务帧静默」判据
 *
 * 上一版用 `lastBizRxAt` 判断「连接活着但事件流死了」，静默超 30 分钟就整页重载。
 * 真机日志证明这是系统性误判：
 *
 * ```
 * 08:11:54 bizAge=29s    bizCount=5110
 * ...（29 分钟，ws 全程 open，一次 ws.closed 都没有）...
 * 08:41:26 bizAge=1801s  bizCount=5110   ← 判死 → 整页重载
 * ```
 *
 * 结论：**任务空闲时页面本来就不推业务帧**，bizCount 冻结是正常状态。
 * 而它造成的后果是每 30 分钟自己重载一次——重载会打断会话、丢掉输入草稿，
 * 比它想防的问题严重得多。重载后 bizCount 又从 201 冻住不动，说明这个信号
 * 根本无法区分「空闲」与「订阅死亡」，留着只会继续误伤。
 *
 * 现在只保留一个可靠判据：**入站静默时长**（`lastRxAt`）。
 *
 * 【2026-10-31 二次修正：不能取 max(rx, tx)】
 *
 * 上一版用 `max(lastRxAt, lastTxAt)`，理由是「心跳会更新两者」。原生代发心跳
 * （[NativeHeartbeat]）上线后这个假设被真机日志推翻：代发刷新了 `lastTxAt`，
 * 判据被喂成「永远健康」，而 `rxAge` 从 392s 一路涨到 1293s——
 * 连接已经单向死透（发的帧没人回），却再没触发过一次恢复。
 *
 * 所以现在只看**入站**：服务端收到心跳会回 ack（bundle 里 `heartbeatAckTimeoutMs`
 * 就是等它）。入站彻底静默 = 发的帧没人回 = 连接已死，该重建而不是继续发心跳。
 *
 * 业务帧计数仍被采集并写进日志（`bizAge` / `bizCount`），**只用于诊断**，
 * 不参与任何自动决策。
 *
 * 抽成纯函数是为了在 JVM 上锁住边界：判错的代价是「要么该重连不重连、
 * 要么好好的连接被反复打断」。
 */
object BackgroundHealthGate {

    /**
     * @param rawJson 页面探针返回的 JSON
     * @param nowMs   当前时间（便于测试注入）
     * @return 是否应当触发重连
     */
    fun evaluate(rawJson: String?, nowMs: Long = System.currentTimeMillis()): Verdict {
        val text = rawJson?.trim().orEmpty()
        if (text.isEmpty() || text == "null") return Verdict(false, "no-probe")
        val obj = runCatching { JsonValue.parse(text) as? JsonValue.Obj }.getOrNull()
            ?: return Verdict(false, "bad-json")
        if (obj.str("err") != null) return Verdict(false, "probe-error")

        val bridge = obj.value("bridge").let { it is JsonValue.Bool && it.value }
        if (!bridge) return Verdict(false, "bridge-gone")

        val ws = obj.str("ws") ?: return Verdict(false, "no-ws")
        val everOpen = obj.value("everOpen").let { it is JsonValue.Bool && it.value }
        val wsAgeMs = obj.num("wsAgeMs") ?: -1L
        val lastRxAt = obj.num("lastRxAt") ?: 0L
        val lastTxAt = obj.num("lastTxAt") ?: 0L

        if (ws == "open") {
            // 名义上开着：用**入站**时间戳判断页面是否还在收东西。
            //
            // 【2026-10-31 关键修正：判据不能看 lastTxAt】
            //
            // 上一版取 max(lastRxAt, lastTxAt)，理由是「心跳会更新两者，
            // 任一在动就说明页面 JS 活着」。真机日志证明这个假设错了：
            //
            // ```
            // 01:00:00 txAge=30s  rxAge=392s    ← 我们在发，一个字节都收不到
            // 01:12:30 txAge=30s  rxAge=1143s   ← 一路涨到 1293s
            // ```
            //
            // 原生代发心跳（NativeHeartbeat）刷新了 lastTxAt，于是判据被喂成
            // 「永远健康」，连接已经单向死透了却再没触发过一次恢复。
            // 这正是「保活看起来开着、通知却收不到」的状态。
            //
            // 现在的取舍：**只有入站在动才算活**。
            // 理由：连接是双向的，服务端在收到我们的心跳后会回 ack
            // （bundle 里 heartbeatAckTimeoutMs 就是等这个 ack）。
            // 入站彻底静默 = 我们发的帧没人回 = 连接已死，
            // 这时该做的是重建，而不是继续发心跳骗自己。
            //
            // 代价：页面若长时间没有任何下行帧（真空闲），也会被判死。
            // 但实测服务端在 pair_status_ack 之外还会周期推状态，
            // 且 120 秒窗口对「空闲」与「死连接」区分得开（见 SILENT_OPEN_MS 的说明）。
            val lastActivity = lastRxAt
            if (lastActivity <= 0L) return Verdict(false, "no-traffic-yet")

            val silentMs = nowMs - lastActivity
            return if (silentMs > SILENT_OPEN_MS) {
                // urgent：入站死透 = socket 已被服务端关闭但页面没收到 close 事件，
                // 页面自己永远不会恢复（它的恢复链路依赖事件回调，而事件不再派发）。
                // 这一条必须由原生在后台重建，不能推迟到回前台。
                Verdict(true, "silent-rx-$silentMs", urgent = true)
            } else {
                Verdict(false, "healthy")
            }
        }

        if (ws == "closed" || ws == "degraded") {
            // 断开了。注意：**这里不再由原生抢着重载**。
            //
            // 真机日志（2026-10-28 07:27-07:31）里这一条每 90 秒触发一次整页重载，
            // 形成「断开→等 30 秒→重载→又断开」的循环。根因不是这个判据太激进，
            // 而是页面在后台被 suspend 了（可见性屏蔽装得太晚，见 file-pick-guard.js）。
            // 屏蔽改到 document-start 常驻武装后，健康连接不会在后台自己关掉，
            // 这条路径自然不会再进。
            //
            // 现在它的语义收窄为「**需要兜底重连**」，由调用方在**回前台时**才执行。
            // 后台只记录、不动手：后台重载既丢状态又看不到效果，
            // 而回前台后页面自己就有完整的恢复链路（visible → recoverConnection）。
            return if (everOpen && wsAgeMs > CLOSED_GRACE_MS) {
                Verdict(true, "closed-$wsAgeMs")
            } else {
                Verdict(false, "reconnecting")
            }
        }
        // 'none'（从没连上）或未知：不动，交给正常的建连流程。
        return Verdict(false, "never-opened")
    }

    /**
     * 入站多久没有任何数据就判为「连接已死」。
     *
     * 取 120 秒。依据（2026-10-31 真机日志）：
     *
     * - 健康时入站年龄稳定在 0-3 秒：每次代发心跳服务端都会回 ack，
     *   `01:15:30 → 01:22:01` 连续 14 次探针全是 `rxAge=0s`。
     * - 故障时 `rxAge` 单调增长且**从不回落**（392s → 1293s），
     *   与健康的锯齿形态截然不同，120 秒足以分开这两种形态。
     * - 服务端 ack 超时是 30 秒（bundle 里 `heartbeatAckTimeoutMs ?? 3e4`），
     *   120 秒留出 4 倍余量，不会因为一次慢 ack 就误判。
     *
     * 【历史】曾取 10 分钟，是因为当时判据取 max(rx, tx) 且掺了业务帧静默，
     * 空闲会被误伤；现在只看入站、健康态恒为 0，不需要那么宽。
     * 更早还取过 90 秒，当时误伤来自业务帧判据（`silent-91226`），
     * 与入站静默不是同一个信号。
     */
    private const val SILENT_OPEN_MS = 120_000L

    /**
     * 断开后给页面多久自己重连。
     *
     * 实测页面自身重连多在 1-3 秒完成；30 秒覆盖慢网与一次退避重试。
     * 注意这个宽限期只在**回前台判定**时生效（后台不重载）。
     */
    private const val CLOSED_GRACE_MS = 30_000L

    /**
     * @param recover 是否应当触发恢复
     * @param reason  判定依据（原样写进日志，便于事后核查）
     * @param urgent  是否必须在**后台立即**恢复。
     *   true 只用于「入站死透」：那种状态下页面自己永远不会恢复
     *   （socket 已被服务端关闭，但页面收不到 close 事件，定时器也停了），
     *   等回前台再修只会让用户多等一次冷加载。
     *   false 的恢复可以推迟到回前台（页面自己还有恢复链路）。
     */
    data class Verdict(
        val recover: Boolean,
        val reason: String,
        val urgent: Boolean = false,
    )
}
