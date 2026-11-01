// native_heartbeat_test.js — 在 Node 里真跑 bootstrap.js 与原生代发脚本。
//
// 为什么需要它：代发心跳的效果完全取决于两段 JS 协作——
// bootstrap.js 要正确记下 socket 引用与页面自己的心跳帧形状，
// 原生脚本要正确地「页面还活着就不插手、被冻结才代发」。
// 这两段写错了都不会抛异常，只会静默地不发或发错帧，
// 而症状正是「保活看起来开着但服务端照样判死」。只有真跑起来才看得见。
//
// 用法: node native_heartbeat_test.js
'use strict';

const fs = require('fs');
const path = require('path');

const BOOTSTRAP = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'capture', 'bootstrap.js');
const BOOTSTRAP_SRC = fs.readFileSync(BOOTSTRAP, 'utf8');

let failures = 0;
function check(name, ok, detail) {
  if (ok) {
    console.log('  ok   ' + name);
  } else {
    failures++;
    console.log('  FAIL ' + name + (detail ? '  -> ' + detail : ''));
  }
}

/**
 * 造一个最小的页面环境，只实现 bootstrap.js 用到的东西。
 *
 * 关键点：WebSocket 要能区分「页面的 socket」与「别的 socket」，
 * 因为 bootstrap 只包装 ws 前缀的 URL，也只有那种连接会被记成主 socket。
 */
function makePage() {
  const sent = [];

  // 页面自己的 socket 实现：记录 send 的内容，readyState 可注入。
  class FakeWebSocket {
    constructor(url, protocols) {
      this.url = url;
      this.readyState = 1; // OPEN
      this._listeners = {};
      this.sent = [];
      FakeWebSocket.instances.push(this);
    }
    addEventListener(type, fn) {
      (this._listeners[type] = this._listeners[type] || []).push(fn);
    }
    send(data) {
      this.sent.push(data);
      sent.push(data);
      if (this.onSend) this.onSend(data);
    }
    close() { this.readyState = 3; }
    _emit(type, ev) {
      (this._listeners[type] || []).forEach((fn) => fn(ev || {}));
    }
  }
  FakeWebSocket.instances = [];

  const window = {
    WebSocket: FakeWebSocket,
    __zrTransport: {
      doc: 'd1',
      push() {},
      flush() {},
      send() { return true; },
    },
  };

  return { window, sent, FakeWebSocket };
}

/** 把 bootstrap.js 在给定 window 作用域里执行。 */
function installBootstrap(win) {
  const fn = new Function('window', 'Object', 'JSON', 'Date', 'setInterval', BOOTSTRAP_SRC);
  fn(win, Object, JSON, Date, () => 0);
}

/** 执行原生代发脚本（NativeHeartbeat.build 的输出），返回解析后的对象。 */
function runNativeBeat(win, script) {
  // 脚本本身是个 IIFE 表达式；包一层 return 才能拿到它的返回值。
  const fn = new Function('window', 'JSON', 'Date', 'return ' + script);
  const raw = fn(win, JSON, Date);
  return JSON.parse(raw);
}

console.log('native heartbeat / 帧捕获与代发门控');

// --- 场景一：页面自己发过心跳，原生不该插手 ---
{
  const page = makePage();
  installBootstrap(page.window);

  // 页面建立主连接（与 bootstrap 的前缀匹配一致）。
  const sock = new page.window.WebSocket('wss://zcode.z.ai/api/v1/remote/ws2');
  sock._emit('open');

  // 页面自己发一条心跳（这就是 bundle 里 sendPairStatusQuery 的形状）。
  sock.send(JSON.stringify({ type: 'pair_status_query', device_sid: 'sid-abc', client_ts: 1000 }));

  check('bootstrap 记下主 socket', page.window.__zrMainSocket === sock);
  check(
    'bootstrap 捕获到心跳帧形状',
    !!page.window.__zrHeartbeatShape &&
      page.window.__zrHeartbeatShape.type === 'pair_status_query',
    JSON.stringify(page.window.__zrHeartbeatShape)
  );
  check(
    '捕获的帧保留了 device_sid',
    page.window.__zrHeartbeatShape &&
      page.window.__zrHeartbeatShape.device_sid === 'sid-abc'
  );
  check(
    'bootstrap 更新了 lastTxAt',
    typeof page.window.__zrWsState.lastTxAt === 'number' &&
      page.window.__zrWsState.lastTxAt > 0
  );

  // 页面刚发过（2 秒前），原生应跳过。
  // 时间基准必须取自 lastTxAt，否则 pageAge 会算成负数、被误判成「页面从未发过」。
  const script = readNativeScript(page.window.__zrWsState.lastTxAt + 2000);
  const r = runNativeBeat(page.window, script);
  check('页面还活着时原生跳过', r.result === 'page-alive', JSON.stringify(r));
  check('跳过时不产生额外帧', sock.sent.length === 1, 'sent=' + sock.sent.length);
}

