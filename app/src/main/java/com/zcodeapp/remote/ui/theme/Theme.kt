package com.zcodeapp.remote.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color

/**
 * 原生壳配色。
 *
 * 两套来源，优先级从高到低：
 * 1. **页面实际配色**（PageThemeStore）。远控页是深是浅由页面自己的主题决定，
 *    壳层必须跟着走，否则用户把页面切成浅色后，状态栏与入口页会跟页面割裂。
 * 2. 系统深浅色。从未同步过页面配色时（首次启动、还没进过远控页）用它兜底。
 *
 * 强调色不是固定品牌色，而是**当前前景色本身**——官方页面就是这么做的：
 * 浅色模式下主按钮黑底白字，深色模式下白底黑字。这样壳层与页面并排时
 * 不会有第二种色相干扰，也天然保证在任意底色上都有足够对比度。
 */
private val DarkColorScheme = darkColorScheme(
    primary = DarkText,
    onPrimary = DarkBackground,
    secondary = DarkTextSecondary,
    onSecondary = DarkBackground,
    tertiary = SignalAmberDark,
    background = DarkBackground,
    onBackground = DarkText,
    surface = DarkSurface,
    onSurface = DarkText,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkTextSecondary,
    outline = DarkOutline,
    outlineVariant = DarkOutline,
    error = SignalRedDark,
    onError = DarkBackground,
)

private val LightColorScheme = lightColorScheme(
    primary = LightText,
    onPrimary = LightSurface,
    secondary = LightTextSecondary,
    onSecondary = LightSurface,
    tertiary = SignalAmber,
    background = LightBackground,
    onBackground = LightText,
    surface = LightSurface,
    onSurface = LightText,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightTextSecondary,
    outline = LightOutline,
    outlineVariant = LightOutline,
    error = SignalRed,
    onError = LightSurface,
)

/**
 * 由页面配色派生 Material 配色。
 *
 * 只继承**底色与文字色**，其余（卡片、分隔线、次要文字）都由这两者派生，
 * 所以页面换成任何一套主题，壳层的层级关系都不会塌。
 */
private fun schemeFromPage(page: PageTheme) = if (page.isDark) {
    darkColorScheme(
        primary = page.foreground,
        onPrimary = page.background,
        secondary = page.muted,
        onSecondary = page.background,
        tertiary = SignalAmberDark,
        background = page.background,
        onBackground = page.foreground,
        surface = page.surface,
        onSurface = page.foreground,
        surfaceVariant = page.surfaceVariant,
        onSurfaceVariant = page.muted,
        outline = page.outline,
        outlineVariant = page.outline,
        error = SignalRedDark,
        onError = page.background,
    )
} else {
    lightColorScheme(
        primary = page.foreground,
        onPrimary = page.background,
        secondary = page.muted,
        onSecondary = page.background,
        tertiary = SignalAmber,
        background = page.background,
        onBackground = page.foreground,
        surface = page.surface,
        onSurface = page.foreground,
        surfaceVariant = page.surfaceVariant,
        onSurfaceVariant = page.muted,
        outline = page.outline,
        outlineVariant = page.outline,
        error = SignalRed,
        onError = page.background,
    )
}

@Composable
fun ZcodemobileTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val page by PageThemeStore.current.collectAsState()
    val colorScheme = page?.let { schemeFromPage(it) }
        ?: if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        // 显式限定：本文件同包下的 Typography 会被 kotlin.text.Typography 抢占解析，
        // 不加全名时编译器解析到错误的类型。
        typography = com.zcodeapp.remote.ui.theme.Typography,
        content = content
    )
}

/** 当前是否为深色背景（决定状态栏图标明暗）。与主题用同一份数据源，避免两处判断打架。 */
@Composable
fun useDarkBackground(): Boolean {
    val page by PageThemeStore.current.collectAsState()
    return page?.isDark ?: isSystemInDarkTheme()
}

/**
 * 语义色在深浅两套底色上的取值。
 *
 * 页面配色是动态的，不能用固定常量判断明暗——这里统一按「当前是否深色」选一档，
 * 保证同一处提示在两套主题下都看得清。
 */
@Composable
fun signalGreen(): Color = if (useDarkBackground()) SignalGreenDark else SignalGreen

@Composable
fun signalRed(): Color = if (useDarkBackground()) SignalRedDark else SignalRed

@Composable
fun signalAmber(): Color = if (useDarkBackground()) SignalAmberDark else SignalAmber
