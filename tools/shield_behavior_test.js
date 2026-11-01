// shield_behavior_test.js — 在 Node 里真跑注入脚本，验证可见性屏蔽的行为。
//
// 为什么需要它：单测只能锁住 Kotlin 侧的 ShieldPolicy 判定，但真正执行的是
// 注入进页面的 JS。JS 写错了不会抛异常、也不会触发任何原生回调——症状只是
// 「屏蔽不生效」，然后表现为「回前台刷新一次」。这种静默失效只有真跑起来才看得见。
//
// 这里用最小 DOM 模拟（document / window / 事件派发）复现页面生命周期助手的
// 行为，然后断言：在页面助手存在的前提下，回前台是否触发了恢复流程。
//
// 用法: node shield_behavior_test.js
'use strict';

const fs = require('fs');
const path = require('path');

const GUARD = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'capture', 'file-pick-guard.js');
const SRC = fs.readFileSync(GUARD, 'utf8');

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
 * 造一个最小的页面环境。
 *
 * 关键点：按真实 Chromium 的行为建模——
 * - visibilityState 在「App 切后台」时变 hidden，并派发 visibilitychange
 * - 页面自己的监听器注册在 document/window 上（非捕获阶段）
 * - 我们的守卫用捕获阶段拦截，因此必须先注册才能抢在页面前面
 */
function makeEnv(config) {
  const listeners = new Map(); // key: target|type|capture -> [fn]

  function addTo(target, type, fn, capture) {
    const key = target.name + '|' + type + '|' + (capture ? 'C' : 'B');
    if (!listeners.has(key)) listeners.set(key, []);
    listeners.get(key).push(fn);
  }
  function removeFrom(target, type, fn, capture) {
    const key = target.name + '|' + type + '|' + (capture ? 'C' : 'B');
    const arr = listeners.get(key) || [];
    const i = arr.indexOf(fn);
    if (i >= 0) arr.splice(i, 1);
  }
  function fire(target, type, extra) {
    // 捕获阶段先跑（与浏览器一致），再跑冒泡阶段
    for (const capture of [true, false]) {
      const key = target.name + '|' + type + '|' + (capture ? 'C' : 'B');
      const arr = (listeners.get(key) || []).slice();
      for (const fn of arr) {
        const e = Object.assign({ type: type, stopped: false }, extra || {});
        e.stopImmediatePropagation = function () { this.stopped = true; };
        e.stopPropagation = function () { this.stopped = true; };
        try { fn(e); } catch (_) { /* 页面侧异常不影响断言 */ }
      }
    }
  }

  // --- 模拟 document 的原型链（守卫会读 getPrototypeOf(document) 上的描述符）---
  const docProto = {
    get visibilityState() { return this._realVisibility; },
    get hidden() { return this._realVisibility === 'hidden'; },
  };
  const document = Object.create(docProto);
  document.name = 'document';
  document._realVisibility = 'visible';
  document.addEventListener = (t, f, c) => addTo(document, t, f, c);
  document.removeEventListener = (t, f, c) => removeFrom(document, t, f, c);
  document.hasFocus = () => true;

  const window = {
    name: 'window',
    __zrShieldConfig: config,
    __zrWsState: { last: 'open', at: Date.now() },
    addEventListener: (t, f, c) => addTo(window, t, f, c),
    removeEventListener: (t, f, c) => removeFrom(window, t, f, c),
  };

  // --- 页面自己的生命周期助手（复刻 3.16.3 bundle 的 e4t）---
  // 它的行为就是我们这次要防的对象：一旦收到 hidden 就置 i=true 并 suspend，
  // 之后收到 visible 就 recover。所以「回前台是否 recover」是本测试的核心断言。
  const page = { suspended: 0, recovered: 0 };
  let i = false;
  const onSuspend = () => { i = true; page.suspended++; };
  const onRecover = (why) => { if (!i) return; i = false; page.recovered++; };

  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'hidden') onSuspend();
    else onRecover('visible');
  });
  document.addEventListener('freeze', () => onSuspend());
  document.addEventListener('resume', () => onRecover('resume'));
  document.addEventListener('pagehide', () => onSuspend());
  window.addEventListener('pageshow', () => onRecover('pageshow'));
  window.addEventListener('online', () => onRecover('online'));

  return {
    window, document, page,
    /** 把注入脚本在 window/document 作用域里执行。 */
    install() {
      // 守卫脚本是 IIFE，用 Function 构造让它跑在我们的模拟作用域上
      const fn = new Function('window', 'document', 'setInterval', 'setTimeout',
        'Object', 'JSON', 'Date', SRC);
      fn(window, document, () => 0, () => 0, Object, JSON, Date);
    },
    /** 切到后台：改真实可见性并派发事件（顺序与 Chromium 一致）。 */
    goBackground() {
      document._realVisibility = 'hidden';
      fire(document, 'visibilitychange');
      fire(window, 'pagehide');
    },
    /** 回到前台。 */
    goForeground() {
      document._realVisibility = 'visible';
      fire(document, 'visibilitychange');
      fire(window, 'pageshow');
    },
    /** 直接派发某个事件（用于测 resume / online）。 */
    fire(target, type) { fire(target === 'window' ? window : document, type); },
    setWs(state) { window.__zrWsState.last = state; },
    shield() { return window.__zrVisibilityShield; },
  };
}