// --- 场景二：页面被冻结（3.7 小时无发送），原生代发 ---
{
  const page = makePage();
  installBootstrap(page.window);
  const sock = new page.window.WebSocket('wss://zcode.z.ai/api/v1/remote/ws2');
  sock._emit('open');
  sock.send(JSON.stringify({ type: 'pair_status_query', device_sid: 'sid-abc', client_ts: 1000 }));

  // 模拟冻结：把 lastTxAt 拨回 3.7 小时前（真机日志里的 13284 秒）。
  page.window.__zrWsState.lastTxAt = 1793373069595 - 13284000;
  const now = 1793373069595;
  const r = runNativeBeat(page.window, readNativeScript(now));

  check('冻结后原生代发', r.result === 'sent', JSON.stringify(r));
  check('代发用了正确的帧类型', r.type === 'pair_status_query');
  check('代发真的写进了 socket', sock.sent.length === 2, 'sent=' + sock.sent.length);

  const frame = JSON.parse(sock.sent[1]);
  check('代发帧保留了 device_sid', frame.device_sid === 'sid-abc', JSON.stringify(frame));
  check('代发帧刷新了 client_ts', frame.client_ts === now, 'client_ts=' + frame.client_ts);
  check('代发帧没有多余字段', Object.keys(frame).length === 3, Object.keys(frame).join(','));
}

// --- 场景三：socket 不是 OPEN 时不发 ---
{
  const page = makePage();
  installBootstrap(page.window);
  const sock = new page.window.WebSocket('wss://zcode.z.ai/api/v1/remote/ws2');
  sock._emit('open');
  sock.send(JSON.stringify({ type: 'pair_status_query', device_sid: 's', client_ts: 1 }));
  sock.readyState = 3; // CLOSED
  page.window.__zrWsState.lastTxAt = 1; // 很久以前

  const r = runNativeBeat(page.window, readNativeScript(1793373069595));
  check('socket 关闭时代发跳过', r.result === 'socket-not-open', JSON.stringify(r));
  check('跳过时不写 socket', sock.sent.length === 1);
}

// --- 场景四：没有 socket 引用时明确失败 ---
{
  const page = makePage();
  installBootstrap(page.window);
  const r = runNativeBeat(page.window, readNativeScript(1793373069595));
  check('没有 socket 时明确失败', r.result === 'no-socket', JSON.stringify(r));
}

// --- 场景五：没有帧模板时不自己拼帧 ---
{
  const page = makePage();
  installBootstrap(page.window);
  const sock = new page.window.WebSocket('wss://zcode.z.ai/api/v1/remote/ws2');
  sock._emit('open');
  // 没有发过任何心跳：__zrHeartbeatShape 为空。
  page.window.__zrWsState.lastTxAt = 1;

  const r = runNativeBeat(page.window, readNativeScript(1793373069595));
  check('没有帧模板时不自己拼帧', r.result === 'no-shape', JSON.stringify(r));
  check('不自己拼帧就不写 socket', sock.sent.length === 0);
}

// --- 场景六：非心跳帧不该被当成模板 ---
{
  const page = makePage();
  installBootstrap(page.window);
  const sock = new page.window.WebSocket('wss://zcode.z.ai/api/v1/remote/ws2');
  sock._emit('open');
  // 一条业务帧：既不是 pair_status_query，也没有 client_ts。
  sock.send(JSON.stringify({ type: 'task_event', payload: { id: 't1' } }));

  check(
    '业务帧不会被当成心跳模板',
    !page.window.__zrHeartbeatShape,
    JSON.stringify(page.window.__zrHeartbeatShape)
  );
}

