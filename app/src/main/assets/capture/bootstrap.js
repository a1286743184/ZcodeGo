// bootstrap.js — WS 观察包装：只加监听、包装 send 仅观察，不改变页面协议行为
(function () {
  if (window.__ZR_BOOTED) return;
  window.__ZR_BOOTED = true;

  var O = window.WebSocket;
  if (!O || O.__zr) return;

  function forward(dir, data) {
    try {
      var T = window.__zrTransport;
      if (!T) return;
      var filter = window.__zrFrameFilter;
      if (filter && !filter(data)) return;
      T.push({ v: 2, kind: 'ws', doc: T.doc, dir: dir, text: data });
      T.flush();
    } catch (e) { /* 观察失败不影响页面 */ }
  }

  // 【2026-10-26 实测修正】页面实际连接的是 `/api/v1/remote/ws2`，不是 `/api/v1/remote/ws`。
  // 用前缀匹配兼容 v3（/ws）与 v4（/ws2）两种形态，避免再被版本升级打断。
  //
  // 【2026-10-31 修正：前缀匹配太脆，改为路径特征匹配】
  //
  // 真机日志里有 ws.open，说明老前缀在那个环境命中了；但模拟器上页面
  // 明明已经连上并渲染出任务列表，`__zrMainSocket` 却是空、探针报 `sock=-1`。
  // 同一份代码在两个环境行为不同，说明前缀匹配依赖了不该依赖的东西
  // （host 是否带端口、scheme 是否被改写、路径版本号）。
  //
  // 一旦匹配不中，代发心跳会静默失效（脚本报 no-socket），而日志里看不出
  // 是「没匹配上」还是「页面没连」——这正是最难排查的那种静默失败。
  //
  // 现在改为按**路径特征**匹配：只要 URL 里出现 relay 的路径段就认。
  // 不看 host、不看 scheme，因为它们会被代理、端口、页面版本改写。
  var WS_PATH_HINTS = ['/api/v1/remote/ws', '/remote/ws', '/relay/ws'];

  function isRelayUrl(u) {
    var s = String(u || '');
    for (var i = 0; i < WS_PATH_HINTS.length; i++) {
      if (s.indexOf(WS_PATH_HINTS[i]) !== -1) return true;
    }
    return false;
  }

  function health(state, reasonCode) {
    try {
      var T = window.__zrTransport;
      if (!T) return;
      T.push({ v: 2, kind: 'health', doc: T.doc, state: state, reasonCode: reasonCode });
    } catch (e) { /* 观察失败不影响页面 */ }
  }

  function W(u, p) {
    var w = p ? new O(u, p) : new O(u);
    try {
      if (isRelayUrl(u)) {
        // 【2026-10-31】记下主连接的 socket 引用，供原生「代发心跳」使用。
        //
        // 为什么需要：App 切后台后 Chromium 会停掉页面的 setTimeout，
        // 页面自己的 10 秒心跳链就此停摆（真机日志里 rxAge/txAge 冻在同一个值
        // 长达 3.7 小时）。但 evaluateJavascript 仍然能执行（探针每 30 秒都拿到了
        // 返回值），说明渲染进程活着，只是页面的定时器不再被调度。
        // 既然 JS 能被原生驱动执行，就让原生按同样的节奏、通过同一条 socket
        // 代发心跳帧，绕开被停掉的定时器。
        //
        // 这里只记录引用，不发任何东西——bootstrap 保持「只观察」的定位。
        window.__zrMainSocket = w;
        // 记下真实 URL 的形态（只保留 scheme+host+path，query 一律丢弃）。
        // 下一轮真机日志据此能直接判断「匹配规则是否还适用」，
        // 不用再靠猜是没匹配上还是页面没连。
        try {
          var parsed = new URL(String(u));
          window.__zrRelayUrlShape = parsed.protocol + '//' + parsed.host + parsed.pathname;
        } catch (e) {
          window.__zrRelayUrlShape = String(u).split('?')[0].slice(0, 120);
        }
        // 【2026-10-26 实测修正】旧实现在**构造时**就 health('open')，
        // 而那一刻连接其实还没建立。真实连接成功才会触发 open 事件。
        // 这让原生侧误以为已连上，随后任何一次 close 都会把状态打成 CLOSED，
        // 顶栏就出现那条「正在重连」的红条——而页面内容完全正常。
        w.addEventListener('open', function () { health('open', ''); });
        w.addEventListener('message', function (e) { forward('recv', e.data); });
        var originalSend = w.send.bind(w);
        w.send = function (d) { forward('send', d); return originalSend(d); };
        // close 不等于「连接坏了」：页面重载、桥代次切换、用户切走都会 close，
        // 之后页面自己会重连。这里只上报「断开」这一事实，由原生侧结合
        // 「多久没恢复」来判断要不要给用户提示，避免一次正常重连就弹红条。
        w.addEventListener('close', function (e) {
          health('closed', e && e.wasClean ? 'WS_CLOSED_CLEAN' : 'WS_CLOSED');
        });
        w.addEventListener('error', function () { health('degraded', 'WS_ERROR'); });
      }
    } catch (e) { /* 包装失败时退化为原生行为 */ }
    return w;
  }

  /**
   * 终端传输是否已终结。
   *
   * 【2026-10-27】页面侧的 WebRemoteControlTerminalTransport 在 dispose() 之后
   * intentionallyClosed 恒为 true，此后任何一次 recoverConnection() 都直接抛
   * "Web remote control transport stopped."。这个 promise 拒绝不会被页面捕获，
   * 结果是：页面还画着任务列表，但每一次交互都在后台抛错、什么也不发生。
   * 用户看到的就是「点击无效」，退出再进来还是无效——因为 WebView 没重载。
   *
   * 判定依据：最近一条 WS 生命周期事件是 closed，且之后没有任何 open。
   * 这不能区分「正常重连中」和「终结」，所以原生侧只用它做「静默期」判据，
   * 真正的重载决策还要看点击后是否有新的 open。
   */
  window.__zrWsState = window.__zrWsState || {
    last: 'none', at: 0, everOpen: false, lastRxAt: 0, lastTxAt: 0,
    lastBizRxAt: 0, bizRxCount: 0
  };
  var origHealth = health;
  health = function (state, reasonCode) {
    try {
      var prev = window.__zrWsState || {};
      if (state === 'open') {
        // everOpen 是这个文档里「曾经真的连上过」的证据。
        // 存活判定要用它排除首次建连期间的 close（那时还没连上，不是故障）。
        window.__zrWsState = {
          last: 'open', at: Date.now(), everOpen: true,
          lastRxAt: prev.lastRxAt || 0, lastTxAt: prev.lastTxAt || 0,
          lastBizRxAt: prev.lastBizRxAt || 0, bizRxCount: prev.bizRxCount || 0
        };
      } else if (state === 'closed' || state === 'degraded') {
        window.__zrWsState = {
          last: state,
          at: Date.now(),
          everOpen: !!prev.everOpen,
          lastRxAt: prev.lastRxAt || 0,
          lastTxAt: prev.lastTxAt || 0,
          lastBizRxAt: prev.lastBizRxAt || 0,
          bizRxCount: prev.bizRxCount || 0
        };
      }
    } catch (e) { /* 静默 */ }
    return origHealth(state, reasonCode);
  };

  /**
   * 记录收发时间戳。
   *
   * 为什么需要：息屏后如果 CPU 被冻结，页面 JS 停止执行、WS 心跳发不出去，
   * 服务端会判连接死掉。但原生侧只看 `last: 'open'` 看不出来——
   * 那个状态会一直停在 open 上，直到页面自己发现异常。
   * 有了 lastRxAt / lastTxAt，原生侧才能区分「连接名义上开着」与「真的在通」。
   *
   * 【2026-10-27 修正】旧实现的签名是 touch(dir)，却在函数体里引用了 `data`。
   * 那个变量在 touch 的作用域里根本不存在，每次收到帧都抛 ReferenceError，
   * 又被外层 try/catch 静默吞掉——于是 lastBizRxAt / bizRxCount 从上线起恒为 0。
   * 真机日志里连续几小时 `bizAge=-1s bizCount=0` 就是这条 bug 的指纹：
   * 「连接活着但事件流死了」这个最关键的维度，实际上从来没有被观测到过。
   */
  function touch(dir, data) {
    try {
      var s = window.__zrWsState;
      if (!s) return;
      var now = Date.now();
      if (dir === 'rx') s.lastRxAt = now;
      else s.lastTxAt = now;
      // 单独记「业务帧」时间：只统计会被帧过滤器放行的帧。
      // 心跳/ack 这类控制帧不能证明「任务订阅还活着」——
      // 连接可能开着、心跳也在走，但页面已经不再推任务事件了。
      if (dir === 'rx' && window.__zrFrameFilter && window.__zrFrameFilter(data)) {
        s.lastBizRxAt = now;
        s.bizRxCount = (s.bizRxCount || 0) + 1;
      }
    } catch (e) { /* 静默 */ }
  }
  var origForward = forward;
  forward = function (dir, data) {
    // forward 的 dir 是 'recv' / 'send'
    touch(dir === 'recv' ? 'rx' : 'tx', data);
    if (dir === 'send') captureHeartbeatShape(data);
    return origForward(dir, data);
  };

  /**
   * 捕获页面自己的心跳帧形状，供原生代发时复用。
   *
   * 为什么不自己拼帧：心跳里除了 `type` 还有 `device_sid` 等字段，取值来自页面
   * 内部的 options。自己拼一份等于把协议常量复制到 App 里，页面升级换了字段名
   * 就会发出一条畸形帧，服务端可能直接断开连接——比不发更糟。
   *
   * 所以这里只在页面**自己发过一次**之后记下那条帧，原生代发时按同样的键
   * 重新填一个新鲜的 `client_ts`。这样字段集合永远跟着页面走。
   *
   * 判定依据：type 为 pair_status_query（实测页面心跳就是这个帧，
   * 见 bundle 里 sendPairStatusQuery 的实现），或者带有 client_ts 的短帧。
   */
  function captureHeartbeatShape(data) {
    try {
      if (typeof data !== 'string' || data.length > 512) return;
      if (data.charAt(0) !== '{') return;
      var obj = JSON.parse(data);
      if (!obj || typeof obj !== 'object') return;
      if (obj.type !== 'pair_status_query' && obj.client_ts === undefined) return;
      window.__zrHeartbeatShape = obj;
      window.__zrHeartbeatAt = Date.now();
    } catch (e) { /* 静默：不是心跳帧就算了 */ }
  }


  Object.assign(W, O);
  W.prototype = O.prototype;
  W.__zr = 1;
  window.WebSocket = W;
})();