const EVENTS = ['visibilitychange', 'pagehide', 'freeze', 'resume', 'pageshow', 'online'];
// gated:false = 不做连接健康门控（2026-10-30 修正后的语义）
const CFG_ON = { events: EVENTS, hold: true, gated: false };
const CFG_OFF = { events: EVENTS, hold: false, gated: false };

console.log('shield behavior tests');

// ---- 1. hold 开启、连接健康：回前台不应触发恢复 ----
{
  const env = makeEnv(CFG_ON);
  env.install();
  env.goBackground();
  check('后台期间页面没有 suspend', env.page.suspended === 0, 'suspended=' + env.page.suspended);
  check('后台期间页面读到 visible', env.document.visibilityState === 'visible', env.document.visibilityState);
  env.goForeground();
  check('回前台不触发 recover', env.page.recovered === 0, 'recovered=' + env.page.recovered);
}

// ---- 2. hold 关闭：回前台必须让页面恢复（旧行为，证明测试能抓到故障）----
{
  const env = makeEnv(CFG_OFF);
  env.install();
  env.goBackground();
  check('无屏蔽时页面确实 suspend', env.page.suspended === 1, 'suspended=' + env.page.suspended);
  env.goForeground();
  check('无屏蔽时回前台确实 recover', env.page.recovered === 1, 'recovered=' + env.page.recovered);
}

// ---- 3. 【2026-10-30 回归】连接抖动时也必须挡 ----
//
// 0.3.5 真机日志（diag-20261030-18.log 18:17:31 / 18:25:07）：
//   web.shield resume keepalive held  ← 我们以为屏蔽还在
//   session.probe in-session
//   ws.closed WS_CLOSED_CLEAN        ← 0.2 秒后页面自己 close()
//
// 上一版「按连接健康门控」在这里开洞：连接短暂抖成 closed 时屏蔽失效，
// 恰好有 hidden 派发，页面 suspend 并永久记住，此后每次回前台都恢复。
//
// 现在无条件挡：即使连接是 closed，页面也不该看到 hidden。
{
  const env = makeEnv(CFG_ON);
  env.install();
  env.setWs('closed');
  env.goBackground();
  check('连接 closed 时仍然挡住 suspend', env.page.suspended === 0, 'suspended=' + env.page.suspended);
  env.goForeground();
  check('连接 closed 时也不触发 recover', env.page.recovered === 0, 'recovered=' + env.page.recovered);
}

