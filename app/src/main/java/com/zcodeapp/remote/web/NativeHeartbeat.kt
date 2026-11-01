package com.zcodeapp.remote.web

import com.zcodeapp.remote.protocol.JsonValue

/**
 * 原生代发心跳：App 在后台时替页面把心跳发出去。
 *
 * ## 为什么需要
 *
 * 2026-10-30 真机日志（`diag-20261030-23.log`）给出了完整证据链：
 *
 * ```
 * 23:00:04 bg.health recover=true reason=silent-13284796
 *          bizAge=13284s rxAge=13284s txAge=13284s
 * 23:00:34 bg.health ... rxAge=13314s     ← 每 30 秒正好 +30s
 * ```
 *
 * 三个年龄值完全相同、且按探针周期稳定递增，说明页面最后一次收发停在
 * **19:18**，之后 3.7 小时零流量。而同一时段探针**每次都返回了 JSON**
 * （`now` 由页面内 `Date.now()` 现算，见 BackgroundProbeScript），
 * 也没有任何 `bg.health.timeout`。
 *
 * 结论：**渲染进程活着、`evaluateJavascript` 能执行，但页面的 `setTimeout`
 * 不再被调度**。这是 Chromium 对不可见页面的定时器节流/冻结，不是 CPU 休眠
 * （CPU 休眠会连原生定时器一起停，而 `bg.health` 每 30 秒从未缺席），
 * 也不是我们的可见性屏蔽失效（日志里 `sawHidden=0`，页面从未读到 hidden）。
 *
 * 前两次修复之所以无效，是因为都作用在错误的层：
 * - 屏蔽 `document.visibilityState` 只骗过页面 JS 读到的值，
 *   改不了 Chromium 内部的调度器；
 * - `BackgroundHealthGate` 只是**发现**了静默，没有恢复手段
 *   （后台整页重载又被推迟到回前台）。
 *
 * ## 做法
 *
 * 既然原生能在后台执行页面 JS，就由原生按页面原本的节奏代发心跳：
 * 通过**页面自己那条 socket**，发一条**页面自己曾经发过的帧**，
 * 只把 `client_ts` 换成当前时间。
 *
 * 三条设计约束：
 *
 * 1. **不自己拼帧**。心跳帧里有 `device_sid` 等字段，取值来自页面内部 options。
 *    自己拼一份等于把协议常量复制进 App，页面升级换字段名就会发出畸形帧，
 *    服务端可能直接断开——比不发更糟。所以帧形状由 `bootstrap.js` 从页面
 *    实际发出的帧里捕获（`window.__zrHeartbeatShape`），这里只刷新时间戳。
 * 2. **页面自己还在发就不插手**。先看 `__zrWsState.lastTxAt`：只要页面心跳
 *    还在正常节奏内（[PAGE_ALIVE_MS]），说明它没被冻结，这次 tick 直接跳过。
 *    这样前台时行为与改动前完全一致，零风险；只有真被节流时才接管。
 * 3. **socket 必须是 OPEN**。CONNECTING/CLOSING/CLOSED 时发帧只会抛异常，
 *    且那种情况下该走的是重建路径（`checkBackgroundHealth`），不是代发心跳。
 *
 * 返回结构化结果，由调用方记日志——「发了」与「没发」都必须能事后核查，
 * 否则又回到「打印了就算生效」的老问题。
 */
object NativeHeartbeat {

    /** 页面心跳间隔（实测 10 秒，见 bundle 的 `heartbeatIntervalMs ?? 1e4`）。 */
    const val PAGE_INTERVAL_MS = 10_000L

    /**
     * 页面「自己还活着」的判定窗口。
     *
     * 取 3 个心跳周期：单次抖动或一次慢 tick 不该让原生抢着代发；
     * 超过 30 秒没有任何发送，说明页面的定时器确实停了（实测冻结后是永久停，
     * 不是慢一拍）。
     */
    const val PAGE_ALIVE_MS = PAGE_INTERVAL_MS * 3

    /**
     * 代发脚本。
     *
     * @param nowMs 由原生传入的当前时间。**不用页面内的 `Date.now()`**：
     *   如果冻结影响到了时钟推进，页面时间戳本身就不可信；用原生时间更稳。
     */
    fun build(nowMs: Long): String = """
(function () {
  try {
    var s = window.__zrWsState || {};
    var sock = window.__zrMainSocket;
    var shape = window.__zrHeartbeatShape;
    var now = $nowMs;

    if (!sock) return JSON.stringify({ result: 'no-socket' });
    if (sock.readyState !== 1) {
      return JSON.stringify({ result: 'socket-not-open', readyState: sock.readyState });
    }
    if (!shape) return JSON.stringify({ result: 'no-shape' });

    // 页面自己在发就不插手：前台行为与改动前完全一致。
    var lastTx = s.lastTxAt || 0;
    var pageAge = lastTx > 0 ? (now - lastTx) : -1;
    if (pageAge >= 0 && pageAge < ${PAGE_ALIVE_MS}) {
      return JSON.stringify({ result: 'page-alive', pageAgeMs: pageAge });
    }

    var frame = {};
    for (var k in shape) {
      if (Object.prototype.hasOwnProperty.call(shape, k)) frame[k] = shape[k];
    }
    // 只刷新时间戳，其余字段原样回放（含 device_sid）。
    if (frame.client_ts !== undefined) frame.client_ts = now;
    else if (frame.clientTs !== undefined) frame.clientTs = now;

    sock.send(JSON.stringify(frame));
    return JSON.stringify({
      result: 'sent',
      pageAgeMs: pageAge,
      type: frame.type || null,
      sentAt: now
    });
  } catch (e) {
    return JSON.stringify({ result: 'error', message: String(e) });
  }
})()
""".trimIndent()

    /**
     * 解析代发结果，决定要不要记成「成功」。
     *
     * 抽成纯函数是为了在 JVM 上锁住边界：把 `page-alive` 当成失败来告警会淹没日志，
     * 把 `sent` 当成失败又会让真正的问题看起来没修好。
     */
    fun parseResult(rawJson: String?): Result {
        val text = rawJson?.trim().orEmpty()
        if (text.isEmpty() || text == "null") return Result(false, "no-result", 0L)
        val obj = runCatching { JsonValue.parse(text) as? JsonValue.Obj }.getOrNull()
            ?: return Result(false, "bad-json", 0L)
        val result = obj.str("result") ?: "unknown"
        val pageAge = obj.num("pageAgeMs") ?: -1L
        return Result(result == "sent", result, pageAge)
    }

    /**
     * @param sent     是否真的发出去了一帧
     * @param reason   脚本返回的 result 原样保留，便于区分「页面自己活着」与「失败」
     * @param pageAgeMs 页面距上次发送的毫秒数；-1 表示页面还没发过任何帧
     */
    data class Result(val sent: Boolean, val reason: String, val pageAgeMs: Long)
}
