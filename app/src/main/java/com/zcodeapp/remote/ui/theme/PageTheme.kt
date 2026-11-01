package com.zcodeapp.remote.ui.theme

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 页面实际配色。
 *
 * 为什么原生壳要跟着页面走：远控页是深色还是浅色由页面自己的主题设置决定
 * （localStorage `zcode-theme`，可选 system/light/dark/zai-light/zai-dark）。
 * 壳层如果固定深色，用户把页面切成浅色后，状态栏、入口页、设置页会与页面割裂，
 * 视觉上像是两个应用拼在一起。
 *
 * 数据来源是页面**实际渲染出来的计算样式**，不是配置值——配置是 `system` 时，
 * 页面自己会解析成具体某一套，我们只需要跟最终结果。
 */
data class PageTheme(
    val background: Color,
    val foreground: Color,
) {
    /** 由背景亮度判定明暗，不依赖页面的 class 命名。 */
    val isDark: Boolean get() = background.luminance() < 0.5f

    /**
     * 卡片/面板色。
     *
     * 深色页面向**亮**偏移（官方 #161616 → 卡片 #2b2b2b，约 0.07 的亮度差），
     * 浅色页面向**白**偏移（官方 #f8f8f8 → 卡片 #ffffff）。
     * 两个方向都是为了「卡片浮在底上」，而不是简单按比例混合。
     */
    val surface: Color get() = if (isDark) {
        blend(background, foreground, 0.075f)
    } else {
        blend(background, Color.White, 1f)
    }

    /** 输入框/次级容器：深色比 surface 略暗，浅色比 surface 略深。 */
    val surfaceVariant: Color get() = if (isDark) {
        blend(background, foreground, 0.035f)
    } else {
        blend(background, foreground, 0.025f)
    }

    /** 分隔线：比 surface 再明显一点，但不至于抢视线。 */
    val outline: Color get() = blend(background, foreground, if (isDark) 0.13f else 0.09f)

    /** 次要文字：向背景方向退让，保证可读但明显弱于主文字。 */
    val muted: Color get() = blend(foreground, background, 0.42f)

    private fun blend(a: Color, b: Color, t: Float) = Color(
        red = a.red + (b.red - a.red) * t,
        green = a.green + (b.green - a.green) * t,
        blue = a.blue + (b.blue - a.blue) * t,
        alpha = 1f,
    )
}

/**
 * 页面主题的本机缓存。
 *
 * 需要缓存的原因：入口页与设置页在远控页之前，那时页面还没加载、拿不到计算样式。
 * 用上一次已知的配色，比每次启动都闪一下系统主题更连贯。
 * 从未同步过时返回 null，由调用方退回系统深浅色。
 */
object PageThemeStore {
    private const val PREF = "zr_page_theme"
    private const val KEY_BG = "bg"
    private const val KEY_FG = "fg"

    private val _current = MutableStateFlow<PageTheme?>(null)
    val current: StateFlow<PageTheme?> = _current.asStateFlow()

    fun load(context: Context) {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val bg = sp.getInt(KEY_BG, -1)
        val fg = sp.getInt(KEY_FG, -1)
        if (bg == -1 || fg == -1) return
        _current.value = PageTheme(Color(bg), Color(fg))
    }

    /**
     * 写入页面配色。值没变就不写盘，避免每次页面加载都触发一次 IO 与重组。
     * 返回是否发生了变化，调用方可据此决定要不要更新状态栏图标。
     */
    fun update(context: Context, theme: PageTheme): Boolean {
        if (_current.value == theme) return false
        _current.value = theme
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putInt(KEY_BG, theme.background.toArgb())
            .putInt(KEY_FG, theme.foreground.toArgb())
            .apply()
        return true
    }

    /** 解析 `rgb(22, 22, 22)` / `rgba(22,22,22,1)` / `#161616` 三种形态。 */
    fun parseColor(raw: String?): Color? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        if (s.startsWith("#")) {
            return runCatching {
                val hex = s.removePrefix("#")
                when (hex.length) {
                    6 -> Color(0xFF000000L.or(hex.toLong(16)).toInt())
                    8 -> Color(hex.toLong(16).toInt())
                    else -> null
                }
            }.getOrNull()
        }
        val nums = Regex("""[\d.]+""").findAll(s).map { it.value }.toList()
        if (nums.size < 3) return null
        return runCatching {
            val r = nums[0].toFloat().coerceIn(0f, 255f) / 255f
            val g = nums[1].toFloat().coerceIn(0f, 255f) / 255f
            val b = nums[2].toFloat().coerceIn(0f, 255f) / 255f
            Color(r, g, b, 1f)
        }.getOrNull()
    }
}
