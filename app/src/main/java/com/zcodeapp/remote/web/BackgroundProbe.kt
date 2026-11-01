package com.zcodeapp.remote.web

/**
 * 后台健康探针：页面自报 WS 状态、最近收发时间、以及**屏蔽是否真的生效**。
 *
 * 与 SessionProbeScript 的区别：
 * - SessionProbe：判断「是否掉出会话」（回前台时用，决定要不要重新导航）
 * - 本探针：判断「连接是否真的还活着」+「屏蔽是否真在挡」（后台巡检用）
 *
 * 后者必须看收发时间戳而不是只看 `last: 'open'`：
 * CPU 被冻结时 WS 对象仍然是 OPEN，只是心跳发不出去，状态字段不会变。
 *
 * 【2026-10-30 新增屏蔽取证】
 *
 * 0.3.5 的真机日志里 `web.hold fn=holdBegin` 打了无数次，但页面回前台照样
 * 自己 recoverConnection()（`WS_CLOSED_CLEAN`）。那行日志只证明**调用**发生了，
 * 不证明屏蔽生效——它是无条件打印的。
 *
 * 这里补三个页面视角的事实，全部来自 shield 自己的计数器：
 *   blocked  我们拦下的事件数（>0 说明捕获阶段确实在起作用）
 *   passed   我们放行的次数（连接不健康时的正常行为）
 *   sawHidden 页面**实际读到** hidden 的次数（应为 0；非 0 = 屏蔽失效，铁证）
 *
 * sawHidden 是关键：它不受原生日志措辞影响，直接反映页面看到的世界。
 */
object BackgroundProbeScript {
    fun build(): String = """
(function () {
  try {
    var s = window.__zrWsState || {
      last: 'none', at: 0, everOpen: false,
      lastRxAt: 0, lastTxAt: 0, lastBizRxAt: 0, bizRxCount: 0
    };
    var sh = window.__zrVisibilityShield;
    var snap = null;
    if (sh && sh.snapshot) {
      try { snap = JSON.parse(sh.snapshot()); } catch (e) { snap = null; }
    }
    var now = Date.now();
    return JSON.stringify({
      ws: s.last,
      wsAgeMs: s.at ? (now - s.at) : -1,
      everOpen: s.everOpen === true,
      lastRxAt: s.lastRxAt || 0,
      lastTxAt: s.lastTxAt || 0,
      lastBizRxAt: s.lastBizRxAt || 0,
      bizRxCount: s.bizRxCount || 0,
      // 心跳是否真的在走：这两个年龄比 bizAge 更能说明页面 JS 还活着
      rxAgeMs: s.lastRxAt ? (now - s.lastRxAt) : -1,
      txAgeMs: s.lastTxAt ? (now - s.lastTxAt) : -1,
      // 屏蔽取证（见类注释）
      shieldInstalled: !!sh,
      shieldGuard: snap ? snap.guard : null,
      shieldHold: snap ? snap.hold : null,
      shieldHealthy: snap ? snap.wsHealthy : null,
      shieldBlocked: snap ? snap.blocked : null,
      shieldPassed: snap ? snap.passed : null,
      shieldSawHidden: snap ? snap.sawHidden : null,
      shieldDefineFailed: snap ? snap.defineFailed : null,
      // 原生代发心跳的取证（2026-10-31）：
      //   beatShape   页面是否已捕获到自己的心跳帧形状（false = 代发没有素材）
      //   sockState   主 socket 的 readyState（1=OPEN；其它值时代发会跳过）
      //   relayUrl    bootstrap 实际认作主连接的 URL 形态（scheme+host+path，
      //               不含 query）。匹配规则失效时，这里能直接看出真实形态是什么。
      // 这几个值回答的是「代发心跳为什么没生效」——没有它们，只能看到
      // native.beat skip，却不知道是没 socket、没帧模板，还是匹配规则没命中。
      beatShape: !!(window.__zrHeartbeatShape),
      sockState: (window.__zrMainSocket ? window.__zrMainSocket.readyState : -1),
      relayUrl: window.__zrRelayUrlShape || null,
      bridge: !!(window.__zrBridge && window.__zrTransport),
      now: now
    });
  } catch (e) {
    return JSON.stringify({ err: String(e) });
  }
})()
""".trimIndent()
}