// --- 场景七：非远控 socket 不会被记成主 socket ---
{
  const page = makePage();
  installBootstrap(page.window);
  const other = new page.window.WebSocket('wss://example.com/other');
  check('其它 socket 不记为主 socket', page.window.__zrMainSocket === undefined);
  check('其它 socket 不产生帧模板', !page.window.__zrHeartbeatShape);
}

// --- 场景八：relay URL 的多种形态都要认得 ---
//
// 【2026-10-31 模拟器实测教训】页面明明已连上并渲染出任务列表，
// 但 __zrMainSocket 为空、探针报 sock=-1——说明老的前缀匹配
// （'wss://zcode.z.ai/api/v1/remote/ws'）在那个环境没命中。
// 同一份代码两个环境行为不同，本身就是隐患：匹配不中时代发心跳会静默失效。
// 现在改为按路径特征匹配，下面把这些真实形态逐个锁住。
{
  const cases = [
    ['wss://zcode.z.ai/api/v1/remote/ws2', '原始形态（真机日志里命中的那个）'],
    ['wss://zcode.z.ai/api/v1/remote/ws', 'v3 形态'],
    ['ws://zcode.z.ai/api/v1/remote/ws2', '明文 ws（调试/代理环境）'],
    ['wss://zcode.z.ai:443/api/v1/remote/ws2', '带显式端口'],
    ['wss://relay.example.com/remote/ws2', '换了 host'],
    ['wss://zcode.z.ai/api/v1/remote/ws2?mid=abc', '带 query（页面会加 mid）'],
  ];
  for (const [url, label] of cases) {
    const page = makePage();
    installBootstrap(page.window);
    const sock = new page.window.WebSocket(url);
    check('认得 relay URL：' + label, page.window.__zrMainSocket === sock, url);
    check('  ↑ 记录了 URL 形态', typeof page.window.__zrRelayUrlShape === 'string',
      page.window.__zrRelayUrlShape);
    // 记录形态时必须丢掉 query：query 里可能有 mid 等标识，
    // 不该原样进日志（项目规则：URL 只保留 scheme+host+path）。
    check('  ↑ 形态不含 query', !(page.window.__zrRelayUrlShape || '').includes('mid='),
      page.window.__zrRelayUrlShape);
  }
}

// --- 场景九：非 relay 的 ws 不能被误认 ---
{
  const negatives = [
    'wss://zcode.z.ai/api/v1/telemetry',
    'wss://zcode.z.ai/ws/something-else',
    'wss://example.com/api/v1/remote/ws2', // host 不同但路径像：按路径匹配会认它
  ];
  // 前两条必须不认；第三条按「只看路径特征」的设计会被认——
  // 这是有意的取舍：宁可多认一条无关连接（最坏是多发一条心跳给错 socket，
  // 页面自己的 socket 仍会被记录），也不要在 host/scheme 变化时静默漏认。
  const page1 = makePage();
  installBootstrap(page1.window);
  new page1.window.WebSocket(negatives[0]);
  check('非 relay 路径不认', page1.window.__zrMainSocket === undefined);

  const page2 = makePage();
  installBootstrap(page2.window);
  new page2.window.WebSocket(negatives[1]);
  check('无关 /ws 路径不认', page2.window.__zrMainSocket === undefined);
}

console.log(failures === 0 ? '\n全部通过' : '\n' + failures + ' 项失败');
process.exit(failures === 0 ? 0 : 1);

/**
 * 取 NativeHeartbeat.build(nowMs) 的输出。
 *
 * 这里直接从 Kotlin 源里抽模板并替换时间戳占位，避免在 JS 测试里
 * 复制一份脚本文本——复制就会漂移，而漂移的症状是「测试通过、真机不发」。
 */
function readNativeScript(nowMs) {
  const ktPath = path.join(
    __dirname, '..', 'app', 'src', 'main', 'java',
    'com', 'zcodeapp', 'remote', 'web', 'NativeHeartbeat.kt'
  );
  const kt = fs.readFileSync(ktPath, 'utf8');
  const start = kt.indexOf('(function () {');
  const end = kt.indexOf('})()', start);
  if (start < 0 || end < 0) {
    throw new Error('在 NativeHeartbeat.kt 里找不到脚本模板');
  }
  let script = kt.slice(start, end + 4);
  // Kotlin 里的两个插值点：原生时间戳与页面存活窗口。
  script = script.replace(/\$nowMs/g, String(nowMs));
  script = script.replace(/\$\{PAGE_ALIVE_MS\}/g, '30000');
  return script;
}
