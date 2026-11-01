package com.zcodeapp.remote.web

import com.zcodeapp.remote.protocol.JsonValue

/**
 * 返回路由：把页面侧 [PageNavScript] 的回包翻译成原生动作。
 *
 * 为什么需要一个独立的判定层，而不是在 WebViewClient 回调里 if/else：
 * 1. 返回有三条入口（系统返回键、左边缘右滑、壳子上的返回按钮），
 *    三者必须走同一份判定，否则同一时刻不同入口给出不同结果——正是「交互不一致」的来源。
 * 2. 「两次返回才退出」这类规则有状态（时间窗、是否已经在最外层），
 *    散落在 Compose 里会被重组清掉。
 * 3. 这些判定必须能被单测覆盖：页面 DOM 拿不到，但回包文本可以构造。
 *
 * 纯 JVM，不引用任何 Android 类，便于在 `app/src/test` 里直接跑。
 */
object BackRouter {

    /** 页面侧回包翻译出的动作。 */
    sealed class Action {
        /** 页面自己消化了：什么都不做。 */
        data class PageHandled(val layer: String) : Action()

        /** 交给 Radix 浮层栈：原生派发受信任的 Escape。 */
        data class DismissPopup(val layer: String) : Action()

        /** 页面已在最外层，退到壳子。 */
        data class ToShell(val layer: String) : Action()

        /** 探测失败/超时：退化成「再按一次退出」，绝不静默无反应。 */
        data class Unresponsive(val layer: String) : Action()
    }

    /** 两次返回的间隔上限：超过就要重新提示一次。 */
    const val EXIT_CONFIRM_WINDOW_MS = 2_000L

    /**
     * 解析页面回包。
     *
     * 回包形如 `{"handled":true,"action":"settings","layer":"settings"}`，
     * 但 evaluateJavascript 会把它再编码成字符串字面量，所以调用方应先解一层。
     * 这里对两种形态都容忍（已解码 / 仍是字面量），并且对缺字段、坏 JSON
     * 一律按 Unresponsive 处理——宁可按一次没反应，也不要把用户误退出。
     */
    fun parse(raw: String?): Action {
        val text = decode(raw)
        if (text.isEmpty()) return Action.Unresponsive("no-answer")
        val obj = runCatching { JsonValue.parse(text) as? JsonValue.Obj }.getOrNull()
            ?: return Action.Unresponsive("bad-json")
        val layer = obj.str("layer") ?: "unknown"
        if (obj.value("handled") == JsonValue.Bool(true)) {
            return Action.PageHandled(layer)
        }
        return when (obj.str("action")) {
            "escape" -> Action.DismissPopup(layer)
            "shell" -> Action.ToShell(layer)
            // 页面没给明确动作（旧版脚本、脚本未注入）：按最外层处理，
            // 但走「再按一次退出」的确认，避免直接把人踢出会话。
            else -> Action.Unresponsive(layer)
        }
    }

    /**
     * 解开 evaluateJavascript 的字符串字面量包装。
     *
     * 与 WebViewSessionController.decodeJsStringResult 同义，但这里是纯 JVM 的
     * 唯一入口：两边各自实现会让「回包格式」在两处漂移。
     */
    fun decode(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text == "null") return ""
        val first = text.first()
        if (first != '"') return text
        return runCatching {
            val v = JsonValue.parse(text)
            (v as? JsonValue.Str)?.value ?: text
        }.getOrDefault(text)
    }

    /**
     * 「再按一次退出」的判定。
     *
     * @param nowMs 当前时刻
     * @param lastPromptAtMs 上一次提示的时刻；0 表示还没提示过
     * @return 是否应当真的退出。false 表示应该改为提示。
     *
     * 单独抽出来是因为它同时被返回键、右滑、壳子返回按钮使用；
     * 三个入口共享同一个时间戳，用户在两个入口之间交替按也不会被连退两次。
     */
    fun shouldExit(nowMs: Long, lastPromptAtMs: Long): Boolean {
        if (lastPromptAtMs <= 0L) return false
        return nowMs - lastPromptAtMs in 0..EXIT_CONFIRM_WINDOW_MS
    }

    /**
     * 这次返回是否值得先做一次页面探测。
     *
     * 页面还没加载完（无桥、无 DOM）时探测只会拿到 no-answer，
     * 那种情况下直接按「最外层」处理更快，也不会让用户等一整个超时。
     */
    fun shouldProbe(pageReady: Boolean): Boolean = pageReady

    /**
     * 回包是否来自「真的页面」。
     *
     * 脚本未注入（document-start 未生效、页面换了 origin）时
     * `window.__zrNav` 不存在，evaluateJavascript 返回 "null"。
     * 那种情况必须走 Unresponsive 分支：提示「页面未就绪」而不是假装已处理。
     */
    fun isScriptMissing(raw: String?): Boolean {
        val text = raw?.trim().orEmpty()
        return text.isEmpty() || text == "null"
    }
}
