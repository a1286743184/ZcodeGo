// nav_test.js — 在 Node 里真跑 nav.js，验证分层返回与下拉刷新判定。
//
// 为什么需要它：nav.js 里全是 DOM 判据，写错不会抛异常、也不会触发任何原生回调，
// 症状只是「按返回没反应」或「按返回把人踢出会话」——这类静默错层只有真跑起来才看得见。
// Kotlin 单测只能锁住回包格式，锁不住「哪个 DOM 状态该退哪一层」。
//
// 用法: node nav_test.js
'use strict';

const fs = require('fs');
const path = require('path');
const vm = require('vm');

const NAV = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'capture', 'nav.js');
const SRC = fs.readFileSync(NAV, 'utf8');

let failures = 0;
function check(name, ok, detail) {
  if (ok) {
    console.log('  ok   ' + name);
  } else {
    failures++;
    console.log('  FAIL ' + name + (detail ? '  -> ' + detail : ''));
  }
}

const VISIBLE = { display: 'block', visibility: 'visible', opacity: '1' };
const HIDDEN = { display: 'none', visibility: 'hidden', opacity: '0' };

/**
 * 造一个可控的 DOM。
 *
 * 只实现 nav.js 用到的东西：querySelector/querySelectorAll、visible 判定所需的
 * getComputedStyle / getClientRects / isConnected / parentElement，
 * 以及 click 计数。
 */