// ---- 4. resume / online 事件也必须被拦 ----
{
  const env = makeEnv(CFG_ON);
  env.install();
  env.goBackground();              // 屏蔽生效，未 suspend
  env.fire('document', 'resume');  // 直接派发（模拟渲染进程重启等路径）
  env.fire('window', 'online');
  check('resume/online 也被拦下', env.page.recovered === 0, 'recovered=' + env.page.recovered);
}

// ---- 5. holdBegin/holdEnd 幂等可用 ----
{
  const env = makeEnv(CFG_OFF);
  env.install();
  env.shield().holdBegin();
  env.goBackground();
  check('holdBegin 后可屏蔽', env.page.suspended === 0, 'suspended=' + env.page.suspended);
  env.shield().holdEnd();
  env.goBackground();
  check('holdEnd 后不再屏蔽', env.page.suspended === 1, 'suspended=' + env.page.suspended);
}

// ---- 6. 文件选择守卫无条件生效 ----
{
  const env = makeEnv(CFG_OFF);
  env.install();
  env.setWs('closed');
  env.shield().begin();
  env.goBackground();
  check('filePick 无条件屏蔽', env.page.suspended === 0, 'suspended=' + env.page.suspended);
  env.shield().end();
}

// ---- 7. 快照字段完整（诊断依赖它）----
{
  const env = makeEnv(CFG_ON);
  env.install();
  const snap = JSON.parse(env.shield().snapshot());
  for (const k of ['ws', 'wsAgeMs', 'visibility', 'bridge', 'guard', 'hold', 'filePick',
                   'wsHealthy', 'blocked', 'passed', 'sawHidden', 'defineFailed', 'doc']) {
    check('snapshot 含 ' + k, k in snap, JSON.stringify(snap));
  }
}

// ---- 8. 【取证回归】sawHidden 必须为 0 ----
//
// sawHidden 是页面视角的事实：它统计「页面实际读到了几次 hidden」。
// 这是判断屏蔽真假的唯一硬证据——不受我们自己的日志措辞影响。
// 非 0 就说明页面会 suspend 并永久记住，此后每次回前台都恢复。
{
  const env = makeEnv(CFG_ON);
  env.install();
  env.goBackground();
  env.goForeground();
  env.goBackground();
  env.goForeground();
  const snap = JSON.parse(env.shield().snapshot());
  check('页面从未读到 hidden', snap.sawHidden === 0, 'sawHidden=' + snap.sawHidden);
  check('确实拦下了事件', snap.blocked > 0, 'blocked=' + snap.blocked);
}

// ---- 9. 【真实故障回归】多轮切后台不累积「已 suspend」----
//
// 用户实测：更新后打开，闪一次重连，5-6 秒后又刷一次。
// 根因是「i 标志一旦置真就永久有效」，所以只要任意一轮切后台漏过一次 hidden，
// 后面每一轮回前台都会恢复。
// 这里连做 3 轮「后台→前台」，断言页面一次都没 suspend 过。
{
  const env = makeEnv(CFG_ON);
  env.install();
  for (let round = 1; round <= 3; round++) {
    env.goBackground();
    env.goForeground();
  }
  check('3 轮切后台后页面从未 suspend', env.page.suspended === 0, 'suspended=' + env.page.suspended);
  check('3 轮切后台后从未 recover', env.page.recovered === 0, 'recovered=' + env.page.recovered);
}

// ---- 10. 未武装时不拦（用户没开保活，行为应回到旧版）----
{
  const env = makeEnv(CFG_OFF);
  env.install();
  env.goBackground();
  env.goForeground();
  check('未武装时正常 suspend', env.page.suspended === 1, 'suspended=' + env.page.suspended);
  check('未武装时正常 recover', env.page.recovered === 1, 'recovered=' + env.page.recovered);
}

console.log(failures === 0 ? 'ALL PASS' : failures + ' FAILURE(S)');
process.exit(failures === 0 ? 0 : 1);
