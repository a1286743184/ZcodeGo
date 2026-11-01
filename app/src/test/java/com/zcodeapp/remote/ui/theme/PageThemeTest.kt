package com.zcodeapp.remote.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 页面配色解析与派生。
 *
 * 这些值直接决定壳层与页面是否「看起来是同一个应用」，解析错了就是整屏颜色错位，
 * 但运行时看不出来是哪一步错，所以在纯 JVM 上锁住。
 */
class PageThemeTest {

    @Test
    fun parsesRgbFromComputedStyle() {
        val c = PageThemeStore.parseColor("rgb(22, 22, 22)")
        assertNotNull(c)
        assertEquals(22f / 255f, c!!.red, 0.01f)
        assertEquals(22f / 255f, c.blue, 0.01f)
    }

    @Test
    fun parsesRgbaWithSpaces() {
        val c = PageThemeStore.parseColor("rgba(248, 250, 252, 1)")
        assertNotNull(c)
        assertEquals(248f / 255f, c!!.red, 0.01f)
    }

    @Test
    fun parsesHex() {
        val c = PageThemeStore.parseColor("#161616")
        assertNotNull(c)
        assertEquals(22f / 255f, c!!.red, 0.01f)
    }

    @Test
    fun rejectsUnusableInput() {
        assertNull(PageThemeStore.parseColor(null))
        assertNull(PageThemeStore.parseColor(""))
        assertNull(PageThemeStore.parseColor("transparent"))
        assertNull(PageThemeStore.parseColor("rgb(1, 2)"))
    }

    @Test
    fun darknessFollowsBackgroundNotClass() {
        val dark = PageTheme(
            background = PageThemeStore.parseColor("rgb(22,22,22)")!!,
            foreground = PageThemeStore.parseColor("rgb(250,250,250)")!!,
        )
        assertTrue("深底应判定为深色", dark.isDark)

        val light = PageTheme(
            background = PageThemeStore.parseColor("rgb(248,250,252)")!!,
            foreground = PageThemeStore.parseColor("rgb(17,24,39)")!!,
        )
        assertFalse("浅底应判定为浅色", light.isDark)
    }

    @Test
    fun surfaceAndOutlineStayCloseToBackground() {
        val light = PageTheme(
            background = PageThemeStore.parseColor("rgb(248,248,248)")!!,
            foreground = PageThemeStore.parseColor("rgb(22,22,22)")!!,
        )
        // 浅色主题的卡片是**白**的（官方 #f8f8f8 底 + #ffffff 卡片），
        // 所以 surface 应该比底色更亮，且只亮一点点。
        assertTrue("浅色 surface 应比底色更亮", light.surface.red > light.background.red)
        assertTrue("surface 与底色差距应很小", light.surface.red - light.background.red < 0.05f)
        assertTrue("outline 应比底色更明显", light.outline.red < light.background.red)
        // 次要文字必须比主文字淡，否则层级消失。
        assertTrue("muted 应比主文字更接近背景", light.muted.red > light.foreground.red)
    }

    @Test
    fun darkSurfaceLiftsAboveBackground() {
        val dark = PageTheme(
            background = PageThemeStore.parseColor("rgb(22,22,22)")!!,
            foreground = PageThemeStore.parseColor("rgb(245,245,245)")!!,
        )
        // 深色主题卡片比底色亮（官方 #161616 → #2b2b2b），差距很小。
        assertTrue("深色 surface 应比底色亮", dark.surface.red > dark.background.red)
        assertTrue("surface 与底色差距应很小", dark.surface.red - dark.background.red < 0.12f)
        assertTrue("surfaceVariant 应比 surface 暗", dark.surfaceVariant.red < dark.surface.red)
    }
}
