package com.zcodeapp.remote.web

/**
 * 页面存活探测。
 *
 * 为什么需要：远控页存在一种「假活」状态——React 界面照常渲染（任务列表、输入框都在），
 * 但页面内部的 terminal transport 已经被 dispose，此后每次交互都在后台抛
 * "Web remote control transport stopped."，用户看到的就是「点了没反应」。
 *
 * 这种状态在原生侧没有任何回调：WebView 的 onPageFinished 早就走完了，
 * 渲染进程也没崩，网络层也没有错误。唯一能拿到的事实来自页面自己：
 * 最近一条 WebSocket 生命周期事件是什么、距今多久。
 *
 * 判据刻意保守——只有「最近一条 WS 事件是 closed，且已过去超过阈值」才算死。
 * 正常切后台回来时页面会自己重连，2.5 秒内就会把 open 补上；探测放在宽限期之后，
 * 避免把「正在重连」误判成「已死」而白白丢掉用户的输入草稿。
 */
object LivenessProbeScript {
    fun build(): String = """
(function () {
  try {
    var s = window.__zrWsState || { last: 'none', at: 0, everOpen: false };
    var age = s.at ? (Date.now() - s.at) : -1;
    var body = document.body ? document.body.innerText : '';
    var failed = /连接已断开|连接失败|重新连接|Disconnected|Failed to connect|Desktop offline/i.test(body);
    var hasSession = !!document.querySelector('[data-testid^="task-item-"]') ||
                     !!document.querySelector('[data-session-id]');
    // 【2026-10-27 真机修正】旧判据只看 ws=closed + age>2.5s，真机日志里误重载了两次：
    //   08:18:32 dead=true {"last":"closed","age":4839,"hasSession":false}
    // 页面刚加载完、WS 开过一次又关、会话内容还没渲染出来（hasSession=false）——
    // 那是「正在建连」，不是「死了」。重载只会把建连过程打断重来。
    //
    // 现在要求三条同时成立才判死：
    //   1. 这个文档里 WS 曾经真的 open 过（everOpen）：排除首次建连期间的 close
    //   2. 会话内容已经渲染出来（hasSession）：页面确实「看起来正常」
    //   3. closed 已持续超过 8 秒：给足页面自身重连的时间（实测多在 1-3 秒完成）
    // 三条缺一，宁可不动——重载会丢用户的滚动位置与输入草稿。
    var dead = s.everOpen === true &&
               hasSession &&
               s.last === 'closed' &&
               age > 8000;
    return JSON.stringify({
      dead: dead, last: s.last, age: age, everOpen: s.everOpen === true,
      failed: failed, hasSession: hasSession,
      bridge: !!(window.__zrBridge && window.__zrTransport)
    });
  } catch (e) {
    return JSON.stringify({ dead: false, err: String(e) });
  }
})()
""".trimIndent()
}
