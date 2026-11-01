package com.zcodeapp.remote.capture

import android.net.Uri
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * 窄桥（v2 5.2）：仅 https://zcode.z.ai 主文档可达；schema 校验后分发。
 * 秘密（token/完整 URL）不跨桥——本桥只转发协议帧与低置信候选。
 *
 * 诊断计数（仅 debug 构建写 logcat）用于 G1 实测：确认桥消息真的从页面到达原生，
 * 以及各 kind 的到达量。计数不含消息正文，只有类别与数量。
 */
class NarrowBridge(
    private val onHello: (doc: String) -> Unit = {},
    private val onWs: (direction: com.zcodeapp.remote.protocol.Direction, text: String) -> Unit,
    private val onHealth: (state: String, reasonCode: String?, detail: String) -> Unit,
    private val onDom: (candidate: String, taskHint: String?, evidenceId: String?) -> Unit,
    private val onDiag: (level: String, code: String, text: String, tap: String?, tapAgeMs: Long?) -> Unit = { _, _, _, _, _ -> },
    private val onTheme: (bg: String, fg: String, cls: String?) -> Unit = { _, _, _ -> },
) : WebViewCompat.WebMessageListener {

    private val knownDocs = HashSet<String>()

    /** G1 诊断：按 kind 累计的到达计数（不含正文）。 */
    val arrivalCounts: MutableMap<String, Long> = java.util.concurrent.ConcurrentHashMap()

    private fun count(kind: String) {
        arrivalCounts.merge(kind, 1L) { a, b -> a + b }
    }

    override fun onPostMessage(
        view: WebView,
        message: WebMessageCompat,
        sourceOrigin: Uri,
        isMainFrame: Boolean,
        replyProxy: JavaScriptReplyProxy,
    ) {
        if (sourceOrigin.toString() != ALLOWED_ORIGIN) return
        if (!isMainFrame) return
        val data = message.data ?: return
        val msg = BridgeSchema.parse(data) ?: return
        when (msg) {
            is BridgeSchema.BridgeMsg.Hello -> {
                count("hello")
                knownDocs.add(msg.doc)
                onHello(msg.doc)
            }
            is BridgeSchema.BridgeMsg.Ws -> {
                if (msg.doc in knownDocs) {
                    count(if (msg.direction == com.zcodeapp.remote.protocol.Direction.SEND) "ws_send" else "ws_recv")
                    onWs(msg.direction, msg.text)
                }
            }
            is BridgeSchema.BridgeMsg.Health -> {
                if (msg.doc in knownDocs) {
                    count("health")
                    val detail = buildString {
                        // reasonCode 由 RemoteSession 统一输出，这里只补桥侧计数，避免同一字段重复。
                        msg.dropped?.let { append("dropped=").append(it) }
                        msg.queuedBytes?.let { if (isNotEmpty()) append(' '); append("queuedBytes=").append(it) }
                        msg.queuedItems?.let { if (isNotEmpty()) append(' '); append("queuedItems=").append(it) }
                    }
                    onHealth(msg.state, msg.reasonCode, detail)
                }
            }
            is BridgeSchema.BridgeMsg.Dom -> {
                if (msg.doc in knownDocs) {
                    count("dom")
                    onDom(msg.candidate, msg.taskHint, msg.evidenceId)
                }
            }
            is BridgeSchema.BridgeMsg.Diag -> {
                if (msg.doc in knownDocs) {
                    count("diag")
                    onDiag(msg.level, msg.code, msg.text, msg.tap, msg.tapAgeMs)
                }
            }
            is BridgeSchema.BridgeMsg.Theme -> {
                if (msg.doc in knownDocs) {
                    count("theme")
                    onTheme(msg.bg, msg.fg, msg.cls)
                }
            }
        }
    }

    companion object {
        const val ALLOWED_ORIGIN = "https://zcode.z.ai"
        const val JS_OBJECT_NAME = "__zrBridge"

        fun attach(webView: WebView, bridge: NarrowBridge): Boolean {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return false
            WebViewCompat.addWebMessageListener(
                webView,
                JS_OBJECT_NAME,
                setOf(ALLOWED_ORIGIN),
                bridge,
            )
            return true
        }
    }
}
