package com.zcodeapp.remote.shell

/**
 * 下拉刷新的触发判定（纯 JVM）。
 *
 * 为什么把判定从 View 里抽出来：误触是这套功能唯一真正的风险。
 * 在 View 里判定就没法用单测覆盖，而真机复现「输入法弹起后下拉被吃掉」
 * 需要模拟器 + 中文输入法 + 长会话，成本极高、还不一定复现得准。
 * 抽成纯函数后，每一种「该不该认」都能被断言钉住。
 */
object PullRefreshGate {

    /** 起手区域：距屏幕顶部 88dp（约一个状态栏 + 一行工具栏）。 */
    const val START_ZONE_DP = 88f

    /** 触发距离：88dp。 */
    const val THRESHOLD_DP = 88f

    /** 纵向必须是横向的多少倍，才算「在往下拖」而不是斜滑。 */
    const val VERTICAL_DOMINANCE = 1.5f

    /** 起手时刻能否参与下拉刷新。 */
    fun canStart(
        startScreenYDp: Float,
        atTop: Boolean,
        imeVisible: Boolean,
        touchSlopDp: Float,
    ): Boolean {
        if (imeVisible) return false
        if (!atTop) return false
        return startScreenYDp <= START_ZONE_DP + touchSlopDp
    }

    /**
     * 拖拽过程中是否应当接管。
     *
     * @param dy 纵向位移（向下为正）
     * @param dx 横向位移
     */
    fun shouldClaim(dyDp: Float, dxDp: Float, touchSlopDp: Float): Boolean {
        if (dyDp < 0f) return false
        if (dyDp <= touchSlopDp * 2) return false
        return dyDp > kotlin.math.abs(dxDp) * VERTICAL_DOMINANCE
    }

    /** 是否已经达到触发阈值。 */
    fun reached(dyDp: Float, dxDp: Float): Boolean {
        if (dyDp <= THRESHOLD_DP) return false
        return dyDp > kotlin.math.abs(dxDp) * VERTICAL_DOMINANCE
    }
}
