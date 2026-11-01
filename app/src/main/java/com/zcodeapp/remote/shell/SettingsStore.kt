package com.zcodeapp.remote.shell

import android.content.Context
import android.content.SharedPreferences

/**
 * 应用设置。
 *
 * 【2026-10-27 变更】`l1Available` 由恒为 false 改为 true。
 *
 * 旧逻辑是「能力开关必须由真机验证结果决定」，G4-G6 没通过前把 L1 整个藏起来。
 * 结果是用户拿不到任何后台能力，而实际需求是「息屏也要收到通知」。
 * 现在 L1 由 KeepAliveService（常驻前台服务 + 开机自启 + 电池优化白名单）实现，
 * 这套机制不依赖页面存活，所以可以开放。
 *
 * 能力边界如实写在设置页的说明文字里，而不是靠隐藏开关。
 */
class SettingsStore(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("zrcode_settings", Context.MODE_PRIVATE)

    /** L1（后台保活）是否对用户可见/可启用。 */
    val l1Available: Boolean get() = true

    /** L2（原生独立 Relay）是否对用户可见。G7-G10 未通过前恒为 false，且 relay/ 尚未实现。 */
    val l2Available: Boolean get() = false

    var backgroundListening: Boolean
        get() = l1Available && sp.getBoolean(KEY_BACKGROUND, false)
        set(value) {
            sp.edit().putBoolean(KEY_BACKGROUND, l1Available && value).apply()
        }

    private companion object {
        const val KEY_BACKGROUND = "background_listening"
    }
}
