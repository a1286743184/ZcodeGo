package com.zcodeapp.remote.capture

import com.zcodeapp.remote.protocol.Direction
import com.zcodeapp.remote.protocol.JsonValue

/**
 * 桥消息校验（v2 5.2）：schema 白名单 + 大小/频率上限；畸形一律静默丢弃并计数。
 * 生产桥只允许：hello / ws（完整 WS 文本帧）/ dom / health / diag / theme 六种。
 */
object BridgeSchema {
    const val MAX_MESSAGE_CHARS = 1_600_000
    private const val MAX_DOC_ID = 24
    private const val DOM_MIN_INTERVAL_MS = 400L

    sealed class BridgeMsg {
        data class Hello(val doc: String) : BridgeMsg()
        data class Ws(val doc: String, val direction: Direction, val text: String) : BridgeMsg()
        data class Dom(val doc: String, val candidate: String, val taskHint: String?, val evidenceId: String?) : BridgeMsg()
        data class Health(
            val doc: String,
            val state: String,
            val reasonCode: String?,
            val dropped: Long?,
            val queuedBytes: Long?,
            val queuedItems: Long?,
        ) : BridgeMsg()
        data class Diag(
            val doc: String,
            val level: String,
            val code: String,
            val text: String,
            val tap: String?,
            val tapAgeMs: Long?,
        ) : BridgeMsg()

        /** 页面当前配色（计算样式），用于原生壳实时跟随页面主题。 */
        data class Theme(
            val doc: String,
            val bg: String,
            val fg: String,
            val cls: String?,
        ) : BridgeMsg()
    }

    private val domLastAt = HashMap<String, Long>()
    private val diagLastAt = HashMap<String, Long>()

    /** 页面侧异常按文档限流：异常循环会把桥打满，这里再做一道原生侧的闸。 */
    private const val DIAG_MIN_INTERVAL_MS = 300L
    private const val DIAG_MAX_TEXT = 400
    var rejected: Long = 0
        private set

    fun parse(raw: String): BridgeMsg? {
        if (raw.length > MAX_MESSAGE_CHARS) {
            rejected++
            return null
        }
        val root = try {
            JsonValue.parse(raw)
        } catch (e: Exception) {
            rejected++
            return null
        }
        if (root !is JsonValue.Obj) {
            rejected++
            return null
        }
        val v = root.num("v") ?: run { rejected++; return null }
        if (v != 2L) {
            rejected++
            return null
        }
        val doc = root.str("doc")?.take(MAX_DOC_ID) ?: run { rejected++; return null }
        return when (root.str("kind")) {
            "hello" -> BridgeMsg.Hello(doc)
            "ws" -> {
                val dir = if (root.str("dir") == "send") Direction.SEND else Direction.RECV
                val text = root.str("text")
                if (text.isNullOrEmpty()) {
                    rejected++
                    null
                } else {
                    BridgeMsg.Ws(doc, dir, text)
                }
            }
            "dom" -> {
                val now = System.currentTimeMillis()
                val last = domLastAt[doc] ?: 0L
                if (now - last < DOM_MIN_INTERVAL_MS) {
                    return null // 频率限制（不计数为拒绝）
                }
                domLastAt[doc] = now
                val candidate = root.str("candidate") ?: run { rejected++; return null }
                BridgeMsg.Dom(
                    doc = doc,
                    candidate = candidate,
                    taskHint = root.str("taskHint"),
                    evidenceId = root.str("evidenceId"),
                )
            }
            "health" -> BridgeMsg.Health(
                doc = doc,
                state = root.str("state") ?: "unknown",
                reasonCode = root.str("reasonCode"),
                dropped = root.num("dropped"),
                queuedBytes = root.num("queuedBytes"),
                queuedItems = root.num("queuedItems"),
            )
            "diag" -> {
                val now = System.currentTimeMillis()
                val last = diagLastAt[doc] ?: 0L
                if (now - last < DIAG_MIN_INTERVAL_MS) {
                    return null // 频率限制（不计数为拒绝）
                }
                diagLastAt[doc] = now
                val text = root.str("text")?.take(DIAG_MAX_TEXT) ?: run { rejected++; return null }
                BridgeMsg.Diag(
                    doc = doc,
                    level = root.str("level") ?: "error",
                    code = root.str("code") ?: "unknown",
                    text = text,
                    tap = root.str("tap")?.take(120),
                    tapAgeMs = root.num("tapAgeMs"),
                )
            }
            // 主题变化不做频率限制：脚本侧已经只在「颜色真的变了」时才发，
            // 再加一道闸会让用户切完主题后壳层晚一拍才跟上。
            "theme" -> {
                val bg = root.str("bg") ?: run { rejected++; return null }
                val fg = root.str("fg") ?: run { rejected++; return null }
                BridgeMsg.Theme(doc = doc, bg = bg, fg = fg, cls = root.str("cls"))
            }
            else -> {
                rejected++
                null
            }
        }
    }
}
