package com.zcodeapp.remote.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 返回路由的判定边界。
 *
 * 这些用例覆盖的都是真机上会出错、而代码看起来「很合理」的地方：
 * 回包被 JSON 再编码一层、脚本没注入返回 null、坏 JSON、
 * 以及「两次返回才退出」在三个入口之间共享时间戳。
 */
class BackRouterTest {

    @Test
    fun pageHandledLayerIsRespected() {
        val action = BackRouter.parse("""{"handled":true,"action":"settings","layer":"settings"}""")
        assertEquals("settings", (action as BackRouter.Action.PageHandled).layer)
    }

    @Test
    fun doubleEncodedReplyStillParses() {
        // evaluateJavascript 会把 JS 字符串再编码一次：真实回包长这样。
        val raw = "\"{\\\"handled\\\":true,\\\"action\\\":\\\"chat\\\",\\\"layer\\\":\\\"chat\\\"}\""
        val action = BackRouter.parse(raw)
        assertEquals("chat", (action as BackRouter.Action.PageHandled).layer)
    }

    @Test
    fun popupAsksForTrustedEscape() {
        val action = BackRouter.parse("""{"handled":false,"action":"escape","layer":"popup"}""")
        assertEquals("popup", (action as BackRouter.Action.DismissPopup).layer)
    }

    @Test
    fun outermostPageGoesToShell() {
        val action = BackRouter.parse("""{"handled":false,"action":"shell","layer":"list"}""")
        assertEquals("list", (action as BackRouter.Action.ToShell).layer)
    }

    @Test
    fun missingScriptIsUnresponsiveNotExit() {
        // 脚本没注入时 evaluateJavascript 返回 "null"：绝不能据此退出会话。
        val action = BackRouter.parse("null")
        assertTrue(action is BackRouter.Action.Unresponsive)
        assertEquals("no-answer", (action as BackRouter.Action.Unresponsive).layer)
    }

    @Test
    fun malformedJsonIsUnresponsive() {
        val action = BackRouter.parse("""{"handled":tru""")
        assertTrue(action is BackRouter.Action.Unresponsive)
    }

    @Test
    fun unknownActionFallsBackToConfirm() {
        // 旧版脚本或页面换了协议：字段在但没有可识别的 action。
        val action = BackRouter.parse("""{"handled":false,"action":"mystery","layer":"chat"}""")
        assertTrue(action is BackRouter.Action.Unresponsive)
        assertEquals("chat", (action as BackRouter.Action.Unresponsive).layer)
    }

    @Test
    fun exitNeedsTwoPressesInsideWindow() {
        val first = 10_000L
        // 第一次按：只提示，不退出。
        assertFalse(BackRouter.shouldExit(first, 0L))
        // 2 秒内再按：退出。
        assertTrue(BackRouter.shouldExit(first + 1_500L, first))
        // 恰好等于窗口边界：仍算「连按」。
        assertTrue(BackRouter.shouldExit(first + BackRouter.EXIT_CONFIRM_WINDOW_MS, first))
        // 超出窗口：重新提示。
        assertFalse(BackRouter.shouldExit(first + BackRouter.EXIT_CONFIRM_WINDOW_MS + 1L, first))
    }

    @Test
    fun clockGoingBackwardsDoesNotExit() {
        // 系统时间被调整（NTP 回拨）时不能误判成「刚按过」。
        assertFalse(BackRouter.shouldExit(9_000L, 10_000L))
    }

    @Test
    fun scriptMissingDetectsNullAndBlank() {
        assertTrue(BackRouter.isScriptMissing(null))
        assertTrue(BackRouter.isScriptMissing(""))
        assertTrue(BackRouter.isScriptMissing("null"))
        assertFalse(BackRouter.isScriptMissing("\"{\\\"handled\\\":true}\""))
    }

    @Test
    fun decodeLeavesPlainJsonAlone() {
        val plain = """{"handled":false,"action":"shell"}"""
        assertEquals(plain, BackRouter.decode(plain))
    }
}
