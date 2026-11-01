package com.zcodeapp.remote.shell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下拉刷新的触发判定。
 *
 * 这套功能的唯一风险是**误触**：把用户正常的滚动吃掉，比没有下拉刷新更糟。
 * 所以用例的重点不是「能触发」，而是「什么情况下绝不触发」。
 *
 * 尤其注意 ime 那一组：输入法弹起后页面被压到上半屏、内容被压缩、
 * scrollY 变成 0，用户想回看前面的对话而向下拖——此时「页面在顶部」
 * 与「起手在顶部区域」全部成立，只有 ime 这一条能挡住。
 */
class PullRefreshGateTest {

    private val slop = 8f

    @Test
    fun topZoneAtTopStarts() {
        assertTrue(
            PullRefreshGate.canStart(
                startScreenYDp = 40f,
                atTop = true,
                imeVisible = false,
                touchSlopDp = slop,
            ),
        )
    }

    @Test
    fun startZoneToleratesSlop() {
        // 起手点略低于边界但仍在 slop 容差内：算顶部区域。
        assertTrue(
            PullRefreshGate.canStart(
                startScreenYDp = PullRefreshGate.START_ZONE_DP + slop,
                atTop = true,
                imeVisible = false,
                touchSlopDp = slop,
            ),
        )
        assertFalse(
            PullRefreshGate.canStart(
                startScreenYDp = PullRefreshGate.START_ZONE_DP + slop + 1f,
                atTop = true,
                imeVisible = false,
                touchSlopDp = slop,
            ),
        )
    }

    @Test
    fun pageNotAtTopNeverStarts() {
        // 用户在会话中间往下拖是想看前面的内容：那是滚动，绝不能刷新。
        assertFalse(
            PullRefreshGate.canStart(
                startScreenYDp = 10f,
                atTop = false,
                imeVisible = false,
                touchSlopDp = slop,
            ),
        )
    }

    @Test
    fun imeVisibleNeverStarts() {
        // 输入法弹起时页面被压缩、scrollY 为 0，前两条前提全部成立——
        // 这条是唯一能挡住「想回看内容却被刷新」的判据。
        assertFalse(
            PullRefreshGate.canStart(
                startScreenYDp = 10f,
                atTop = true,
                imeVisible = true,
                touchSlopDp = slop,
            ),
        )
    }

    @Test
    fun upwardDragIsNotClaimed() {
        assertFalse(PullRefreshGate.shouldClaim(dyDp = -50f, dxDp = 0f, touchSlopDp = slop))
    }

    @Test
    fun smallMovementIsNotClaimed() {
        // 位移还没超过 2 倍 slop：可能只是手指抖动或点击。
        assertFalse(PullRefreshGate.shouldClaim(dyDp = slop * 2, dxDp = 0f, touchSlopDp = slop))
    }

    @Test
    fun diagonalDragIsNotClaimed() {
        // 纵向没有明显占优：用户在斜着拖，交给页面处理。
        assertFalse(PullRefreshGate.shouldClaim(dyDp = 30f, dxDp = 30f, touchSlopDp = slop))
    }

    @Test
    fun clearVerticalDragIsClaimed() {
        assertTrue(PullRefreshGate.shouldClaim(dyDp = 40f, dxDp = 5f, touchSlopDp = slop))
    }

    @Test
    fun thresholdNeedsFullDistance() {
        assertFalse(
            PullRefreshGate.reached(
                dyDp = PullRefreshGate.THRESHOLD_DP,
                dxDp = 0f,
            ),
        )
        assertTrue(
            PullRefreshGate.reached(
                dyDp = PullRefreshGate.THRESHOLD_DP + 1f,
                dxDp = 0f,
            ),
        )
    }

    @Test
    fun thresholdIgnoresDiagonal() {
        // 距离够了但方向不明确：不触发。
        assertFalse(
            PullRefreshGate.reached(
                dyDp = PullRefreshGate.THRESHOLD_DP + 10f,
                dxDp = PullRefreshGate.THRESHOLD_DP + 10f,
            ),
        )
    }
}
