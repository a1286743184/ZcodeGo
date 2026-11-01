package com.zcodeapp.remote.web

/**
 * 页面内任务定位脚本（v2 7.3 / G3）。
 *
 * 不使用 URL 直达：官方页面没有任务级 URL，只能在页面内点击任务条目。
 * 选择器与防护逻辑参照 `ref/zcode-mobile-app/.../SessionJumpScript.kt`（该实现在真机上
 * 踩过以下坑，均已在脚本里处理）：
 * 1) 任务条目用 `data-testid="task-item-<完整 taskId>"`，必须全等匹配，
 *    用 indexOf 会把前缀相同的其它条目误判为目标；
 * 2) 列表页全部项目折叠时 task-item 数量为 0，需逐个展开 `virtual-project-toggle`；
 * 3) 会话页没有任何 task-item，此时绝不能点任何按钮——会话页的按钮都是弹层触发器，
 *    误点会出现「git 工具 → 审批 → 思考」的弹层刷屏；
 * 4) 只有当前会话 id 与目标一致才算成功；停在别的会话页不算，
 *    否则表现为「点 A 的通知跳到 B」；
 * 5) 设置页/引导页是覆盖层，背后仍有尺寸，必须先点明确的退出入口。
 *
 * 与参考实现的差异（基于 2026-10-25 真实页面实测修正）：
 * 参考实现用「当前会话 id 非空」判定不在列表页，从而完全不点击。实测发现这一条在
 * 当前版本页面上不成立：会话视图下左侧任务列表（aside，含全部 task-item）依然可见可点，
 * 而页面上**不存在任何返回列表的按钮**（三种 aria-label 全部 0 命中）。
 * 因此若沿用原判定，从会话页永远跳不到另一个任务。
 *
 * 本实现的判定改为：只要侧栏里有可见的任务条目（或可展开的分组），就允许点击；
 * 仍保留参考实现的全部防护——会话页主区域没有 task-item、只点侧栏内部元素、
 * 设置/引导覆盖层先退出、只有 data-session-id 与目标一致才算成功。
 *
 * 【时序要求】通知点击时 App 可能刚冷启动、页面还在加载。脚本必须能在「页面尚未渲染出
 * 任何可操作元素」时持续等待，而不是立刻判定失败——否则表现为「进了 App 但没跳到位」。
 * 因此 tick 循环在 timeout 之前不会因为找不到元素就退出，只有成功（sid 匹配）
 * 或超时才停。
 */
object SessionJumpScript {
    private const val TIMEOUT_MS = 20_000
    private const val TICK_MS = 400

