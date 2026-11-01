// diag.js — 页面侧故障取证：未捕获异常 / 未处理 Promise / console.error / 最近一次点击
//
// 为什么需要它：远控页的故障大多发生在页面自己的 JS 里（例如 transport 被 dispose 之后
// 每次点击都抛 "transport stopped"）。这类错误在原生侧完全不可见——WebView 只上报
// 页面加载、渲染进程崩溃和子资源 HTTP 错误，页面的 promise 拒绝不会触发任何一个回调。
// 没有这份取证，用户只能描述「点了没反应」，我们拿不到任何可复盘的现场。
//
// 约束：
// - 只观察，不修改页面行为：不 preventDefault、不拦截事件、不改 DOM。
// - 只上报类别与截断后的文本；URL 的 query/fragment 一律抹掉（里面有 sid/token/code）。
// - 每条文档最多 40 条，相同内容 5 秒内不重复，避免异常循环把桥打爆。
// - 任何失败都静默，取证本身绝不影响页面。
(function () {
  if (window.__ZR_DIAG) return;
  window.__ZR_DIAG = 1;

  var MAX_TEXT = 400;
  var MAX_PER_DOC = 40;
  var DEDUPE_MS = 5000;
  var seen = Object.create(null);
  var sent = 0;
  var lastTap = null;

  function scrub(s) {
    if (typeof s !== 'string') {
      try { s = String(s); } catch (e) { return '-'; }
    }
    // URL 只留到 path：query 与 fragment 里是凭据。
    s = s.replace(/(https?:\/\/[^\s"'<>]+)/g, function (u) {
      return u.replace(/[?#][^\s"'<>]*/g, '');
    });
    // 长串（base64 / JWT / 哈希）整体打码，只留长度，便于判断是哪一段。
    s = s.replace(/[A-Za-z0-9_-]{48,}/g, function (m) { return '<' + m.length + 'ch>'; });
    s = s.replace(/\s+/g, ' ').trim();
    if (s.length > MAX_TEXT) s = s.slice(0, MAX_TEXT) + '…';
    return s || '-';
  }

  function post(level, code, text) {
    try {
      var T = window.__zrTransport;
      if (!T || sent >= MAX_PER_DOC) return;
      var key = level + '|' + code + '|' + text;
      var now = Date.now();
      if (seen[key] && now - seen[key] < DEDUPE_MS) return;
      seen[key] = now;
      sent++;
      T.push({
        v: 2,
        kind: 'diag',
        doc: T.doc,
        level: level,
        code: code,
        text: scrub(text),
        tap: lastTap ? lastTap.sel : null,
        tapAgeMs: lastTap ? (now - lastTap.at) : null
      });
    } catch (e) { /* 取证失败不影响页面 */ }
  }

  // 最近一次点击：只留选择器与文案。这是回答「点哪里没反应」的唯一线索，
  // 但按次上报会把桥打满，所以只留在内存里，随下一条错误一起带出去。
  function describe(el) {
    if (!el || el.nodeType !== 1) return '-';
    var s = el.tagName.toLowerCase();
    var testid = el.getAttribute('data-testid');
    if (testid) s += '[data-testid=' + testid + ']';
    var aria = el.getAttribute('aria-label');
    if (aria) s += '[aria-label=' + aria.slice(0, 24) + ']';
    var txt = (el.innerText || '').trim().slice(0, 32);
    if (txt) s += ' "' + txt + '"';
    return s;
  }

  window.addEventListener('pointerdown', function (e) {
    lastTap = { sel: describe(e.target), at: Date.now() };
  }, true);

  window.addEventListener('error', function (e) {
    // 【2026-10-27 修正】这个监听器同时会收到「资源加载失败」事件：
    // <img>/<svg>/<script> 加载失败时 target 是元素而不是 window，
    // 且没有 message / filename / lineno。旧实现不看这点，把每个 404 的图标
    // 都当成 JS 异常上报，真机日志里 28 条 uncaught 全部是资源 404，
    // 把真正的脚本错误完全淹没了。
    var t = e && e.target;
    if (t && t !== window && t.nodeType === 1) {
      // 资源失败已经由原生的 onReceivedHttpError 记录，这里不重复上报。
      return;
    }
    var where = e && e.filename ? (e.filename + ':' + (e.lineno || 0)) : '-';
    post('error', 'uncaught', (e && e.message ? e.message : 'error') + ' @' + where);
  }, true);

  window.addEventListener('unhandledrejection', function (e) {
    var r = e && e.reason;
    var msg = r && r.message ? r.message : (typeof r === 'string' ? r : 'rejection');
    post('error', 'unhandled-rejection', msg);
  });

  if (window.console && typeof console.error === 'function') {
    var origError = console.error.bind(console);
    console.error = function () {
      try {
        var parts = [];
        for (var i = 0; i < arguments.length; i++) {
          var a = arguments[i];
          parts.push(a && a.message ? a.message : a);
        }
        post('error', 'console', parts.join(' '));
      } catch (e) { /* 静默 */ }
      return origError.apply(null, arguments);
    };
  }
})();
