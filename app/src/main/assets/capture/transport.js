// transport.js — 桥传输层：队列 + 字节上限 + 需要重同步信号 + 文档身份
(function () {
  if (window.__zrTransport) return;
  var QUEUE = [];
  var QB = 0;
  var QCAP = 4 * 1024 * 1024;
  var DOC = Math.random().toString(36).slice(2, 10);

  function send(m) {
    try {
      if (window.__zrBridge && window.__zrBridge.postMessage) {
        window.__zrBridge.postMessage(JSON.stringify(m));
        return true;
      }
    } catch (e) { /* bridge 未就绪 */ }
    return false;
  }

  window.__zrTransport = {
    doc: DOC,
    resynced: false,
    dropped: 0,
    push: function (m) {
      var s = JSON.stringify(m);
      if (QB + s.length > QCAP) {
        // 【2026-10-27 修正】旧实现在这里静默丢消息，且 needsResync 只报一次，
        // 于是「队列满了、丢了几条、丢的是哪一类」在原生侧完全看不出来。
        // 现在累计计数，随重同步信号一起带出。
        this.dropped++;
        this.needsResync();
        return;
      }
      QUEUE.push(s);
      QB += s.length;
      // 【2026-10-26 实测修正】入队后立刻尝试发送。
      // 旧实现只入队、等 250ms 定时器来 flush；而定时器在「队列空且桥就绪」时会自行 clearInterval。
      // bootstrap.js 的三条 health 事件（open/close/error）只 push 不 flush，
      // 于是定时器停掉之后这些事件永远留在队列里发不出去：
      // 原生收不到 open → pageState 卡在 CLOSED → 界面顶栏一直显示「正在重连…」，
      // 但页面本身是正常的（任务列表、桌面在线状态都加载出来了）。
      this.flush();
    },
    flush: function () {
      while (QUEUE.length) {
        if (!send(JSON.parse(QUEUE[0]))) return;
        QB -= QUEUE.shift().length;
      }
    },
    send: send,
    needsResync: function () {
      if (this.resynced) return;
      this.resynced = true;
      send({
        v: 2,
        kind: 'health',
        doc: DOC,
        state: 'degraded',
        reasonCode: 'QUEUE_CAP',
        dropped: this.dropped,
        queuedBytes: QB
      });
    },
  };

  // 定时器只在「桥还没就绪」时用来重试；桥就绪后不再停机，
  // 改为低频兜底（1s），保证任何时候积压的消息都能被推出去。
  //
  // 【2026-10-27 修正】旧实现在 200 次 tick 后 clearInterval，注释却写着「不再停机」。
  // 页面停留超过 50 秒后就没有任何兜底 flush 了：一旦某次 send 抛错（桥被 detach、
  // 页面导航中），队列里剩下的消息再也没机会出去，原生侧看到的就是「事件突然全停」。
  // 这里改成限速而不是停机，并在积压时补一条积压告警。
  var backlogReported = false;
  var timer = setInterval(function () {
    window.__zrTransport.flush();
    if (QUEUE.length && !backlogReported) {
      backlogReported = true;
      window.__zrTransport.push({
        v: 2,
        kind: 'health',
        doc: DOC,
        state: 'degraded',
        reasonCode: 'QUEUE_BACKLOG',
        queuedBytes: QB,
        queuedItems: QUEUE.length
      });
    }
    if (QUEUE.length === 0) backlogReported = false;
  }, 250);

  send({ v: 2, kind: 'hello', doc: DOC, scriptVersion: '1', pageVersion: (location.search.match(/app_version=([^&]+)/) || [])[1] || null });
})();
