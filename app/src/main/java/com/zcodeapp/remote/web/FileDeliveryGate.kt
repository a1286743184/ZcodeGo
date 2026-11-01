package com.zcodeapp.remote.web

import com.zcodeapp.remote.protocol.JsonValue

/**
 * 判断页面此刻能否接收上传请求。
 *
 * 为什么单独抽出来：这个判定决定「用户选完文件后能不能立刻上传」，
 * 判错了就是用户报的那个 bug——文件挂在输入框上方一直卡着。
 * 抽成纯函数后可以在 JVM 上锁住边界，不必每次都靠真机复现。
 *
 * 就绪条件（两条同时成立）：
 * 1. 桥还在：文档没被重建。`bridge=false` 说明页面已经换了一个 document，
 *    旧回调绑在死掉的文档上，交出去也没用。
 * 2. WS 开着：`ws=open`。页面 transport 只有在 paired 状态才允许 sendPayload，
 *    否则一律返回 unavailable 且不重试——这正是上传被丢弃的直接原因。
 */
object FileDeliveryGate {

    /** 从守卫脚本返回的 JSON 里解析判定结果。 */
    fun isReady(rawJson: String?): Boolean = evaluate(rawJson).ready

    /**
     * 解析快照。返回是否就绪以及用于日志的原因码。
     * 原因码刻意简短且稳定，便于在 diag.log 里 grep。
     */
    fun evaluate(rawJson: String?): Verdict {
        val text = rawJson?.trim().orEmpty()
        if (text.isEmpty() || text == "null") return Verdict(false, "no-snapshot")
        val obj = runCatching { JsonValue.parse(text) as? JsonValue.Obj }.getOrNull()
            ?: return Verdict(false, "bad-json")

        // 守卫脚本缺失：页面还没注入完，或脚本被页面覆盖。等下去没意义，按未就绪处理。
        if (obj.str("err") != null) return Verdict(false, "no-guard")

        val bridge = obj.value("bridge").let { it is JsonValue.Bool && it.value }
        if (!bridge) return Verdict(false, "bridge-gone")

        return when (val ws = obj.str("ws")) {
            "open" -> Verdict(true, "ready")
            "closed" -> Verdict(false, "ws-closed")
            "degraded" -> Verdict(false, "ws-degraded")
            null -> Verdict(false, "no-ws")
            else -> Verdict(false, "ws-$ws")
        }
    }

    data class Verdict(val ready: Boolean, val reason: String)
}
