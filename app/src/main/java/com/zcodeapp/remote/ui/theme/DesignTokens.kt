package com.zcodeapp.remote.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.dp

/**
 * 间距节奏。
 *
 * 不用「随手 16.dp」：固定几档，页面之间才有一致的呼吸感。
 * 4 的倍数，但刻意留出 6 与 10 两个中间档，避免所有间距都落在 8/16 上显得僵硬。
 */
object Gap {
    /** 2dp — 图标与文字之间的贴合间距 */
    val hair = 2.dp
    /** 6dp — 行内元素 */
    val xs = 6.dp
    /** 10dp — 紧凑块间距 */
    val sm = 10.dp
    /** 16dp — 常规块间距 */
    val md = 16.dp
    /** 24dp — 区块之间 */
    val lg = 24.dp
    /** 36dp — 大段落之间 */
    val xl = 36.dp
    /** 56dp — 页面上下主留白 */
    val xxl = 56.dp
}

/**
 * 动效 token。
 *
 * 三档时长 + 两种曲线，避免「全页面一个 0.3s ease」（那是 CSS 默认值的味道）。
 * 物理感的交互（按压、展开）用弹簧，页面级转场用缓动曲线。
 */
object MotionSpec {
    /** 状态微反馈：120ms，几乎瞬时但不突兀 */
    const val INSTANT = 120
    /** 常规转场：220ms */
    const val BASE = 220
    /** 大块内容进出：360ms */
    const val SLOW = 360

    /** 入场减速：快起慢停，适合内容出现 */
    val Decelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    /** 标准双向：适合位置变化 */
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** 按压回弹：轻微过冲，让触感有生命 */
    fun press() = spring<Float>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    /** 展开/收起：阻尼大一些，避免抖动 */
    fun expand() = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium,
    )
}
