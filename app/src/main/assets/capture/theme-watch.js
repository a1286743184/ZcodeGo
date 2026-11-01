// theme-watch.js — 把页面当前配色实时回传原生，供原生壳跟随。
//
// 为什么需要这个：原生的 PageThemeProbeScript 只在 onPageFinished 跑一次。
// 用户在页面右上角调色板里把深色切成浅色时，页面不重载、URL 不变，
// onPageFinished 不会再触发——壳层就永远停在旧配色上。
// 这里持续观察，只在「算出来的颜色真的变了」时才上报，避免刷桥。
(function () {
  if (window.__zrThemeWatch) return;
  window.__zrThemeWatch = true;

  var last = '';
  var POLL_MS = 2000;

  function read() {
    try {
      var html = document.documentElement;
      var body = document.body;
      var bg = body ? getComputedStyle(body).backgroundColor : '';
      if (!bg || bg === 'transparent' || bg === 'rgba(0, 0, 0, 0)') {
        bg = getComputedStyle(html).backgroundColor;
      }
      var fg = body ? getComputedStyle(body).color : getComputedStyle(html).color;
      var cls = (html.className || '').split(/\s+/).filter(function (c) {
        return c === 'dark' || c === 'light' || c.indexOf('theme-') === 0;
      }).join(',');
      return { bg: bg, fg: fg, cls: cls };
    } catch (e) {
      return null;
    }
  }

  function report(force) {
    var t = read();
    if (!t) return;
    var key = t.bg + '|' + t.fg;
    if (!force && key === last) return;
    last = key;
    try {
      var T = window.__zrTransport;
      if (!T) return;
      T.push({ v: 2, kind: 'theme', doc: T.doc, bg: t.bg, fg: t.fg, cls: t.cls });
      T.flush();
    } catch (e) { /* 观察失败不影响页面 */ }
  }

  // 1) 属性变化：大多数主题切换改 <html class="dark"> 或 data-theme / style 变量。
  try {
    var mo = new MutationObserver(function () { report(false); });
    mo.observe(document.documentElement, {
      attributes: true,
      attributeFilter: ['class', 'style', 'data-theme', 'data-color-mode'],
    });
    if (document.body) {
      mo.observe(document.body, { attributes: true, attributeFilter: ['class', 'style'] });
    }
  } catch (e) { /* 老内核没有 MutationObserver */ }

  // 2) 跟随系统：页面选「跟随系统」时，系统深浅切换不会改 DOM 属性，
  //    只会让媒体查询结果翻转——必须单独监听。
  try {
    var mq = window.matchMedia('(prefers-color-scheme: dark)');
    var onScheme = function () { report(false); };
    if (mq.addEventListener) mq.addEventListener('change', onScheme);
    else if (mq.addListener) mq.addListener(onScheme);
  } catch (e) { /* 无 matchMedia */ }

  // 3) 兜底轮询：主题也可能由样式表整体替换（DOM 属性不变，只有计算样式变）。
  //    只在页面可见时跑，避免后台空转。
  setInterval(function () {
    if (document.visibilityState === 'hidden') return;
    report(false);
  }, POLL_MS);

  // 4) 从后台回来立刻复核一次：期间可能已经换过主题。
  document.addEventListener('visibilitychange', function () {
    if (document.visibilityState === 'visible') report(false);
  });

  // 首帧先报一次：页面可能在注入前就已经渲染好了。
  if (document.readyState === 'complete' || document.readyState === 'interactive') {
    report(true);
  } else {
    document.addEventListener('DOMContentLoaded', function () { report(true); });
  }
})();
