package com.zcodeapp.remote.web

/**
 * 页面侧 nav.js 的脚本入口。
 *
 * 与 [SessionJumpScript] 等脚本不同，nav.js 是**常驻资产**（随 document-start 注入，
 * 见 InjectionInstaller 的 ORDER），这里只负责构造调用它的表达式。
 *
 * 为什么不把调用内联在控制器里：调用表达式与回包格式是一对契约，
 * 两处各写一遍必然漂移；集中在同一处才能被单测锁住。
 */
object PageNavScript {

    private const val ROOT = "(window.__zrNav ? window.__zrNav"

    /** 当前层级快照（诊断与「这次返回是否真的动了」的复核都用它）。 */
    fun state(): String = "$ROOT.state() : null)"

    /** 执行一次分层后退。 */
    fun back(): String = "$ROOT.back() : null)"
}

