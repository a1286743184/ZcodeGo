package com.zcodeapp.remote.web

/**
 * 页面侧可见性屏蔽策略：**单一真源**。
 *
 * 为什么要有这个类：屏蔽的判定同时存在于两处——注入进页面的 JS（真正执行的地方）
 * 和原生侧的决策（什么时候武装）。两边各写一份常量，迟早会漂移：
 * 改了 JS 忘了改 Kotlin，或者反过来，症状是「某些状态下屏蔽失效」，
 * 而这种失效只在特定后台时序下才暴露，极难复现。
 *
 * 现在把「拦哪些事件」「什么状态生效」都定义在这里，由
 * [com.zcodeapp.remote.capture.InjectionInstaller] 序列化进 document-start 脚本，
 * JS 只负责按配置执行，不再自己写死。
 *
 * ## 为什么拦这一组事件
 *
 * 页面内部的生命周期助手（反编译自 3.16.3 bundle）监听：
 *
 * ```js
 * let i = false;                                  // 「已 suspend」标志
 * onSuspend = () => { i = true; transport.suspend(); }
 * onRecover = (e) => { if (!i) return; i = false; recover(e); }
 *
 * document.addEventListener('visibilitychange', s)   // hidden→suspend / visible→recover
 * document.addEventListener('freeze', f)
 * document.addEventListener('resume', u)
 * document.addEventListener('pagehide', d)
 * window.addEventListener('pageshow', c)
 * window.addEventListener('online', l)
 * ```
 *
 * `onRecover` 只在 `i` 为真时执行。所以只要页面从没收到过 hidden，
 * 回前台就永远不会跑 `recoverConnection()`——那一步会主动关掉健康 socket、
 * 重握手、重拉工作区（实测约 10 秒），失败还会整页 reload。
 *
 * 必须**六个全拦**：漏一个，一旦 `i` 被任何路径置真（页面自己的其它分支、
 * 渲染进程重启后的状态残留），漏掉的那个事件就会把恢复流程放出来。
 *
 * 不拦 `focus` / `blur`：页面助手不监听它们，拦掉反而会干扰输入框与 IME。
 *
 * ## 2026-10-30 重要修正：屏蔽必须无条件，不能按连接健康门控
 *
 * 上一版的设计是「只在连接健康（open/none）时才屏蔽，closed/degraded 放行」，
 * 理由是「断了还挡，页面永远收不到可见性变化，自动恢复失效」。
 *
 * **这个设计是错的，真机日志直接证伪了它。**
 *
 * 0.3.5 的 `diag-20261030-18.log`（18:17:31 / 18:25:07 两次回前台）：
 *
 * ```
 * 18:17:31.699 web.shield resume keepalive held   ← 我们以为屏蔽还在
 * 18:17:31.742 session.probe in-session
 * 18:17:32.231 ws.closed WS_CLOSED_CLEAN          ← 0.2 秒后页面自己 close()
 * ```
 *
 * `WS_CLOSED_CLEAN` 只可能来自页面主动 `socket.close()`（`reconnectNow()`），
 * 而 `reconnectNow()` 只从 `onRecover` 进来，`onRecover` 只在 `i` 为真时跑。
 * **所以 `i` 早就被置真了** —— 后台期间页面确实收到过 hidden。
 *
 * 洞就在「按健康门控」：连接在后台只要短暂抖成 `closed`（socket 断开重连的正常波动，
 * 日志里 `closed/open/closed/open` 连续抖动），屏蔽立刻失效；如果恰好那一刻
 * 有一个 hidden 派发，页面就 suspend，`i` 永久置真，之后**每次**回前台都必走恢复。
 *
 * 这就是「有时还行、有时刷好几次」的确切机制——不是概率，是「是否撞上抖动窗口」。
 *
 * 正确的取舍反过来：
 *
 * - **屏蔽无条件生效**。页面永远不该看到 hidden，`i` 永远是 false，
 *   回前台永远不会触发 `recoverConnection()`。
 * - **恢复权收回原生**。页面不再有自愈机会，所以原生必须承担：
 *   后台巡检发现真死了就重建（`probe-timeout` 路径），
 *   回前台 `probeAndRecoverSession` 统一判定一次。
 *   这两条本来就有，不依赖页面自愈。
 *
 * 代价是明确且可接受的：页面失去自愈能力，恢复完全由原生负责。
 * 而原来的设计代价是「页面自愈」与「屏蔽」互相拆台，导致每次回前台都刷新——
 * 那才是不可用状态。
 */
object ShieldPolicy {

    /** 需要拦截的事件（document 与 window 上都注册捕获阶段）。 */
    val BLOCKED_EVENTS: List<String> = listOf(
        "visibilitychange",
        "pagehide",
        "freeze",
        "resume",
        "pageshow",
        "online",
    )

    /**
     * 屏蔽是否生效。
     *
     * 三个开关任一为真即无条件屏蔽，**不看连接状态**：
     *
     * - `filePick`：文件选择器盖住 App 期间（秒级窗口，120 秒看门狗兜底）。
     *   放行会让页面挂起 transport，选完回来上传请求正好落在重建窗口里被丢弃。
     * - `keepAlive`：保活态。
     * - `hold`：常驻武装，覆盖整个文档生命周期。
     *
     * 为什么长窗口开关也必须无条件（见类注释的 2026-10-30 修正）：
     * 「按连接健康门控」会在连接抖动的那一刻开洞，让 hidden 漏过去，
     * 页面一旦 suspend 就永久记住，此后每次回前台都恢复。
     * 恢复能力由原生承担（后台重建 + 回前台判定），不依赖页面自愈。
     */
    fun shielded(
        filePick: Boolean = false,
        keepAlive: Boolean = false,
        hold: Boolean = false,
    ): Boolean = filePick || keepAlive || hold

    /**
     * 序列化成注入用的 JS 配置对象。
     *
     * 只含标识符与布尔值，不含任何用户数据，可直接拼接。
     *
     * 注意 `gated` 字段：它明确告诉 JS「不要按连接健康门控」。
     * 上一版这里有 `healthyWs` 白名单，正是它造成了开洞。
     */
    fun toJsConfig(holdDefault: Boolean): String {
        val events = BLOCKED_EVENTS.joinToString(",") { "\"$it\"" }
        val hold = if (holdDefault) "true" else "false"
        return """{"events":[$events],"hold":$hold,"gated":false}"""
    }
}
