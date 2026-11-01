package com.zcodeapp.remote.web

/**
 * 读取页面实际配色的脚本。
 *
 * 取的是 `document.body` / `html` 的**计算样式**，不是 localStorage 里的偏好值：
 * 偏好可能是 `system`，页面自己解析成具体某一套；只有计算样式是最终事实。
 *
 * 另外把 `<html>` 上的主题 class 一起带出来，用于日志区分「页面换了主题」
 * 与「我们读错了颜色」。
 */
object PageThemeProbeScript {
    fun build(): String = """
(function () {
  try {
    var html = document.documentElement;
    var body = document.body;
    var bg = body ? getComputedStyle(body).backgroundColor : '';
    // body 透明时退到 html：部分主题把底色挂在根节点上。
    if (!bg || bg === 'transparent' || bg === 'rgba(0, 0, 0, 0)') {
      bg = getComputedStyle(html).backgroundColor;
    }
    var fg = body ? getComputedStyle(body).color : getComputedStyle(html).color;
    var cls = (html.className || '').split(/\s+/).filter(function (c) {
      return c === 'dark' || c.indexOf('theme-') === 0;
    }).join(',');
    return JSON.stringify({ bg: bg, fg: fg, cls: cls });
  } catch (e) {
    return JSON.stringify({ err: String(e) });
  }
})()
""".trimIndent()
}
