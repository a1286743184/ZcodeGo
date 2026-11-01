package com.zcodeapp.remote.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回前台会话恢复判据。
 *
 * 这个判定错了的代价很具体：
 * - 该恢复却没恢复 → 用户停在「选择电脑」页，收不到任何通知（本次修的就是这个）
 * - 不该恢复却恢复 → 用户每次回前台页面都刷一下，丢掉滚动位置与输入草稿
 *
 * 所以两个方向都要锁住。
 */
class SessionRecoveryGateTest {

    private fun probe(
        ws: String = "open",
        wsAgeMs: Long = 500,
        hasSession: Boolean = true,
        onPicker: Boolean = false,
        bridge: Boolean = true,
    ) = """{"ws":"$ws","wsAgeMs":$wsAgeMs,"everOpen":true,"hasSession":$hasSession,"onPicker":$onPicker,"bridge":$bridge}"""

    @Test
    fun healthySessionIsLeftAlone() {
        val v = SessionRecoveryGate.shouldRecover(probe())
        assertFalse("正常会话页绝不能重载", v.recover)
        assertEquals("in-session", v.reason)
    }

    @Test
    fun droppedToPickerTriggersRecovery() {
        // 用户实测的现象：回前台停在「选择电脑」页，WS 已关、无会话内容。
        val v = SessionRecoveryGate.shouldRecover(
            probe(ws = "closed", wsAgeMs = 60_000, hasSession = false, onPicker = true),
        )
        assertTrue("掉回选择电脑页必须重新导航", v.recover)
        assertEquals("dropped-to-picker", v.reason)
    }

    @Test
    fun reconnectingSessionIsNeverTouched() {
        // 【真机日志教训】15:03:10 页面自己 ws.open、200ms 后正常关闭，
        // 旧判据在 10 秒后就整页重载 → 用户看到一片全白。
        // 现在只要还在会话页，无论 WS 关多久都不插手。
        val fresh = SessionRecoveryGate.shouldRecover(
            probe(ws = "closed", wsAgeMs = 1_500, hasSession = true),
        )
        assertFalse("正在重连不能被打断", fresh.recover)

        val stale = SessionRecoveryGate.shouldRecover(
            probe(ws = "closed", wsAgeMs = 120_000, hasSession = true),
        )
        assertFalse("即使关了很久也不重载——页面自己会恢复", stale.recover)
        assertEquals("in-session-reconnecting", stale.reason)
    }

    @Test
    fun missingBridgeIsSkipped() {
        // 桥不在说明文档正在换，此刻导航只会打架。
        val v = SessionRecoveryGate.shouldRecover(probe(bridge = false, onPicker = true, hasSession = false))
        assertFalse(v.recover)
        assertEquals("bridge-gone", v.reason)
    }

    @Test
    fun garbageInputNeverRecovers() {
        assertFalse(SessionRecoveryGate.shouldRecover(null).recover)
        assertFalse(SessionRecoveryGate.shouldRecover("").recover)
        assertFalse(SessionRecoveryGate.shouldRecover("not json").recover)
        assertFalse(SessionRecoveryGate.shouldRecover("""{"err":"boom"}""").recover)
    }

    @Test
    fun unknownStateIsConservative() {
        // 既不在会话、也没识别出选择页（可能在加载中）：不动。
        val v = SessionRecoveryGate.shouldRecover(
            probe(ws = "none", wsAgeMs = -1, hasSession = false, onPicker = false),
        )
        assertFalse("状态不明时宁可不动作", v.recover)
    }
}
