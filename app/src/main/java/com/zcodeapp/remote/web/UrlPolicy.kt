package com.zcodeapp.remote.web

import java.net.URI
import java.net.URISyntaxException

/**
 * 导航白名单（v2 6.1）：主功能只接受 https://zcode.z.ai 下的远控页；zcode:// 深链映射到同一 origin；
 * 拒绝 userinfo/非默认端口/编码逃逸。
 *
 * 【2026-10-26 修正】OAuth 授权页必须留在 App 内，并且在**同一个主文档**里导航。
 * 页面登录走 `openExternal(authorizeUrl)`，在浏览器环境它退化成
 * `window.open(url, '_blank')`。旧实现把它判成 External 外跳系统浏览器，
 * 授权码落在浏览器里，App 的 WebView 永远收不到 `?code=`。
 * 之后一版改成另开子 WebView 承载，但那个子 WebView 从未挂到任何 ViewGroup
 * （CDP 实测 attached=false / never_attached=true），用户根本看不到授权页。
 *
 * 实测授权域与形态（2026-10-26，线上 3.16.3 bundle）：
 * - Z.ai：`https://chat.z.ai/api/oauth/authorize?...` → 307 到 `https://chat.z.ai/auth?...`
 * - BigModel：`https://bigmodel.cn/login?redirect=...&appId=zcode&state=...`
 *   （`VITE_BIGMODEL_OAUTH_ORIGIN` 就是 `https://bigmodel.cn`）
 * 回调不是 `/web-remote/callback`：3.16.3 的 `redirect_uri` 就是当前远控页 URL 本身，
 * 回调形如 `https://zcode.z.ai/remote/v4?...&code=...&state=...`，本来就在主白名单内。
 * 因此只放行这两个精确 host 的授权路径，不做子域通配。
 *
 * 【2026-10-25 实测修正】页面入口是 `/remote/v4`，不是 `/remote`：
 * - `GET /remote` → 307 `location: /cn/remote`（Accept-Language en 时为 /en/remote），两者均为 404；
 * - `GET /remote/v4` → 200，且官方源码 webRemoteControlEntry.ts 只把 `/remote/v4`、`/remote/v4/`
 *   视为远控入口。
 * 旧写法把 `/remote` 当入口，会让 WebView 跟随重定向停在 404 页面，且该 404 目标不在白名单内，
 * 主文档被判为外部链接后交给 Chrome。因此这里把历史形态 `/remote`、`/remote/` 与服务器实际给出的
 * locale 前缀 404 目标统一重写到 `/remote/v4`，查询串原样保留（sid/token 都在查询里）。
 *
 * 纯 JVM 实现（不依赖 android.net.Uri），便于单元测试覆盖这些真实路径行为。
 */
object UrlPolicy {
    private const val HOST = "zcode.z.ai"
    private const val PAGE_PATH = "/remote/v4"

    /** OAuth 授权域（精确 host，不做子域通配）。 */
    private const val ZAI_OAUTH_HOST = "chat.z.ai"
    private const val BIGMODEL_OAUTH_HOST = "bigmodel.cn"

    sealed class Verdict {
        data class InApp(val url: String) : Verdict()
        object External : Verdict()
        object Invalid : Verdict()
    }

    /**
     * 解析导航目标。
     *
     * @param allowAuthHosts 是否处于 OAuth 授权流程中。为 true 时，授权域的**所有路径**
     *   都留在 App 内——授权页登录成功前后会在自己的域内多次跳转（例如登录回调落到
     *   `bigmodel.cn/login?code=...` 再由它 302 回远控页）。只放行 `/login` 一个路径时，
     *   中间那几次站内跳转会被判成外部链接、交给系统 Chrome，回调就再也回不到 App。
     */
    fun resolve(raw: String?, allowAuthHosts: Boolean = false): Verdict {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return Verdict.Invalid
        val candidate = extractUrl(text) ?: return Verdict.Invalid
        return when {
            candidate.startsWith("zcode://", ignoreCase = true) -> fromZcodeScheme(candidate)
            candidate.startsWith("https://", ignoreCase = true) -> fromHttps(candidate, allowAuthHosts)
            else -> Verdict.External
        }
    }

    private fun fromZcodeScheme(url: String): Verdict {
        val uri = parse(url) ?: return Verdict.Invalid
        if (!uri.host.equals("remote", ignoreCase = true)) return Verdict.Invalid
        return Verdict.InApp("https://$HOST$PAGE_PATH" + queryOf(uri))
    }

