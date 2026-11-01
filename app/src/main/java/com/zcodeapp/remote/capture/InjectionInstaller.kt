package com.zcodeapp.remote.capture

import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.zcodeapp.remote.web.ShieldPolicy

/**
 * 注入器（v2 D2）：capture 资产按固定顺序拼接，document-start 注入，
 * 只对 https://zcode.z.ai 生效。
 *
 * 【2026-10-30】注入前先写一个 `window.__zrShieldConfig`。
 *
 * 为什么必须在 document-start 就把屏蔽配置带进页面：
 * 可见性屏蔽过去是在 onPause 里用 evaluateJavascript 装上去的，但那一刻
 * Chromium 早已把 `visibilitychange(hidden)` 派发给页面了——脚本永远晚一拍。
 * 这是竞态，表现就是「有时候还行、有时候刷好几次」。
 *
 * 现在把配置作为脚本第一行带进去，页面自己的脚本跑起来之前
 * `__zrShieldConfig` 就已经就位，file-pick-guard.js 据此在**同一拍**完成武装，
 * 不存在窗口期。
 *
 * 配置内容由 [ShieldPolicy] 生成（拦哪些事件、什么 WS 状态算健康），
 * 是 Kotlin 与 JS 的唯一真源，避免两边各写一份常量后慢慢漂移。
 */
object InjectionInstaller {
    private val ORDER = listOf(
        "capture/transport.js",
        "capture/rpc-reassembly.js",
        "capture/bootstrap.js",
        "capture/dom-candidate.js",
        // 层级探针与分层返回：原生所有「该退哪一层」的判断都走它，
        // 必须早于 diag 安装（diag 会包 console.error，探针不该受它影响）。
        "capture/nav.js",
        // 主题观察：让原生壳实时跟随页面配色。必须早于 diag 安装——
        // diag 会包 console.error，主题上报不该受它影响。
        "capture/theme-watch.js",
        // diag 放最后：它依赖 __zrTransport，且要能看到前面脚本的异常。
        "capture/diag.js",
        // 可见性屏蔽必须最后装：它要读 window.__zrWsState（由 bootstrap.js 建立），
        // 且必须在页面自己的生命周期监听器之前注册好捕获阶段拦截。
        "capture/file-pick-guard.js",
    )

    /**
     * @param holdDefault 文档初始是否武装「常驻可见性屏蔽」。由用户的后台保活
     *   设置决定；false 时行为与旧版一致（只在显式 begin 时屏蔽）。
     */
    fun install(webView: WebView, holdDefault: Boolean = false): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return false
        val script = buildString {
            // 必须排在最前：file-pick-guard.js 在初始化时就读取它。
            append("window.__zrShieldConfig = ")
            append(ShieldPolicy.toJsConfig(holdDefault))
            append(";\n;\n")
            for (name in ORDER) {
                webView.context.assets.open(name).use { append(it.readBytes().toString(Charsets.UTF_8)); append("\n;\n") }
            }
        }
        WebViewCompat.addDocumentStartJavaScript(webView, script, setOf(NarrowBridge.ALLOWED_ORIGIN))
        return true
    }
}
