package com.zcodeapp.remote.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 配色取自官方远控页的实际像素（2026-10-27 对截图采样）：
 *
 * 深色：#161616 页面底 / #2b2b2b 卡片 / #f5f5f5 文字
 * 浅色：#f8f8f8 页面底 / #ffffff 卡片 / #161616 文字
 *
 * 关键发现：官方界面**几乎没有品牌色**。强调动作靠前景色本身——
 * 浅色模式下主按钮是黑底白字，深色模式下是白底黑字；全屏唯一的有彩色
 * 是「在线」绿点（#1b893b）和一处琥珀色图标。
 *
 * 旧配色用的是蓝灰底（#111827）+ 蓝色强调（#2563EB），与页面并排时
 * 一眼就能看出是两个应用拼在一起——这正是「颜色奇怪」的来源。
 * 现在全部退回中性灰阶，强调色 = 前景色。
 */

// ---- 浅色 ----
val LightBackground = Color(0xFFF8F8F8)
val LightSurface = Color(0xFFFFFFFF)
/** 输入框、次级容器：比 surface 略深，用于「可输入/可点击」的区域。 */
val LightSurfaceVariant = Color(0xFFF0F0F0)
val LightText = Color(0xFF161616)
val LightTextSecondary = Color(0xFF6B6B6B)
val LightTextMuted = Color(0xFF9E9E9E)
val LightOutline = Color(0xFFEBEBEB)

// ---- 深色 ----
val DarkBackground = Color(0xFF161616)
val DarkSurface = Color(0xFF2B2B2B)
val DarkSurfaceVariant = Color(0xFF232323)
val DarkText = Color(0xFFF5F5F5)
val DarkTextSecondary = Color(0xFF9E9E9E)
val DarkTextMuted = Color(0xFF6E6E6E)
val DarkOutline = Color(0xFF303030)

// ---- 语义色（两套底色上都要可读） ----
/** 在线/成功：取自官方「在线」状态点。 */
val SignalGreen = Color(0xFF1B893B)
val SignalGreenDark = Color(0xFF4CAF50)
/** 失败/错误。 */
val SignalRed = Color(0xFFD93025)
val SignalRedDark = Color(0xFFF28B82)
/** 待处理/提醒：取自官方输入框旁的琥珀色图标。 */
val SignalAmber = Color(0xFFB45309)
val SignalAmberDark = Color(0xFFE1AB66)
