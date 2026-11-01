// nav.js — 页面层级探针与分层返回动作。
//
// 为什么需要它：远控页是 SPA，URL 基本不变，WebView 的历史栈对它没有意义。
// 旧的返回实现只有 `canGoBack → goBack`，于是两种结果都错：
// 历史为空时直接退出（用户还在设置页里），历史有别的页面时退回 OAuth 或 404。
// 真正决定「该退哪一层」的事实只存在于页面 DOM：有没有打开的浮层、
// 有没有侧栏、是不是停在设置页、是不是还在会话里。
//
// 这个脚本把那些事实收敛成三层 API，原生只调它，不再自己猜：
//   __zrNav.state()   → 当前层级（诊断用）
//   __zrNav.back()    → 执行一次分层后退
//
// 层级从内到外：popup → sidepane → onboarding → settings → chat → list → connect → unknown。
// 只有落到 list/connect/unknown（页面最外层）时才返回 action=shell，
// 由原生退到自己的壳子。这一条就是「什么时候退到壳子」的唯一判据。
//
// DOM 判据全部来自线上 bundle 实测（2026-10-31）：
//   data-mobile-page="chat"         移动端会话页根
//   data-mobile-side-pane-overlay   移动端侧滑面板遮罩
//   data-testid="settings-page"     设置页
//   data-testid="settings-back-button" 设置页返回
//   data-testid="task-new-button"   任务列表（列表页唯一稳定锚点）
//   aria-label="返回任务首页"        会话页回列表（i18n key mobileShell.backHome）
//   aria-label="退出引导"            引导页退出
//   role=menu/listbox/dialog + data-state=open  Radix 浮层
(function () {
  if (window.__zrNav) return;

  function visible(el) {
    if (!el || !el.isConnected || el.getClientRects().length === 0) return false;
    for (var p = el; p && p.nodeType === 1; p = p.parentElement) {
      var s = window.getComputedStyle(p);
      if (p.hidden || p.getAttribute('aria-hidden') === 'true' || p.hasAttribute('inert') ||
          s.display === 'none' || s.visibility === 'hidden' || parseFloat(s.opacity) === 0) return false;
    }
    return true;
  }

  function firstVisible(sel) {
    var list = document.querySelectorAll(sel);
    for (var i = 0; i < list.length; i++) {
      if (visible(list[i])) return list[i];
    }
    return null;
  }

  var POPUP_SEL =
    'div[role="menu"][data-state="open"],' +
    'div[role="listbox"][data-state="open"],' +
    'div[role="dialog"][data-state="open"],' +
    'div[data-slot="popover-content"][data-state="open"],' +
    'div[data-slot="hover-card-content"][data-state="open"]';

  /**
   * 最上层的浮层。
   *
   * 必须排除「设置页 / 引导页自身被 role=dialog 包裹」的情况：
   * 那种节点是层级本身，不是叠在它上面的浮层。若误判成浮层，
   * 用户按返回会被直接踢出设置页——正是要修的那种错层。
   */
  function popup() {
    var list = document.querySelectorAll(POPUP_SEL);
    for (var i = 0; i < list.length; i++) {
      var el = list[i];
      if (!visible(el)) continue;
      if (el.querySelector('[data-testid="settings-page"]')) continue;
      if (el.querySelector('[data-testid="onboarding-page"]')) continue;
      return el;
    }
    return null;
  }

  /**
   * 移动端侧滑面板是否开着。
   *
   * 不能用 computed opacity 判定：遮罩开合是 class 切换 + 200ms 过渡，
   * 过渡期间 opacity 仍是旧值（实测打开后 20ms 为 0）。用与 class 同步翻转的
   * aria-hidden / pointer-events-none，才不会在「刚打开就按返回」时漏判。
   */
  function sidePane() {
    var o = document.querySelector('[data-mobile-side-pane-overlay="true"]');
    if (!o) return null;
    var cls = o.getAttribute('class') || '';
    if (o.getAttribute('aria-hidden') === 'true') return null;
    if (cls.indexOf('pointer-events-none') >= 0) return null;
    return o;
  }

  function page(testId) {
    var el = document.querySelector('[data-testid="' + testId + '"]');
    return el && visible(el) ? el : null;
  }

  function chatPage() {
    var el = document.querySelector('[data-mobile-page="chat"]');
    return el && visible(el) ? el : null;
  }

  function listPage() {
    // task-new-button 是列表页唯一在折叠/展开两种状态下都存在的锚点。
    // 任务行 task-item-* 在全部项目折叠时为 0，不能用它判定。
    var entry = document.querySelector('[data-testid="task-new-button"]');
    return entry && visible(entry) ? entry : null;
  }

  /** 是否停在连接/失败页（页面自己的恢复入口在这个页面上）。 */
  function connectScreen() {
    var text = document.body ? (document.body.innerText || '') : '';
    if (!text) return false;
    var hasAction = /重新连接|Reconnect/i.test(text);
    var hasStage = /正在连接|正在认证|等待桌面端|已配对|连接已中断|连接失败|正在自动重连|Connecting|Authenticating|Waiting for|Paired|Disconnected|Failed to connect|Reconnecting/i.test(text);
    return hasAction && hasStage && !chatPage() && !listPage();
  }

  function layer() {
    if (popup()) return 'popup';
    if (sidePane()) return 'sidepane';
    if (page('onboarding-page')) return 'onboarding';
    if (page('settings-page')) return 'settings';
    if (chatPage()) return 'chat';
    if (listPage()) return 'list';
    if (connectScreen()) return 'connect';
    return 'unknown';
  }

  /** 会话页的「返回任务首页」。优先在会话页内找，避免命中别处的同名按钮。 */
  function backHomeButton() {
    var scope = chatPage() || document;
    var inScope = firstVisibleIn(scope, 'button[aria-label="返回任务首页"], button[aria-label="Back to task home"]');
    if (inScope) return inScope;
    // 旧版页面把回列表的入口放在侧栏，文案是「返回工作区」。
    return firstVisible('button[aria-label="返回工作区"], button[aria-label="Back to workspace"]');
  }

  function firstVisibleIn(scope, sel) {
    var list = scope.querySelectorAll(sel);
    for (var i = 0; i < list.length; i++) {
      if (visible(list[i])) return list[i];
    }
    return null;
  }

  function settingsBackButton(sp) {
    return firstVisibleIn(
      sp,
      'button[data-testid="settings-back-button"],' +
      'button[aria-label="返回工作区"], button[aria-label="Back to workspace"]'
    );
  }

  function reconnectButton() {
    var nodes = document.querySelectorAll('button, [role="button"], a');
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      var label = (el.innerText || '') + ' ' + (el.getAttribute('aria-label') || '');
      if (!/重新连接|Reconnect/i.test(label)) continue;
      if (!visible(el)) continue;
      return el;
    }
    return null;
  }

  /**
   * 一次分层后退。
   *
   * 返回约定：
   *   { handled:true,  action:'page'|'sidepane'|'settings'|'chat'|'onboarding', layer }
   *       页面自己消化了这次返回，原生什么都不做。
   *   { handled:false, action:'escape', layer:'popup' }
   *       浮层要用受信任的 Escape 交给 Radix 的浮层栈关，不能合成 click
   *       （合成点击会把「打开/关闭」双触发，出现开关闪烁）。
   *   { handled:false, action:'shell', layer }
   *       页面已在最外层，该退到壳子了。
   *
   * 任何异常都退化成 shell：页面卡住时用户按返回的意图是「离开」，不是「留在原地」。
   */
  function back() {
    var l = layer();
    try {
      if (l === 'popup') {
        return { handled: false, action: 'escape', layer: l };
      }
      if (l === 'sidepane') {
        var pane = sidePane();
        var collapse = firstVisibleIn(pane, 'button[aria-label*="收起侧边面板"], button[aria-label*="Collapse side pane"]');
        var btn = collapse || firstVisibleIn(pane, 'button');
        if (btn) {
          btn.click();
          return { handled: true, action: 'sidepane', layer: l };
        }
      }
      if (l === 'onboarding') {
        var ob = page('onboarding-page');
        var exit = firstVisibleIn(ob, 'button[aria-label="退出引导"], button[aria-label="Exit guide"]');
        if (exit) {
          exit.click();
          return { handled: true, action: 'onboarding', layer: l };
        }
      }
      if (l === 'settings') {
        var sp = page('settings-page');
        var sb = settingsBackButton(sp);
        if (sb) {
          sb.click();
          return { handled: true, action: 'settings', layer: l };
        }
      }
      if (l === 'chat') {
        var home = backHomeButton();
        if (home) {
          home.click();
          return { handled: true, action: 'chat', layer: l };
        }
      }
    } catch (e) {
      return { handled: false, action: 'shell', layer: l, error: String(e) };
    }
    return { handled: false, action: 'shell', layer: l };
  }

  function state() {
    var s = window.__zrWsState || { last: 'none', at: 0 };
    var age = s.at ? (Date.now() - s.at) : -1;
    return {
      layer: layer(),
      ws: s.last,
      wsAgeMs: age,
      everOpen: s.everOpen === true,
      bridge: !!(window.__zrBridge && window.__zrTransport),
      // 诊断用：能看出「是没匹配上还是页面没连」
      relayShape: window.__zrRelayUrlShape || null
    };
  }

  window.__zrNav = {
    state: function () { return JSON.stringify(state()); },
    back: function () { return JSON.stringify(back()); },
    layer: layer
  };
})();
