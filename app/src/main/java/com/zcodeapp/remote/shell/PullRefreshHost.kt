package com.zcodeapp.remote.shell

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * 下拉刷新宿主。
 *
 * 只做一件事：**在页面已经滚到顶部、且手指从顶部窄区域起手向下拖时**，
 * 把这次手势识别成「主动刷新」。
 *
 * 为什么不做边缘滑动手势：安卓系统自带的手势导航与返回键都会产生 back 事件，
 * 已经由 RemoteScreen 的 BackHandler 接住并走分层判定。再自绘一套边缘手势
 * 只会与系统的返回竞争，而且两边行为不一致时用户无法预判。
 *
 * 为什么必须是父容器（ViewGroup）：WebView 自己会
 * `requestDisallowInterceptTouchEvent(true)`，Compose 层拿不到这个事实，
 * 只有 dispatch 链上的父容器既看得见全部事件、又能决定要不要抢过来。
 *
 * 触发条件（五条同时成立才抢，任何一条不成立就完全放行）：
 * 1. 起手点距屏幕顶部不超过 [startZonePx]（默认 88dp）
 * 2. 页面已经在顶部（canScrollVertically(-1) == false）
 * 3. 纵向位移超过 [thresholdPx]（默认 88dp）
 * 4. 纵向位移明显大于横向（1.5 倍），排除斜滑
 * 5. **输入法没有弹起**
 *
 * 条件 1、2、5 是**防止误伤**的核心：
 * - 条件 2 保证「页面还能往上滚」时下拉一定是滚动，不会被吃掉。
 *   用户在会话中间往下拖是想看前面的内容，那是滚动，绝不该刷新。
 * - 条件 5 解决的是最容易踩到的一种：输入法弹起后页面被压到上半屏，
 *   用户想回看前面的对话内容而向下拖——此时页面确实在顶部（内容被压缩后
 *   scrollY 为 0），起手点也确实落在屏幕顶部区域，前四条全部成立。
 *   只有「输入法弹起时不认下拉刷新」这一条能把这种情况挡在外面。
 *   输入法在场时用户的意图是「看内容」，不是「重置页面」。
 * - 条件 1 保证从页面中部（例如长列表中间）向下拖不会被识别成刷新。
 *
 * 判定失败时宿主不做任何事：事件照常交给 WebView，
 * 页面行为与没有这个宿主时完全一致。
 */
@SuppressLint("ViewConstructor")
class PullRefreshHost(
    context: Context,
    private val onRefresh: () -> Unit,
) : FrameLayout(context) {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val density = resources.displayMetrics.density

    /** 阈值与判定全部来自 [PullRefreshGate]：两处各写一份常量必然漂移。 */
    private val startZonePx = PullRefreshGate.START_ZONE_DP * density
    private val thresholdPx = PullRefreshGate.THRESHOLD_DP * density
    private val slopDp = touchSlop / density

    private var downX = 0f
    private var downY = 0f
    private var downScreenY = 0f

    /** 本次手势已被我们接管。 */
    private var claimed = false

    /** 本次手势是否仍可能成为刷新。 */
    private var candidate = false

    /** 下拉进度 0..1，供上层画那条细线。 */
    private var progress = 0f
    private var progressListener: ((Float) -> Unit)? = null

    private var content: View? = null

    /**
     * 输入法是否弹起。
     *
     * 由上层通过 [setImeVisible] 告知，而不是自己监听：
     * 窗口 inset 只有 Activity 侧能可靠拿到，宿主去猜会漏。
     * 默认 false（没有输入法时行为与旧版一致）。
     */
    private var imeVisible = false

    fun setImeVisible(visible: Boolean) {
        imeVisible = visible
    }

    fun setPullProgressListener(listener: ((Float) -> Unit)?) {
        progressListener = listener
    }

    override fun addView(child: View?, index: Int, params: ViewGroup.LayoutParams?) {
        super.addView(child, index, params)
        if (childCount == 1) content = child
    }

    private fun atTop(): Boolean {
        val c = content ?: return true
        return !c.canScrollVertically(-1)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                claimed = false
                progress = 0f
                downX = ev.x
                downY = ev.y
                // 用屏幕坐标判断起手区域：View 可能不在屏幕顶端（有状态栏内边距时
                // 本地坐标会偏移，用本地 y 会把区域算错）。
                downScreenY = ev.rawY
                // 五条起手前提在这里先判：不满足就整次手势都不参与。
                candidate = PullRefreshGate.canStart(
                    startScreenYDp = downScreenY / density,
                    atTop = atTop(),
                    imeVisible = imeVisible,
                    touchSlopDp = slopDp,
                )
                // 起手阶段一律不抢：点击、长按、滚动都要 WebView 先拿到。
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (claimed) return true
                if (!candidate) return false
                val dx = ev.x - downX
                val dy = ev.y - downY
                // 向上滑：用户在滚动页面，立刻放弃。
                if (dy < 0) {
                    candidate = false
                    return false
                }
                if (PullRefreshGate.shouldClaim(dy / density, dx / density, slopDp)) {
                    // 再复核一次「页面是否还在顶部」：起手时在顶部，
                    // 但拖拽过程中页面可能已经因为惯性滚动了。
                    if (atTop()) {
                        claimed = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                    candidate = false
                }
                return false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                candidate = false
                return claimed
            }
        }
        return claimed
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (candidate) {
                    progress = (dy / (thresholdPx * 2)).coerceIn(0f, 1f)
                    progressListener?.invoke(progress)
                    if (PullRefreshGate.reached(dy / density, dx / density)) {
                        // 到点即触发，不等抬手：下拉刷新到位就该立刻开始。
                        candidate = false
                        progress = 0f
                        progressListener?.invoke(0f)
                        onRefresh()
                        return true
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasClaimed = claimed
                claimed = false
                candidate = false
                if (progress > 0f) {
                    progress = 0f
                    progressListener?.invoke(0f)
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                return wasClaimed
            }
        }
        return true
    }
}
