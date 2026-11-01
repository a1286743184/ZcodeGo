package com.zcodeapp.remote.web

/**
 * 会话恢复探针：判断页面此刻是否还停在会话里。
 *
 * 与 LivenessProbeScript 的区别：那个判的是「假活」（界面在、transport 死了），
 * 用来决定要不要自愈重载；这个判的是「是否已经掉出会话」（退回选择电脑页），
 * 用来在回前台时决定要不要重新导航。
 *
 * 两者都需要的事实来自页面自己：WS 生命周期、是否渲染出会话内容、是否在选择页。
 * 原生侧没有任何回调能拿到这些。
 */
object SessionProbeScript {
    fun build(): String = """
(function () {
  try {
    var s = window.__zrWsState || { last: 'none', at: 0, everOpen: false };
    var age = s.at ? (Date.now() - s.at) : -1;
    var text = document.body ? document.body.innerText : '';

    // 会话内容：任务行或会话容器。任一到场就说明已经进了会话。
    var hasSession = !!document.querySelector('[data-testid^="task-item-"]') ||
                     !!document.querySelector('[data-session-id]');

    // 「选择电脑」页的特征：出现「选择电脑 / 在线 / 离线」这类设备列表文案，
    // 且没有会话内容。这是用户实测回前台后停住的那个页面。
    var onPicker = !hasSession && /选择电脑|在线|离线|Choose a computer|Online|Offline/i.test(text);

    return JSON.stringify({
      ws: s.last,
      wsAgeMs: age,
      everOpen: s.everOpen === true,
      hasSession: hasSession,
      onPicker: onPicker,
      bridge: !!(window.__zrBridge && window.__zrTransport)
    });
  } catch (e) {
    return JSON.stringify({ err: String(e) });
  }
})()
""".trimIndent()
}
