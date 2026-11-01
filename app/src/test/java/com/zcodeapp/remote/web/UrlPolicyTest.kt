package com.zcodeapp.remote.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导航白名单回归（v2 6.1 + 2026-10-25 实测路径）。
 *
 * 实测事实（curl 直接请求 zcode.z.ai）：
 * - GET /remote      → 307 location: /cn/remote（Accept-Language en 时为 /en/remote）
 * - GET /cn/remote   → 404
 * - GET /en/remote   → 404
 * - GET /remote/v4   → 200，官方源码 webRemoteControlEntry.ts 只认这个入口
 * 所以 /remote 与 locale 前缀的 404 目标都必须重写到 /remote/v4，否则 WebView 会停在 404
 * 且主文档被判为外部链接交给 Chrome。
 */
class UrlPolicyTest {
    private fun inApp(raw: String, allowAuth: Boolean = false): String {
        val v = UrlPolicy.resolve(raw, allowAuthHosts = allowAuth)
        assertTrue("期望 InApp，实际 $v：$raw", v is UrlPolicy.Verdict.InApp)
        return (v as UrlPolicy.Verdict.InApp).url
    }

    private fun assertExternal(raw: String, allowAuth: Boolean = false) {
        assertEquals(
            "期望 External：$raw",
            UrlPolicy.Verdict.External,
            UrlPolicy.resolve(raw, allowAuthHosts = allowAuth),
        )
    }

    private fun assertInvalid(raw: String) {
        assertEquals("期望 Invalid：$raw", UrlPolicy.Verdict.Invalid, UrlPolicy.resolve(raw))
    }

    @Test
    fun legacyRemotePathRewritesToV4() {
        assertEquals("https://zcode.z.ai/remote/v4", inApp("https://zcode.z.ai/remote"))
        assertEquals("https://zcode.z.ai/remote/v4", inApp("https://zcode.z.ai/remote/"))
    }

    @Test
    fun localeRedirectTargetsRewriteToV4() {
        // 服务器对 /remote 的实际重定向目标，两个都是 404
        assertEquals("https://zcode.z.ai/remote/v4", inApp("https://zcode.z.ai/cn/remote"))
        assertEquals("https://zcode.z.ai/remote/v4", inApp("https://zcode.z.ai/en/remote"))
    }

    @Test
    fun queryStringPreserved() {
        val url = inApp("https://zcode.z.ai/remote?sid=abc123&token=t0k")
        assertTrue("sid 必须保留：$url", url.contains("sid=abc123"))
        assertTrue("token 必须保留：$url", url.contains("token=t0k"))
        assertTrue("路径必须是 v4：$url", url.startsWith("https://zcode.z.ai/remote/v4"))
    }

    @Test
    fun v4AndSubPathsAccepted() {
        assertEquals("https://zcode.z.ai/remote/v4", inApp("https://zcode.z.ai/remote/v4"))
        // 尾斜杠与无斜杠是同一页面，规范化后统一为无斜杠
        assertEquals("https://zcode.z.ai/remote/v4", inApp("https://zcode.z.ai/remote/v4/"))
        assertTrue(inApp("https://zcode.z.ai/remote/v4/latest/assets/x.js").contains("/remote/v4/latest"))
    }

    @Test
    fun zcodeSchemeMapsToV4() {
        assertEquals("https://zcode.z.ai/remote/v4", inApp("zcode://remote"))
        assertEquals("https://zcode.z.ai/remote/v4", inApp("zcode://remote/"))
        assertTrue(inApp("zcode://remote?sid=s1").contains("sid=s1"))
        assertInvalid("zcode://other")
    }

    @Test
    fun otherHostsAreExternal() {
        assertExternal("https://evil.example.com/remote")
        assertExternal("https://zcode.z.ai.evil.com/remote/v4")
        assertExternal("https://zcode.z.ai/settings")
        // 非 https 一律外跳
        assertExternal("http://zcode.z.ai/remote/v4")
    }

    @Test
    fun malformedRejected() {
        assertInvalid("https://zcode.z.ai:8443/remote/v4")
        assertInvalid("https://user:pass@zcode.z.ai/remote/v4")
        assertInvalid("")
        assertInvalid("   ")
        assertInvalid("not a url")
    }

    // --- OAuth 授权页（2026-10-26 实测） ---
    //
    // 页面登录用 openExternal(authorizeUrl)，在浏览器环境是 window.open(url,'_blank')。
    // WebView 里这会把主文档导航到授权域；若判成 External 外跳系统浏览器，
    // 授权码落在浏览器、App 永远收不到 ?code=，登录必然失败。

    @Test
    fun zaiOAuthAuthorizeStaysInApp() {
        val url = "https://chat.z.ai/api/oauth/authorize?redirect_uri=" +
            "https%3A%2F%2Fzcode.z.ai%2Fremote%2Fv4&response_type=code" +
            "&client_id=client_P8X5CMWmlaRO9gyO-KSqtg&state=abc"
        assertEquals(url, inApp(url))
    }