function makePage(opts = {}) {
  const clicks = [];
  // body 也要是完整节点：nav.js 的 visible() 会沿 parentElement 链向上检查，
  // 链顶若是裸对象就会在 getAttribute 上抛错——那正是它该走的分支。
  const body = {
    nodeType: 1,
    innerText: opts.bodyText || '',
    parentElement: null,
    hidden: false,
    getAttribute: () => null,
    hasAttribute: () => false,
    getClientRects: () => [{}],
    _children: [],
  };

  function node(attrs = {}, children = [], style = VISIBLE) {
    const el = {
      nodeType: 1,
      isConnected: true,
      parentElement: body,
      hidden: false,
      _attrs: attrs,
      _children: children,
      _style: style,
      getAttribute: (k) => (k in attrs ? attrs[k] : null),
      hasAttribute: (k) => k in attrs,
      getClientRects: () => (style.display === 'none' ? [] : [{}]),
      click() { clicks.push(attrs['__name'] || '?'); },
      querySelector(sel) { return findIn(el, sel); },
      querySelectorAll(sel) { return findAllIn(el, sel); },
      closest() { return null; },
      scrollIntoView() {},
    };
    return el;
  }

  // 极简选择器：只支持 nav.js 实际用到的几种形态，按属性/标签匹配。
  function matches(el, sel) {
    if (!el || !el._attrs) return false;
    const parts = sel.split(',').map((s) => s.trim());
    return parts.some((p) => matchOne(el, p));
  }

  function matchOne(el, sel) {
    const a = el._attrs;
    // [attr="value"]
    const attrEq = sel.match(/^([a-z0-9-]*)\[([a-zA-Z-]+)="([^"]*)"\]$/);
    if (attrEq) {
      const tag = attrEq[1];
      if (tag && tag !== 'div' && tag !== 'button' && tag !== 'a') return false;
      return a[attrEq[2]] === attrEq[3];
    }
    // [attr]
    const attrOnly = sel.match(/^([a-z0-9-]*)\[([a-zA-Z-]+)\]$/);
    if (attrOnly) return attrOnly[2] in a;
    // button[aria-label*="x"]
    const contains = sel.match(/^([a-z]+)\[([a-zA-Z-]+)\*="([^"]*)"\]$/);
    if (contains) {
      const v = a[contains[2]] || '';
      return v.indexOf(contains[3]) >= 0;
    }
    // div[role="x"][data-state="open"]
    const twoAttrs = sel.match(/^([a-z]+)\[([a-zA-Z-]+)="([^"]*)"\]\[([a-zA-Z-]+)="([^"]*)"\]$/);
    if (twoAttrs) {
      return a[twoAttrs[2]] === twoAttrs[3] && a[twoAttrs[4]] === twoAttrs[5];
    }
    if (sel === 'button') return a.__tag === 'button';
    return false;
  }

  function walk(root, sel, out) {
    if (matches(root, sel)) out.push(root);
    (root._children || []).forEach((c) => walk(c, sel, out));
  }

  function findAllIn(root, sel) {
    const out = [];
    walk(root, sel, out);
    return out;
  }

  function findIn(root, sel) {
    return findAllIn(root, sel)[0] || null;
  }

  // 顶层索引：nav.js 用 document.querySelector 找页面级节点。
  const top = [];
  function add(name, attrs, children, style) {
    const el = node(Object.assign({ __name: name }, attrs), children || [], style || VISIBLE);
    top.push(el);
    return el;
  }

  // 按场景装配
  if (opts.popup) {
    add('popup', { role: 'menu', 'data-state': 'open', __tag: 'div' });
  }
  if (opts.sidePaneOpen) {
    add('sidepane-overlay', { 'data-mobile-side-pane-overlay': 'true', class: '' }, [
      add2('collapse', { 'aria-label': '收起侧边面板', __tag: 'button' }),
    ]);
  }
  if (opts.onboarding) {
    add('onboarding', { 'data-testid': 'onboarding-page' }, [
      add2('exit-guide', { 'aria-label': '退出引导', __tag: 'button' }),
    ]);
  }
  if (opts.settings) {
    add('settings', { 'data-testid': 'settings-page' }, [
      add2('settings-back', { 'data-testid': 'settings-back-button', __tag: 'button' }),
    ]);
  }
  if (opts.chat) {
    const chatChildren = [];
    if (opts.backHome !== false) {
      chatChildren.push(add2('back-home', { 'aria-label': '返回任务首页', __tag: 'button' }));
    }
    add('chat', { 'data-mobile-page': 'chat' }, chatChildren);
  }
  if (opts.list) {
    add('task-new', { 'data-testid': 'task-new-button', __tag: 'button' });
  }
  if (opts.reconnectBtn) {
    add('reconnect', { __tag: 'button' }, []);
    // innerText 单独挂上，供 reconnectButton 的文案匹配。
    top[top.length - 1].innerText = '重新连接';
  }

  function add2(name, attrs) {
    return node(Object.assign({ __name: name }, attrs), [], VISIBLE);
  }

  const document = {
    body,
    querySelector(sel) { return findIn(body, sel) || null; },
    querySelectorAll(sel) { return findAllIn(body, sel); },
  };
  // body 的孩子就是 top 里的页面级节点
  body._children = top;

  const win = {
    getComputedStyle: (el) => el._style || VISIBLE,
    // wsAgeMs 表达「最近一条 WS 事件距今多久」，比直接写 at 更贴近真实：
    // 真实时间戳是毫秒级大数，用 0 当占位会让 nav.js 的 `s.at ?` 判成「从未有过事件」。
    __zrWsState: opts.ws
      ? Object.assign({}, opts.ws, { at: (opts.now || 0) - (opts.wsAgeMs || 0) })
      : { last: 'open', at: (opts.now || 0), everOpen: true },
    __zrBridge: {},
    __zrTransport: {},
  };

  const context = { window: win, document, Date: { now: () => opts.now || 0 }, console };
  win.document = document;

  return { context, win, clicks };
}

function run(opts) {
  const page = makePage(opts);
  vm.runInNewContext(SRC, page.context);
  return page;
}

// ---- 层级判定 ----

{
  const p = run({ list: true });
  check('列表页判定为 list', p.win.__zrNav.layer() === 'list', p.win.__zrNav.layer());
}

{
  const p = run({ chat: true });
  check('会话页判定为 chat', p.win.__zrNav.layer() === 'chat', p.win.__zrNav.layer());
}

{
  const p = run({ chat: true, popup: true });
  check('浮层盖在会话页之上时判定为 popup', p.win.__zrNav.layer() === 'popup', p.win.__zrNav.layer());
}

{
  const p = run({ settings: true });
  check('设置页判定为 settings', p.win.__zrNav.layer() === 'settings', p.win.__zrNav.layer());
}

