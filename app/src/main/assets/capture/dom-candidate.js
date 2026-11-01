// dom-candidate.js — 低置信 DOM 候选：确认弹窗可见性提示（限流 + 签名去重）
(function () {
  if (window.__ZR_DOM) return;
  window.__ZR_DOM = true;

  var lastAt = 0;
  var lastSig = '';

  function post(m) {
    try {
      if (window.__zrBridge && window.__zrTransport) {
        window.__zrBridge.postMessage(JSON.stringify(m));
      }
    } catch (e) { /* 静默 */ }
  }

  function taskHint() {
    var el = document.querySelector('[data-testid^="task-item-"][aria-selected="true"]')
      || document.querySelector('[data-session-id][aria-selected="true"]')
      || document.querySelector('[data-active="true"][data-testid^="task-item-"]');
    if (!el) return null;
    return el.getAttribute('data-session-id')
      || ((el.getAttribute('data-testid') || '').replace('task-item-', '') || null);
  }

  var mo = new MutationObserver(function () {
    var now = Date.now();
    if (now - lastAt < 500) return;
    var dlg = document.querySelector('[data-testid="confirm-dialog"]') || document.querySelector('[role="dialog"]');
    if (!dlg || !dlg.textContent) { lastSig = ''; return; }
    var sig = dlg.textContent.slice(0, 120);
    if (sig === lastSig) return;
    lastSig = sig;
    lastAt = now;
    var T = window.__zrTransport;
    if (!T) return;
    post({
      v: 2,
      kind: 'dom',
      doc: T.doc,
      candidate: 'confirm-visible',
      taskHint: taskHint(),
      evidenceId: dlg.getAttribute('data-testid') || 'dialog',
    });
  });

  mo.observe(document.documentElement, { childList: true, subtree: true });
})();