    @Test
    fun zaiAuthLandingStaysInApp() {
        // authorize 会 302 到 /auth，登录表单在这一页
        val url = "https://chat.z.ai/auth?response_type=code&client_id=client_x&state=abc"
        assertEquals(url, inApp(url))
    }

    @Test
    fun bigmodelLoginStaysInApp() {
        // 线上 3.16.3 bundle 的 VITE_BIGMODEL_OAUTH_ORIGIN 是 https://bigmodel.cn，
        // 授权 URL 形如 /login?redirect=...&appId=zcode&state=...
        val url = "https://bigmodel.cn/login?redirect=x&appId=zcode&state=abc"
        assertEquals(url, inApp(url))
    }

    @Test
    fun bigmodelSubdomainNotTrusted() {
        // 旧实现按 open.bigmodel.cn 放行，线上实际是 bigmodel.cn；子域仍不允许
        assertExternal("https://open.bigmodel.cn/login?redirect=x")
    }

    @Test
    fun oauthHostsAreNotBlanketTrusted() {
        // 只放行授权路径，同域的其它页面仍按外部处理
        assertExternal("https://chat.z.ai/chat/abc")
        assertExternal("https://chat.z.ai/")
        assertExternal("https://bigmodel.cn/console")
        // 子域通配不允许
        assertExternal("https://evil.chat.z.ai/api/oauth/authorize")
    }

    @Test
    fun oauthCallbackReturnsToRemotePage() {
        // 授权完成后回调本身必须留在 App 内，否则拿不到 code
        val cb = "https://zcode.z.ai/remote/v4?code=xyz&state=abc"
        val out = inApp(cb)
        assertTrue("回调必须留在远控页：$out", out.startsWith("https://zcode.z.ai/remote/v4"))
        assertTrue("code 必须保留：$out", out.contains("code=xyz"))
    }

    @Test
    fun oauthCallbackKeepsPairingParams() {
        // 3.16.3 的 redirect_uri 就是远控页本身，回调 URL 上会同时带
        // app_version/sid/mid/name 与 code/state。这些参数必须原样保留：
        // 页面靠 _er(url) 判定「这是回调」并要求 state + code 同时存在。
        val cb = "https://zcode.z.ai/remote/v4?app_version=3.16.3&sid=d_abc&mid=m_1&name=X&code=xyz&state=abc"
        val out = inApp(cb)
        assertTrue("app_version 必须保留：$out", out.contains("app_version=3.16.3"))
        assertTrue("sid 必须保留：$out", out.contains("sid=d_abc"))
        assertTrue("mid 必须保留：$out", out.contains("mid=m_1"))
        assertTrue("code 必须保留：$out", out.contains("code=xyz"))
        assertTrue("state 必须保留：$out", out.contains("state=abc"))
    }

    @Test
    fun authFlowKeepsAuthHostPathsInApp() {
        // 授权流程进行中：授权域的其它路径也不能外跳。
        // 实测风险：授权页登录成功后会在自己的域内跳几次才 302 回远控页，
        // 中途只要有一次交给系统 Chrome，回调就再也回不到 App。
        val product = "https://bigmodel.cn/product"
        assertExternal(product)
        assertEquals(product, inApp(product, allowAuth = true))

        val zaiChat = "https://chat.z.ai/chat/abc"
        assertExternal(zaiChat)
        assertEquals(zaiChat, inApp(zaiChat, allowAuth = true))

        // 流程结束后恢复严格白名单
        assertExternal(product)
    }

    @Test
    fun authFlowDoesNotTrustOtherHosts() {
        // 授权流程中也不放行无关域，避免把任意站点都圈进 App
        assertExternal("https://evil.example.com/x", allowAuth = true)
    }

    @Test
    fun authHostUrlRecognition() {
        assertTrue(UrlPolicy.isAuthHostUrl("https://bigmodel.cn/anything"))
        assertTrue(UrlPolicy.isAuthHostUrl("https://chat.z.ai/chat/1"))
        assertTrue(!UrlPolicy.isAuthHostUrl("https://zcode.z.ai/remote/v4"))
        assertTrue(!UrlPolicy.isAuthHostUrl("https://evil.chat.z.ai/x"))
    }

    @Test
    fun remotePageRecognition() {
        assertTrue(UrlPolicy.isRemotePageUrl("https://zcode.z.ai/remote/v4?code=1"))
        assertTrue(UrlPolicy.isRemotePageUrl("https://zcode.z.ai/remote/v4/"))
        assertTrue(!UrlPolicy.isRemotePageUrl("https://bigmodel.cn/login"))
        assertTrue(!UrlPolicy.isRemotePageUrl("https://zcode.z.ai/other"))
    }

    @Test
    fun connectionIdStable() {
        val a = UrlPolicy.connectionId("https://zcode.z.ai/remote/v4?sid=x")
        val b = UrlPolicy.connectionId("https://zcode.z.ai/remote/v4?sid=x")
        val c = UrlPolicy.connectionId("https://zcode.z.ai/remote/v4?sid=y")
        assertEquals(a, b)
        assertTrue("不同 sid 应有不同身份", a != c)
    }
}
