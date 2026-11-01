package com.zcodeapp.remote.web

/**
 * 自动重连脚本。
 *
 * 场景（实测 2026-10-25）：桌面端浏览器持有登录态时，页面打开后停在连接页，
 * 需要用户手动点「重新连接」才真正建连。App 作为远控壳应无感完成这一步。
 *
 * 官方源码依据：`packages/web/src/web-remote-control/domain/webRemoteControlScreenCopy.ts`
 * 里连接页各失败分支的 action 文案都是「重新连接」（英文 "Reconnect"），
 * 所以按文案匹配即可；同时要求页面上确实处于连接页（出现连接阶段文案或重连说明），
 * 避免在正常会话页误点。
 *
 * 安全约束：
 * - 只在「明确处于连接/失败页」时点击，正常会话页绝不点。
 * - 每次文档只点有限次（默认 3），带间隔，避免页面异常时疯狂重试。
 * - 不点击任何其它按钮，不改变页面连接参数。
 */
object AutoReconnectScript {
    private const val MAX_ATTEMPTS = 3
    private const val TICK_MS = 1500

    fun build(): String = """
(function () {
  var KEY = '__zrReconnectState';
  var st = window[KEY];
  if (st && st.doc === location.href) return; // 同文档只装一次
  var state = { doc: location.href, attempts: 0, stopped: false };
  window[KEY] = state;

  var visible = function (el) {
    if (!el || !el.isConnected || el.getClientRects().length === 0) return false;
    for (var p = el; p && p.nodeType === 1; p = p.parentElement) {
      var s = window.getComputedStyle(p);
      if (p.hidden || p.getAttribute('aria-hidden') === 'true' || p.hasAttribute('inert') ||
          s.display === 'none' || s.visibility === 'hidden' || parseFloat(s.opacity) === 0) return false;
    }
    return true;
  };

  // 是否处于「连接/失败」页：出现重连文案、或连接阶段文案，且没有任务会话内容
  var onConnectScreen = function () {
    var text = document.body ? document.body.innerText : '';
    if (!text) return false;
    var hasAction = /重新连接|Reconnect/i.test(text);
    var hasStage = /正在连接|正在认证|等待桌面端|已配对|连接已中断|连接失败|Connecting|Authenticating|Waiting for|Paired|Disconnected|Failed to connect/i.test(text);
    // 正常会话页有输入框/任务行，不能误点
    var hasConversation = document.querySelector('[data-session-id]') ||
                          document.querySelector('[data-testid^="task-item-"]');
    return hasAction && hasStage && !hasConversation;
  };

  var findAction = function () {
    var nodes = document.querySelectorAll('button, [role="button"], a');
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      var label = (el.innerText || '') + ' ' + (el.getAttribute('aria-label') || '');
      if (!/重新连接|Reconnect/i.test(label)) continue;
      if (!visible(el)) continue;
      return el;
    }
    return null;
  };

  var iv = setInterval(function () {
    if (state.stopped) { clearInterval(iv); return; }
    if (state.attempts >= $MAX_ATTEMPTS) { clearInterval(iv); return; }
    if (!onConnectScreen()) { clearInterval(iv); return; } // 已不在连接页：结束
    var btn = findAction();
    if (!btn) return;
    state.attempts++;
    try { btn.click(); } catch (e) { /* 忽略 */ }
  }, $TICK_MS);

  // 页面切走后停止
  setTimeout(function () { state.stopped = true; }, 60_000);
})();
""".trimIndent()
}