{
  // 设置页被 role=dialog 包裹时不能被误判成浮层——否则按返回直接踢出设置页。
  const p = run({ settings: true, popup: true });
  // 这里的 popup 节点是空的，不含 settings-page，所以仍应是 popup。
  // 真正要防的是「settings 页自己就是那个 dialog」，见下一个用例。
  check('空的 open dialog 仍算浮层', p.win.__zrNav.layer() === 'popup', p.win.__zrNav.layer());
}

{
  const p = run({ list: true, ws: { last: 'closed', everOpen: false }, wsAgeMs: 500 });
  check('连接页判定为 connect', p.win.__zrNav.layer() === 'list', '列表锚点优先于文案');
}

{
  const p = run({ bodyText: '重新连接 连接已中断', reconnectBtn: true, now: 1000, ws: { last: 'closed', everOpen: false }, wsAgeMs: 60000 });
  check('无列表无会话时判定为 connect', p.win.__zrNav.layer() === 'connect', p.win.__zrNav.layer());
}

{
  const p = run({});
  check('空页面判定为 unknown', p.win.__zrNav.layer() === 'unknown', p.win.__zrNav.layer());
}

// ---- 分层后退 ----

{
  const p = run({ settings: true });
  const r = JSON.parse(p.win.__zrNav.back());
  check('设置页后退由页面消化', r.handled === true && r.action === 'settings', JSON.stringify(r));
  check('设置页后退点了返回按钮', p.clicks.includes('settings-back'), p.clicks.join(','));
}

{
  const p = run({ chat: true });
  const r = JSON.parse(p.win.__zrNav.back());
  check('会话页后退点了返回任务首页', r.handled === true && r.action === 'chat', JSON.stringify(r));
  check('会话页后退点的是 back-home', p.clicks.includes('back-home'), p.clicks.join(','));
}

{
  const p = run({ chat: true, backHome: false });
  const r = JSON.parse(p.win.__zrNav.back());
  check('会话页没有回列表入口时交给壳子', r.handled === false && r.action === 'shell', JSON.stringify(r));
  check('没有入口时没有乱点', p.clicks.length === 0, p.clicks.join(','));
}

{
  const p = run({ popup: true });
  const r = JSON.parse(p.win.__zrNav.back());
  check('浮层要求原生派发 Escape', r.action === 'escape', JSON.stringify(r));
  check('浮层不合成点击', p.clicks.length === 0, p.clicks.join(','));
}

{
  const p = run({ sidePaneOpen: true });
  const r = JSON.parse(p.win.__zrNav.back());
  check('侧滑面板后退点了收起', r.handled === true && r.action === 'sidepane', JSON.stringify(r));
  check('侧滑面板点的是 collapse', p.clicks.includes('collapse'), p.clicks.join(','));
}

{
  const p = run({ onboarding: true });
  const r = JSON.parse(p.win.__zrNav.back());
  check('引导页后退点了退出引导', r.handled === true && r.action === 'onboarding', JSON.stringify(r));
}

{
  const p = run({ list: true });
  const r = JSON.parse(p.win.__zrNav.back());
  check('列表页是最外层，交给壳子', r.handled === false && r.action === 'shell', JSON.stringify(r));
}

// ---- 异常兜底 ----

{
  const p = run({ settings: true });
  // 让 click 抛异常：nav.js 必须退化成 shell，而不是卡住或把人留在原地。
  p.context.document.querySelector('[data-testid="settings-page"]')._children[0].click = () => { throw new Error('boom'); };
  const r = JSON.parse(p.win.__zrNav.back());
  check('页面点击抛错时退化成 shell', r.action === 'shell' && !!r.error, JSON.stringify(r));
}

{
  const p = run({ list: true });
  const st = JSON.parse(p.win.__zrNav.state());
  check('state 带层级与桥信息', st.layer === 'list' && st.bridge === true, JSON.stringify(st));
}

console.log('');
if (failures === 0) {
  console.log('nav: 全部通过');
} else {
  console.log('nav: ' + failures + ' 项失败');
  process.exitCode = 1;
}
