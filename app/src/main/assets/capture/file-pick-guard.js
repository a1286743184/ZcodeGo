// file-pick-guard.js — 可见性屏蔽：让页面在我们希望的时候认为自己是可见的
//
// 为什么需要它（三处用途，同一个根因）：
// 远控页内部有生命周期助手（反编译自 3.16.3 bundle）：
//
//   let i = false;                       // 「已 suspend」标志
//   onSuspend = () => { i = true; transport.suspend(); }
//   onRecover = (e) => { if (!i) return; i = false; recover(e); }
//   监听 document 的 visibilitychange / freeze / resume / pagehide
//   监听 window 的 pageshow / online
//
// 关键点：**onRecover 只在 i 为真时才跑**。而 i 只有 onSuspend 会置真。
// 所以只要页面从来没收到过 hidden，它就永远不会在回前台时跑
// recoverConnection()——那一步会 reconnectNow() 主动关掉健康连接、
// 重握手、重拉工作区（实测约 10 秒），失败还会 window.location.reload()。
// 用户看到的就是「等十几秒，然后整页刷新，输入的字全没了」。
//
// 用途一：系统文件选择器盖住 App（2026-10-27 实测）。
// 点「添加附件」拉起选择器时 App 被盖住，页面据此挂起 transport；选完回来要走一遍重建，
// 而上传请求正好落在重建窗口里被丢弃，文件挂在输入框上方一直卡着。
//
// 用途二：后台保活（这是「保活了却收不到通知」的真正原因）。
// 用户开了保活后切后台/息屏，App 本来就没有真的「不可见」——进程活着、前台服务在跑、
// 屏幕只是关了。但 Activity 一暂停，WebView 仍会派发 hidden，页面就自己把 WS 关了。
//
// 用途三（2026-10-30 新增，这是「每次回来都刷新」的直接原因）：**hold 常驻武装**。
// 旧实现在 onPause 里才调 keepAliveBegin——但那一刻 Chromium 已经把
// visibilitychange(hidden) 派发完了，我们的脚本晚了一拍，页面照样 suspend。
// 这是竞态，所以表现是「有时候还行、有时候刷好几次」。
// 现在改为**页面一加载就武装**（holdBegin），此后整个文档生命周期都在挡，
// 不再依赖 onPause 那一刻的时序。
//
// 拦哪些事件、什么状态算健康：**不在本文件写死**，读 window.__zrShieldConfig
// （由原生 ShieldPolicy 在 document-start 注入）。两边各写一份常量迟早漂移，
// 而漂移的症状是「特定后台时序下屏蔽失效」，极难复现。
(function () {
  if (window.__zrVisibilityShield) return;

  var cfg = window.__zrShieldConfig || {};
  var BLOCKED = cfg.events || ['visibilitychange', 'pagehide', 'freeze', 'resume', 'pageshow', 'online'];

  // 三个场景各自独立标志，任一为真就屏蔽。
  // 用独立标志而不是一个布尔：选文件期间用户真的切后台时，
  // 不该被文件守卫误判成「仍然可见」。
  var filePick = false;
  // hold：常驻武装。
  //
  // 初值来自 document-start 注入的配置：原生在注入时就把用户的保活设置带进来了，
  // 脚本在这里同一拍就武装，不存在「等 onPause 再装、但 hidden 已经派发过」的竞态窗口。
  var hold = cfg.hold === true;

  function shielded() {
    // 无条件屏蔽，**不看连接状态**。
    //
    // 【2026-10-30 修正】上一版这里是「只在连接健康时才挡，closed/degraded 放行」，
    // 理由是「断了还挡，页面收不到可见性变化就永远无法自愈」。
    // 真机日志证伪了这个设计：连接在后台短暂抖成 closed 的那一瞬间，
    // 屏蔽开洞；若恰好有 hidden 派发，页面 suspend 并永久记住「已 suspend」，
    // 此后**每次**回前台都走 recoverConnection()（主动 close + 重连 + 可能整页 reload）。
    // 日志里的 WS_CLOSED_CLEAN 就是这个证据。
    //
    // 现在的取舍：页面永远看不到 hidden，恢复权交给原生
    // （后台重建 + 回前台 probeAndRecoverSession 判定）。
    // 与 ShieldPolicy.shielded() 一一对应，改一处必须同步改另一处。
    return filePick || hold;
  }

  // 取证计数：这些数字是判断「屏蔽到底有没有用」的唯一硬证据。
  //
  // 为什么要计数：原生日志打印 `fn=holdBegin` 只证明**调用**发生了，
  // 不证明页面真的没收到 hidden。页面若已经 suspend 过，它内部会置
  // 「已 suspend」标志，此后回前台必走 recoverConnection()（主动 close socket
  // + 重握手 + 重拉工作区，实测约 10 秒），失败还会整页 reload。
  //
  //   blockedEvents  我们拦下了多少个事件（越大越说明屏蔽在起作用）
  //   passedEvents   我们放行了多少个（连接不健康时的正常放行）
  //   sawHidden      页面**实际读到**过几次 hidden（应为 0；非 0 = 屏蔽失效）
  //
  // sawHidden 是最关键的：它是页面视角的事实，不受我们自己的日志措辞影响。
  var stats = { blockedEvents: 0, passedEvents: 0, sawHidden: 0 };

  function block(e) {
    if (!shielded()) {
      stats.passedEvents++;
      return;
    }
    stats.blockedEvents++;
    try { e.stopImmediatePropagation(); } catch (_) { /* 静默 */ }
  }

  // 要拦的事件必须与页面生命周期助手监听的那一组完全对齐（见文件头说明）。
  // 在捕获阶段先拦，页面自己的监听器（挂在 document/window 上）就收不到。
  BLOCKED.forEach(function (type) {
    window.addEventListener(type, block, true);
    document.addEventListener(type, block, true);
  });

  // 同时把读取入口也改掉：有些代码不走事件，直接读 document.visibilityState。
  var docProto = Object.getPrototypeOf(document);
  var realVisibility = Object.getOwnPropertyDescriptor(docProto, 'visibilityState');
  var realHidden = Object.getOwnPropertyDescriptor(docProto, 'hidden');

  try {
    Object.defineProperty(document, 'visibilityState', {
      configurable: true,
      get: function () {
        if (shielded()) return 'visible';
        var v = realVisibility && realVisibility.get
          ? realVisibility.get.call(document)
          : 'visible';
        if (v === 'hidden') stats.sawHidden++;
        return v;
      }
    });
    Object.defineProperty(document, 'hidden', {
      configurable: true,
      get: function () {
        if (shielded()) return false;
        var h = realHidden && realHidden.get ? realHidden.get.call(document) : false;
        if (h) stats.sawHidden++;
        return h;
      }
    });
  } catch (e) {
    // 定义失败就退化为「只拦事件」，仍能覆盖主要路径。
    stats.defineFailed = true;
  }

  // 部分实现用 hasFocus() 判断是否活跃，一并屏蔽。
  var realHasFocus = document.hasFocus;
  try {
    document.hasFocus = function () {
      if (shielded()) return true;
      return realHasFocus ? realHasFocus.call(document) : true;
    };
  } catch (e) { /* 静默 */ }

  var shield = {
    // ---- 文件选择 ----
    begin: function () { filePick = true; },
    end: function () { filePick = false; },

    // ---- 常驻武装（按连接健康动态生效） ----
    //
    // 由原生在「页面加载完成」时调用一次，此后整个文档生命周期有效。
    // 页面重载会产生新文档，原生在 onPageFinished 里会再调一次，所以能自愈。
    holdBegin: function () { hold = true; },
    holdEnd: function () { hold = false; },

    isActive: function () { return shielded(); },
    isHold: function () { return hold; },

    /**
     * 现场快照：WS 状态、是否可见、桥是否还在。
     *
     * 这几个值能直接区分三种情况：
     * - ws=open 且 visible=true：守卫生效，页面没有挂起
     * - ws=closed：页面确实挂起过（守卫未生效或被真实后台打断）
     * - bridge=false：文档已经换了，说明发生了重建（页面「刷新」的根因）
     */
    snapshot: function () {
      var s = window.__zrWsState || { last: 'none', at: 0 };
      return JSON.stringify({
        ws: s.last,
        wsAgeMs: s.at ? (Date.now() - s.at) : -1,
        visibility: document.visibilityState,
        bridge: !!(window.__zrBridge && window.__zrTransport),
        guard: shielded(),
        hold: hold,
        filePick: filePick,
        wsHealthy: (window.__zrWsState || {}).last === 'open',
        // 取证字段（见文件头 stats 的说明）
        blocked: stats.blockedEvents,
        passed: stats.passedEvents,
        sawHidden: stats.sawHidden,
        defineFailed: stats.defineFailed === true,
        doc: (window.__zrTransport && window.__zrTransport.doc) || null
      });
    }
  };

  window.__zrVisibilityShield = shield;
  // 兼容既有调用点：原生侧一直用 __zrFilePick.begin/end/snapshot，保持可用。
  window.__zrFilePick = shield;
})();
