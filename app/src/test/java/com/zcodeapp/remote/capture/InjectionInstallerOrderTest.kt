package com.zcodeapp.remote.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 注入脚本的装配顺序。
 *
 * 这些顺序不是风格问题，每一条都有依赖：
 * - bootstrap.js 建立 `window.__zrWsState`，nav.js 与 file-pick-guard.js 都要读它
 * - diag.js 会包 console.error，它必须排在 nav/theme 之后，否则探针的异常被吞
 * - file-pick-guard.js 必须最后：它要在页面自己的生命周期监听器之前注册捕获阶段拦截
 *
 * 顺序写错不会抛异常，只会静默失效（例如「按返回没反应」「主题不跟随」），
 * 所以这里用断言把顺序钉住，而不是靠注释提醒。
 */
class InjectionInstallerOrderTest {

    private fun order(): List<String> {
        // ORDER 是 private：用反射读，避免为了测试把它暴露成公开 API。
        val field = InjectionInstaller::class.java.getDeclaredField("ORDER")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(InjectionInstaller) as List<String>
    }

    @Test
    fun navScriptIsInjected() {
        assertTrue("nav.js 必须随 document-start 注入", order().contains("capture/nav.js"))
    }

    @Test
    fun bootstrapPrecedesNav() {
        val list = order()
        val bootstrap = list.indexOf("capture/bootstrap.js")
        val nav = list.indexOf("capture/nav.js")
        assertTrue("bootstrap 必须先于 nav（nav 读 __zrWsState）", bootstrap >= 0 && bootstrap < nav)
    }

    @Test
    fun navPrecedesDiag() {
        val list = order()
        val nav = list.indexOf("capture/nav.js")
        val diag = list.indexOf("capture/diag.js")
        assertTrue("nav 必须先于 diag（diag 会包 console.error）", nav >= 0 && diag >= 0 && nav < diag)
    }

    @Test
    fun filePickGuardStaysLast() {
        val list = order()
        assertEquals(
            "file-pick-guard 必须最后：它要在页面自己的生命周期监听器之前注册",
            "capture/file-pick-guard.js",
            list.last(),
        )
    }

    @Test
    fun everyListedAssetExists() {
        // 资产缺失时注入器会抛 IOException，整个 document-start 脚本失效——
        // 表现为「所有注入能力一起消失」，而日志里只会看到一条 IOException。
        //
        // 这里读源目录而不是 classloader：assets 是 APK 资源，不进 JVM 测试的 classpath。
        // 查源目录反而更早——在打包之前就能发现漏提交的资产。
        val assetsDir = java.io.File("src/main/assets")
        assertTrue("找不到 assets 目录: ${assetsDir.absolutePath}", assetsDir.isDirectory)
        for (name in order()) {
            val f = java.io.File(assetsDir, name)
            assertTrue("资产缺失: $name", f.isFile)
        }
    }
}
