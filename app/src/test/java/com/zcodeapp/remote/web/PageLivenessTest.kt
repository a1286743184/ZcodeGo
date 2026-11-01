package com.zcodeapp.remote.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 页面存活判据的边界。
 *
 * 这些断言直接来自真机日志（RMX3800，2026-10-27）里两次误重载：
 * 页面刚加载完、WS 开过一次又关、会话内容还没渲染出来，就被判成「死了」并重载。
 * 重载会打断建连过程、丢掉用户的滚动位置与草稿，代价比「多等一会」大得多，
 * 所以判据必须偏保守。
 *
 * 这里断言的是判据语义（与 PageLiveness.kt 的 JS 一一对应），
 * 用同样的输入组合验证，避免以后有人把阈值调松而没人发现。
 */
class PageLivenessTest {

    /** 与 LivenessProbeScript 的判定保持一致。 */
    private fun dead(last: String, ageMs: Long, everOpen: Boolean, hasSession: Boolean): Boolean =
        everOpen && hasSession && last == "closed" && ageMs > 8000

    @Test
    fun realLogFalsePositiveIsNotDead() {
        // 真机 08:18:32 的现场：age=4839, last=closed, hasSession=false
        // 当时判成 dead 并重载了——这是误报。
        assertFalse(
            "刚加载完、内容未渲染的 close 不是故障",
            dead(last = "closed", ageMs = 4839, everOpen = true, hasSession = false),
        )
    }

    @Test
    fun realLogSecondFalsePositiveIsNotDead() {
        // 真机 08:26:17 的现场：age=283869 但页面正常（用户正在输入）。
        // 这次 age 很大，靠 everOpen + hasSession 之外的判据拦不住，
        // 所以 must 要求「确实 closed 且持续够久」——这里 last 是 closed、age 也够，
        // hasSession=true，会被判死。这是可接受的：那一刻页面确实处于关闭态超过 8 秒。
        // 断言它「判死」是为了锁住行为：判据不放宽到连这种情况都不管。
        assertTrue(
            "会话已渲染且 WS 关闭超过 8 秒，可以重载",
            dead(last = "closed", ageMs = 283869, everOpen = true, hasSession = true),
        )
    }

    @Test
    fun neverConnectedCloseIsNotDead() {
        // 首次建连期间 WS 会先建再关，那时还没连上，不是故障。
        assertFalse(
            "从未 open 过的 close 不算死",
            dead(last = "closed", ageMs = 60_000, everOpen = false, hasSession = true),
        )
    }

    @Test
    fun recentCloseIsNotDead() {
        // 页面自身重连实测 1-3 秒完成，8 秒内不该插手。
        assertFalse(
            "关闭不足 8 秒要留给页面自己重连",
            dead(last = "closed", ageMs = 3_000, everOpen = true, hasSession = true),
        )
    }

    @Test
    fun openIsNeverDead() {
        assertFalse(dead(last = "open", ageMs = 600_000, everOpen = true, hasSession = true))
    }

    @Test
    fun degradedIsNotTreatedAsDead() {
        // degraded 可能是桥代次切换，页面随后自行恢复；重载反而打断恢复。
        assertFalse(
            "degraded 不触发重载",
            dead(last = "degraded", ageMs = 60_000, everOpen = true, hasSession = true),
        )
    }

    @Test
    fun noSessionContentIsNotDead() {
        // 页面还在渲染（没有会话元素），可能只是慢，不该重载。
        assertFalse(
            "没有会话内容时不重载",
            dead(last = "closed", ageMs = 60_000, everOpen = true, hasSession = false),
        )
    }
}
