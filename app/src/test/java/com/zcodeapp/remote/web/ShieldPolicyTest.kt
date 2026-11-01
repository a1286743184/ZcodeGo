package com.zcodeapp.remote.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 可见性屏蔽策略的边界。
 *
 * ## 2026-10-30 语义反转，测试跟着反转
 *
 * 上一版是「按连接健康门控」：连接 closed/degraded 就放行，理由是
 * 「断了还挡，页面收不到可见性变化就永远无法自愈」。真机日志证伪了这个设计。
 *
 * 0.3.5 的 `diag-20261030-18.log`（18:17:31 / 18:25:07 两次回前台）：
 *
 * ```
 * 18:17:31.699 web.shield resume keepalive held   ← 我们以为屏蔽还在
 * 18:17:31.742 session.probe in-session
 * 18:17:32.231 ws.closed WS_CLOSED_CLEAN          ← 0.2 秒后页面自己 close()
 * ```
 *
 * `WS_CLOSED_CLEAN` 只来自页面主动 `socket.close()`（`reconnectNow()`），
 * 而它只从 `onRecover` 进来，`onRecover` 只在「已 suspend」标志为真时跑。
 * 所以那个标志早就被置真了 —— 后台期间页面确实收到过 hidden。
 *
 * 洞就在门控本身：连接在后台短暂抖成 `closed` 的那一瞬间屏蔽失效，
 * 若恰好有 hidden 派发，页面就 suspend 并永久记住，此后**每次**回前台都恢复。
 *
 * 现在是**无条件屏蔽**，恢复权收回原生。下面的测试锁住这个新语义。
 */
class ShieldPolicyTest {

    @Test
    fun noSwitchMeansNoShield() {
        assertFalse("用户没开保活、也没在选文件，不该挡", ShieldPolicy.shielded())
    }

    @Test
    fun filePickAlwaysShields() {
        // 选文件期间必须挡：否则页面挂起 transport，选完回来上传请求被丢弃。
        assertTrue(ShieldPolicy.shielded(filePick = true))
    }

    @Test
    fun keepAliveAlwaysShields() {
        assertTrue(ShieldPolicy.shielded(keepAlive = true))
    }

    @Test
    fun holdAlwaysShields() {
        assertTrue(ShieldPolicy.shielded(hold = true))
    }

    /**
     * 【关键，语义已反转】连接断开时**仍然要挡**。
     *
     * 上一版这条是 `assertFalse`（断开放行）。真机日志证明放行是错的：
     * 连接抖动那一瞬间的开洞让 hidden 漏过去，页面 suspend 后永久记住，
     * 每次回前台都走一遍恢复（主动 close + 重连 + 可能整页 reload）。
     *
     * 现在挡着，页面永远看不到 hidden；恢复由原生负责
     * （后台 probe-timeout 重建 + 回前台 probeAndRecoverSession 判定）。
     */
    @Test
    fun holdShieldsEvenWhenConnectionIsClosed() {
        assertTrue(
            "断线也要挡——开洞会让页面 suspend 并永久记住",
            ShieldPolicy.shielded(hold = true),
        )
    }

    /**
     * 拦截事件清单必须覆盖页面生命周期助手监听的全部事件。
     *
     * 漏一个的后果：一旦「已 suspend」标志被任何路径置真，
     * 漏掉的那个事件就会把恢复流程放出来。这组清单来自反编译 bundle 的 e4t 助手。
     */
    @Test
    fun blockedEventsCoverPageLifecycleListeners() {
        val required = setOf(
            "visibilitychange", // document
            "pagehide",         // document
            "freeze",           // document
            "resume",           // document
            "pageshow",         // window
            "online",           // window
        )
        assertEquals(required, ShieldPolicy.BLOCKED_EVENTS.toSet())
    }

    @Test
    fun doesNotBlockFocusOrBlur() {
        // 页面助手不监听 focus/blur，拦掉会干扰输入框与 IME。
        assertFalse("focus" in ShieldPolicy.BLOCKED_EVENTS)
        assertFalse("blur" in ShieldPolicy.BLOCKED_EVENTS)
    }

    @Test
    fun jsConfigCarriesHoldDefault() {
        assertTrue(ShieldPolicy.toJsConfig(holdDefault = true).contains("\"hold\":true"))
        assertTrue(ShieldPolicy.toJsConfig(holdDefault = false).contains("\"hold\":false"))
    }

    @Test
    fun jsConfigCarriesEventList() {
        val on = ShieldPolicy.toJsConfig(holdDefault = true)
        ShieldPolicy.BLOCKED_EVENTS.forEach {
            assertTrue("事件清单必须带上 $it", on.contains("\"$it\""))
        }
    }

    /**
     * 配置里必须显式声明「不做连接健康门控」。
     *
     * 这是防止有人（包括未来的我）顺手把门控加回去的护栏：
     * `gated:false` 出现在配置里，就说明这个决定是有意为之、有日志依据的。
     */
    @Test
    fun jsConfigDeclaresNoHealthGating() {
        assertTrue(
            "配置必须显式声明不做健康门控",
            ShieldPolicy.toJsConfig(holdDefault = true).contains("\"gated\":false"),
        )
    }
}