    fun build(taskId: String): String {
        val tid = jsQuote(taskId)
        return """
(function () {
  var tid = $tid;
  if (!tid) return;
  // 【2026-10-27 弹回循环根因】同一文档里已经跳到过目标会话，就不要再装第二遍。
  //
  // 旧实现没有这道闸：原生侧每次 onPageFinished 都重新注入，而 __zrJumpDone
  // 只写不读。于是「用户点返回 → 脚本下一拍把用户点回会话 → 页面导航又触发
  // onPageFinished → 再注入 → 再点回」形成死循环，用户永远停不在任务列表，
  // 真机日志表现为 25 秒内 20 多次 page.finished。
  if (window.__zrJumpDone === tid) return;
  var GEN = '__zrJumpGen';
  var myGen = (window[GEN] = (window[GEN] || 0) + 1);
  var stale = function () { return window[GEN] !== myGen; };
  var deadline = Date.now() + $TIMEOUT_MS;
  var tried = new WeakSet();
  var mine = new WeakSet();
  // 已经到过目标会话，之后又检测到用户退出了它 —— 说明这是人在操作，
  // 立刻作废跳转意图，绝不把人再拽回去。
  var reachedAt = 0;

  var visible = function (el) {
    if (!el || !el.isConnected || el.getClientRects().length === 0) return false;
    for (var p = el; p && p.nodeType === 1; p = p.parentElement) {
      var s = window.getComputedStyle(p);
      if (p.hidden || p.getAttribute('aria-hidden') === 'true' || p.hasAttribute('inert') ||
          s.display === 'none' || s.visibility === 'hidden' || parseFloat(s.opacity) === 0) return false;
    }
    return true;
  };

  var currentSessionId = function () {
    var p = Array.prototype.slice.call(document.querySelectorAll('[data-session-id]')).filter(visible)[0];
    return p ? (p.getAttribute('data-session-id') || '') : '';
  };

  // 列表根：只有「新任务入口所在侧栏」才是任务列表。
  // 【实测 2026-10-25】会话视图下：
  //   - task-new-button 不存在、task-item 数量为 0（侧栏收起）
  //   - 页面上存在 aria-label="返回任务首页" 的按钮，点它可回到列表
  //   - side-pane-toggle 展开的是右侧面板（320px，无任务条目），不是任务列表
  // 因此会话视图下不要试图「展开侧栏」，正确路径是点「返回任务首页」。
  var listRoot = function () {
    if (visible(document.querySelector('[data-testid="settings-page"]')) ||
        visible(document.querySelector('[data-testid="onboarding-page"]'))) return null;
    var entry = document.querySelector('[data-testid="task-new-button"]');
    if (!visible(entry)) return null;
    var root = entry.closest('aside');
    if (!root) return null;
    return root.querySelectorAll('[data-testid^="task-item-"]').length > 0 ||
           root.querySelectorAll('button[data-testid="virtual-project-toggle"]').length > 0
      ? root : null;
  };
  var onListPage = function () { return !!listRoot(); };

  var backToHome = function () {
    var b = document.querySelector(
      'button[aria-label="返回任务首页"], button[aria-label="返回工作区"], button[aria-label="Back to workspace"]');
    if (!visible(b) || tried.has(b)) return false;
    tried.add(b);
    b.click();
    return true;
  };

  var find = function () {
    if (!onListPage()) return false;
    // 只在侧栏内找：会话页主区域可能存在同名 testid 的展示元素，点它不会切换会话。
    var root = listRoot();
    if (!root) return false;
    var els = root.querySelectorAll('[data-testid]');
    for (var i = 0; i < els.length; i++) {
      var t = els[i].getAttribute('data-testid') || '';
      if (t !== 'task-item-' + tid) continue;
      if (!visible(els[i])) continue;
      if (tried.has(els[i])) continue;
      tried.add(els[i]);
      els[i].scrollIntoView({ block: 'center' });
      els[i].click();
      return true;
    }
    return false;
  };

  var expandNext = function () {
    if (!onListPage()) return false;
    var heads = listRoot().querySelectorAll('button[data-testid="virtual-project-toggle"][aria-expanded="false"]');
    for (var i = 0; i < heads.length; i++) {
      if (!visible(heads[i])) continue;
      if (tried.has(heads[i])) continue;
      tried.add(heads[i]);
      mine.add(heads[i]);
      heads[i].click();
      return true;
    }
    return false;
  };

  var restore = function () {
    if (!onListPage()) return false;
    var heads = listRoot().querySelectorAll('button[data-testid="virtual-project-toggle"][aria-expanded="true"]');
    for (var i = 0; i < heads.length; i++) {
      if (!mine.has(heads[i])) continue;
      mine.delete(heads[i]);
      heads[i].click();
    }
  };

  var iv = null, mo = null;
  var stop = function () {
    if (iv) clearInterval(iv);
    if (mo) mo.disconnect();
    iv = null; mo = null;
  };

  var tick = function () {
    try {
      if (stale()) { restore(); stop(); return; }
      if (Date.now() > deadline) { restore(); stop(); return; }
      // 页面还没开始渲染（通知点击刚冷启动时常见）：继续等，不退出。
      if (!document.body) return;

    var settings = document.querySelector('[data-testid="settings-page"]');
    var onboarding = document.querySelector('[data-testid="onboarding-page"]');
    if (visible(settings) || visible(onboarding)) {
      var exit = visible(onboarding)
        ? onboarding.querySelector('button[aria-label="退出引导"]')
        : settings.querySelector('[data-testid="settings-back-button"], button[aria-label="返回工作区"], button[aria-label="Back to workspace"]');
      if (visible(exit) && !tried.has(exit)) {
        tried.add(exit);
        exit.click();
      }
      return;
    }

    var sid = currentSessionId();
    if (sid === tid) {
      // 成功：通知原生清除待跳转意图，避免后续页面加载重复注入。
      try { window.__zrJumpDone = tid; } catch (e) {}
      // 【2026-10-27】到点即收工，不再继续盯着。
      //
      // 旧实现到这里只写 __zrJumpDone 就 restore/stop，但因为原生侧从不调用
      // clearPendingJump，下一次 onPageFinished 又会注入一份新脚本；新脚本读到
      // 「当前不在目标会话」（用户已经点返回）就再点回去。现在有了顶部那道
      // __zrJumpDone 闸，重复注入本身被挡住；这里再记下到达时刻，
      // 供下一拍判断「是用户自己退出的」。
      if (!reachedAt) reachedAt = Date.now();
      restore(); stop(); return;
    }
    // 到过目标会话，现在又不在里面了：这是用户主动退回列表。
    // 绝不能把他再拽回会话——这正是用户报的「点返回又被弹回」。
    if (reachedAt) {
      try { window.__zrJumpDone = tid; } catch (e) {}
      restore(); stop(); return;
    }
    if (find()) return;

    // 不在列表页（会话视图）：点「返回任务首页」回到列表，下一轮 tick 再找任务。
    if (!onListPage()) {
      if (backToHome()) return;
      return;
    }
    expandNext();
    } catch (e) {
      // 页面渲染中途的瞬时异常（元素被替换、body 尚未就绪等）不应终止跳转，
      // 下一拍继续尝试。
    }
  };

  tick();
  iv = setInterval(tick, $TICK_MS);
  if (document.body) {
    mo = new MutationObserver(function () { if (stale()) { restore(); stop(); } });
    mo.observe(document.body, { childList: true, subtree: true });
  }
})();
""".trimIndent()
    }

    /** JSON 字符串字面量：避免 taskId 里的引号/反斜杠逃逸成脚本。 */
    private fun jsQuote(s: String): String = org.json.JSONObject.quote(s)
}
