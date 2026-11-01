package com.zcodeapp.remote.web

import com.zcodeapp.remote.protocol.JsonValue

/**
 * 回前台时的会话恢复判据。
 *
 * 背景：后台保活期间我们用可见性屏蔽（file-pick-guard.js 的 keepAlive 分支）
 * 阻止页面自行 suspend transport。但屏蔽不是万能的——进程被系统杀掉重建、
 * 渲染进程崩溃、页面自身逻辑走了别的分支，都可能让会话掉回「选择电脑」页。
 * 那种状态下页面不再产生任何任务事件，通知链路等于断了。
 *
 * 判据必须保守：只有「明确不在会话里」才重新导航。
 * 正常会话页（有任务行、WS 开着）绝不能碰——重载会丢用户的滚动位置与输入草稿。
 *
 * 抽成纯函数是为了在 JVM 上锁住边界：这个判定错了的代价是「用户每次回前台
 * 页面都刷一下」，比不恢复更烦人。
 */
object SessionRecoveryGate {

    /**
     * @param rawJson 页面探针返回的 JSON
     * @return 是否需要重新导航到目标 URL
     */
    fun shouldRecover(rawJson: String?): Verdict {
        val text = rawJson?.trim().orEmpty()
        if (text.isEmpty() || text == "null") return Verdict(false, "no-probe")
        val obj = runCatching { JsonValue.parse(text) as? JsonValue.Obj }.getOrNull()
            ?: return Verdict(false, "bad-json")
        if (obj.str("err") != null) return Verdict(false, "probe-error")

        // 桥不在说明文档已经换了（导航中或重建中），此刻重载只会打架。
        val bridge = obj.value("bridge").let { it is JsonValue.Bool && it.value }
        if (!bridge) return Verdict(false, "bridge-gone")

        val ws = obj.str("ws")
        val hasSession = obj.value("hasSession").let { it is JsonValue.Bool && it.value }
        val onPicker = obj.value("onPicker").let { it is JsonValue.Bool && it.value }

        // 在会话里且连接是通的：什么都不做，这是最常见的情况。
        if (hasSession && ws == "open") return Verdict(false, "in-session")

        // 掉回「选择电脑」页：会话肯定没了，必须重新导航才能回到远控。
        // 这一条是用户实测到的现象（每次回前台都停在连接页）。
        if (onPicker) return Verdict(true, "dropped-to-picker")

        // 在会话页但 WS 关着：页面正在自己重连，**不要插手**。
        //
        // 【2026-10-27 真机日志修正】这里原来会在 8 秒后判定 "ws-stale" 并重新导航，
        // 实测把用户坑了：15:03:10 页面自己 ws.open、200ms 后正常关闭（握手波动），
        // 15:03:20 我的判定就触发了整页重载 → 用户看到一片全白，还要手动返回再进。
        // 页面自身的重连（含 pair_status 探测 + 重建 socket）实测需要十几秒，
        // 抢在它前面重载只会把正在恢复的连接打断。
        //
        // 现在的立场：只要还在会话页（hasSession=true），就认为页面有能力自愈，
        // 一律不重载。真正掉出会话的情况由上面的 onPicker 分支覆盖。
        if (hasSession) return Verdict(false, "in-session-reconnecting")

        // 既不在会话也没识别出选择页：可能是加载中，不动。
        return Verdict(false, "unknown")
    }

    /**
     * WS 关闭多久算「掉线」。
     *
     * 【2026-10-27 修正】已不再用于「会话页内」的判定（那条分支现在一律不重载）。
     * 保留此常量只为兼容旧日志里的 reason 码，不再参与决策。
     */
    private const val STALE_WS_MS = 8_000L

    data class Verdict(val recover: Boolean, val reason: String)
}