    private fun fromHttps(url: String, allowAuthHosts: Boolean): Verdict {
        val uri = parse(url) ?: return Verdict.Invalid
        if (!uri.scheme.equals("https", ignoreCase = true)) return Verdict.External
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return Verdict.External
        if (uri.port != -1) return Verdict.Invalid
        if (!uri.userInfo.isNullOrEmpty()) return Verdict.Invalid

        // OAuth 授权页：留在 App 内，否则授权码回不到 WebView，登录必然失败。
        if (isOAuthAuthorize(host, uri.rawPath ?: "/")) return Verdict.InApp(url)
        // 授权流程进行中：授权域的其它路径也不能外跳，否则中途断链。
        if (allowAuthHosts && isAuthHost(host)) return Verdict.InApp(url)

        if (host != HOST) return Verdict.External
        val path = (uri.rawPath ?: "/").trimEnd('/').ifEmpty { "/" }
        val target = when {
            path == "/remote" -> PAGE_PATH
            // 服务器对 /remote 的 locale 重定向目标本身就是 404，直接拉到真实入口。
            path == "/cn/remote" || path == "/en/remote" -> PAGE_PATH
            path == PAGE_PATH || path.startsWith("$PAGE_PATH/") -> path
            else -> return Verdict.External
        }
        return Verdict.InApp("https://$HOST$target" + queryOf(uri))
    }

    /** 授权域（精确 host，不做子域通配）。 */
    private fun isAuthHost(host: String): Boolean =
        host == ZAI_OAUTH_HOST || host == BIGMODEL_OAUTH_HOST

    /**
     * 是否 OAuth 授权页。按线上 3.16.3 bundle 的实测形态判定：
     * - Z.ai：`https://chat.z.ai/api/oauth/authorize?...` → 307 到 `https://chat.z.ai/auth?...`
     * - BigModel：`https://bigmodel.cn/login?redirect=...&appId=zcode&state=...`
     *   （bundle 里 `VITE_BIGMODEL_OAUTH_ORIGIN` 就是 `https://bigmodel.cn`，不是 open. 子域）
     */
    private fun isOAuthAuthorize(host: String, path: String): Boolean {
        val p = path.trimEnd('/').ifEmpty { "/" }
        return when (host) {
            ZAI_OAUTH_HOST -> p == "/api/oauth/authorize" || p == "/auth"
            BIGMODEL_OAUTH_HOST -> p == "/login"
            else -> false
        }
    }

    /**
     * 对外暴露的判定：这个 URL 是不是 OAuth 授权页。
     * 现在授权页与回调都留在主文档导航，控制器用它判断「授权流程是否开始」。
     */
    fun isOAuthAuthorizeUrl(raw: String?): Boolean {
        val uri = parse(raw?.trim().orEmpty()) ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return false
        return isOAuthAuthorize(host, uri.rawPath ?: "/")
    }

    /** 这个 URL 是否位于授权域（用于判断授权流程是否仍在进行）。 */
    fun isAuthHostUrl(raw: String?): Boolean {
        val uri = parse(raw?.trim().orEmpty()) ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return false
        return isAuthHost(host)
    }

    /** 这个 URL 是否已回到远控页（授权流程结束的标志）。 */
    fun isRemotePageUrl(raw: String?): Boolean {
        val uri = parse(raw?.trim().orEmpty()) ?: return false
        if (!uri.host.equals(HOST, ignoreCase = true)) return false
        val path = (uri.rawPath ?: "/").trimEnd('/').ifEmpty { "/" }
        return path == PAGE_PATH || path.startsWith("$PAGE_PATH/")
    }

    private fun parse(url: String): URI? = try {
        URI(url)
    } catch (e: URISyntaxException) {
        null
    }

    private fun queryOf(uri: URI): String = uri.rawQuery?.let { "?$it" } ?: ""

    /** 连接身份：规范化 URL 的 FNV-1a 稳定哈希，跨重启保持通知去重有效。 */
    fun connectionId(url: String): String {
        var h = 0xcbf29ce4L
        for (c in url) h = (h * 0x100000001b3L) xor c.code.toLong()
        return java.lang.Long.toHexString(h)
    }

    private fun extractUrl(text: String): String? {
        val m = Regex("""https?://\S+|zcode://\S+""").find(text) ?: return null
        return m.value
    }
}
