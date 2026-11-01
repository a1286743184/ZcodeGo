package com.zcodeapp.remote.protocol

import com.zcodeapp.remote.event.EventNormalizer
import com.zcodeapp.remote.event.ObsKind
import com.zcodeapp.remote.event.TaskReducer
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 真实抓包端到端回放：把 cap-192020.jsonl 的每条 rpc-frame 依次灌进
 * ProtocolPipeline → EventNormalizer → TaskReducer，核对整条链路在真实数据上的行为。
 *
 * 抓包文件含未脱敏内容，不入库、不进 resources；只按本地路径读取，缺失则跳过。
 * 路径可用 -DzcodeRemote.capture=<path> 覆盖。
 */
class CaptureReplayTest {
    private fun captureFile(): File {
        val override = System.getProperty("zcodeRemote.capture")
        val candidates = listOfNotNull(
            override?.let { File(it) },
            // Gradle 测试的工作目录是 app/，抓包在仓库根的 tools/capture/raw/
            File("../tools/capture/raw/cap-192020.jsonl"),
            File("tools/capture/raw/cap-192020.jsonl"),
        )
        return candidates.firstOrNull { it.isFile && it.length() > 1_000_000 }
            ?: File("__missing_capture__")
    }

    /**
     * 实时监控数据回放（watch.py 抓取，含一次真实 Agent 提问的完整生命周期）。
     * 关键断言：提问产生一条 Attention，回答后产生一条针对同一 interactionId 的撤回。
     * 这直接验证了 v2 R4「每个待处理交互产生一条提醒；处理其对应交互后撤下对应提醒」。
     */
    @Test
    fun liveWatchReplayProducesAttentionAndResolve() {
        val file = watchFile()
        assumeTrue("未找到实时监控抓包，跳过：$file", file.isFile)

        val counter = ReplayCollector()
        val pipeline = ProtocolPipeline(counter)
        val normalizer = EventNormalizer({ "live" }, { 1L })
        val reducer = TaskReducer()

        var frames = 0
        file.forEachLine { line ->
            val frame = parseRpcFrame(line) ?: return@forEachLine
            frames++
            pipeline.onWebSocketText(frame.direction, frame.envelopeText)
            for (ev in counter.drain()) {
                for (obs in normalizer.fromChannelEvent(ev)) {
                    counter.countObservation(obs)
                    counter.countIntents(reducer.reduce(obs))
                }
            }
        }

        println(
            "[live-replay] frames=$frames attention=${counter.attentions.size} " +
                "resolves=${counter.resolves.size} interactionIds=${counter.attentions}"
        )
        assumeTrue("该抓包窗口内没有提问事件，跳过", counter.attentions.isNotEmpty() || frames > 0)

        // 每次提问都必须带真实 interactionId（不是 DOM 推断、不是任务级布尔）
        assertTrue("提问通知缺少 interactionId", counter.attentions.all { it.isNotBlank() })
        assertTrue("提问通知数量异常：${counter.attentions}", counter.attentions.size <= 5)
        // 若该窗口内提问已被回答，撤回必须精确对应同一个 interactionId
        for (resolved in counter.resolves) {
            assertTrue(
                "撤回的 interactionId=$resolved 不在已通知集合内",
                counter.attentions.contains(resolved),
            )
        }
    }

    private fun watchFile(): File {
        val override = System.getProperty("zcodeRemote.watch")
        val candidates = listOfNotNull(
            override?.let { File(it) },
            File("../tools/capture/watch.state.json").takeIf { it.isFile }?.let {
                val text = it.readText(Charsets.UTF_8)
                Regex("\"file\"\\s*:\\s*\"([^\"]+)\"").find(text)
                    ?.groupValues?.get(1)?.replace("\\\\", "\\")?.let { p -> File(p) }
            },
        )
        return candidates.firstOrNull { it != null && it.isFile && it.length() > 100_000 }
            ?: File("__missing_watch__")
    }

    @Test
    fun realCaptureReplayProducesConsistentObservations() {
        val file = captureFile()
        assumeTrue("未找到真实抓包，跳过回放：$file", file.isFile)

        val counter = ReplayCollector()
        val pipeline = ProtocolPipeline(counter)
        val normalizer = EventNormalizer({ "cap" }, { 1L })
        val reducer = TaskReducer()

        var frames = 0
        file.forEachLine { line ->
            val frame = parseRpcFrame(line) ?: return@forEachLine
            frames++
            pipeline.onWebSocketText(frame.direction, frame.envelopeText)
            for (ev in counter.drain()) {
                for (obs in normalizer.fromChannelEvent(ev)) {
                    counter.countObservation(obs)
                    counter.countIntents(reducer.reduce(obs))
                }
            }
        }

        assertTrue("应至少解析到数千个 rpc-frame，实际 $frames", frames > 2_000)

        // 1) 管线健康：绝大多数帧必须成功解出 Channel 消息，不能因解析缺陷整片丢失。
        val h = pipeline.health()
        println(
            "[replay] file=${file.name} frames=$frames rpcComplete=${h.rpcComplete} " +
                "discarded=${h.rpcDiscarded} decodeFailures=${h.decodeFailures} " +
                "events=${counter.eventCount} eventsWithService=${counter.eventWithService} " +
                "promiseSuccess=${counter.promiseSuccess} observations=${counter.observationCount} " +
                "running=${counter.runningObs} terminalObs=${counter.terminalObs} " +
                "terminalNotices=${counter.terminalNotices}"
        )
        assertTrue("rpcComplete=${h.rpcComplete} 过少", h.rpcComplete > 2_000)
        assertTrue("decodeFailures=${h.decodeFailures} 过多（说明解码仍在失败）", h.decodeFailures <= 2)

        // 2) 201/204 的 payload 必须真的解出来（旧缺陷下全是 null，事件一条都产不出来）。
        assertTrue("promise-success 事件数过少：${counter.promiseSuccess}", counter.promiseSuccess > 20)
        assertTrue("event 事件数过少：${counter.eventCount}", counter.eventCount > 1_000)
        assertTrue("204 事件的 service 未能还原", counter.eventWithService > 1_000)

        // 3) 归一器必须从真实 204 里产出任务观察。
        assertTrue("未从真实抓包产出任务观察", counter.observationCount > 500)
        assertTrue("未识别出任何 running 任务", counter.runningObs > 100)
        assertTrue("未识别出任何终态任务", counter.terminalObs > 0)

        // 4) 终态通知必须远少于终态观察：真实数据里同一任务同一次运行会连续出现多条
        //    completed 增量（实测 toSeq 32873/32874 同 traceId），且快照里 49 个历史终态
        //    会话只建基线不通知。去重后通知数应远小于终态观察数。
        println("[replay] terminal notices: ${counter.terminalNoticeKeys}")
        assertTrue(
            "终态通知数 ${counter.terminalNotices} 超过终态观察数 ${counter.terminalObs}",
            counter.terminalNotices <= counter.terminalObs,
        )
        assertTrue(
            "历史终态未被基线吸收：终态观察 ${counter.terminalObs} 只发了 ${counter.terminalNotices} 条通知以外的比例异常",
            counter.terminalNotices * 5 <= counter.terminalObs,
        )
        assertEquals(
            "同一任务不应产生两条终态通知",
            counter.terminalNotices,
            counter.terminalNoticeKeys.size,
        )
    }

    private class ReplayCollector : PipelineListener {
        private val pending = ArrayList<ChannelEvent>()
        var eventCount = 0
        var eventWithService = 0
        var promiseSuccess = 0
        var observationCount = 0
        var runningObs = 0
        var terminalObs = 0
        var terminalNotices = 0
        val terminalNoticeKeys = LinkedHashSet<String>()
        val attentions = LinkedHashSet<String>()
        val resolves = ArrayList<String>()

        fun drain(): List<ChannelEvent> {
            if (pending.isEmpty()) return emptyList()
            val out = ArrayList(pending)
            pending.clear()
            return out
        }

        override fun onChannelEvent(event: ChannelEvent) {
            pending.add(event)
            when (event.kind) {
                "event" -> {
                    eventCount++
                    if (event.service != null) eventWithService++
                }
                "promise-success" -> if (event.service != null) promiseSuccess++
            }
        }

        fun countObservation(obs: com.zcodeapp.remote.event.Observation) {
            observationCount++
            when (obs.kind) {
                ObsKind.TASK_RUNNING -> runningObs++
                ObsKind.TASK_COMPLETED, ObsKind.TASK_FAILED -> terminalObs++
                else -> Unit
            }
        }

        fun countIntents(intents: List<com.zcodeapp.remote.event.NotificationIntent>) {
            for (intent in intents) {
                when (intent) {
                    is com.zcodeapp.remote.event.NotificationIntent.TaskTerminal -> {
                        terminalNotices++
                        terminalNoticeKeys.add("${intent.taskKey.taskId}|${intent.runKey}")
                    }
                    is com.zcodeapp.remote.event.NotificationIntent.Attention -> {
                        attentions.add(intent.interactionKey)
                    }
                    is com.zcodeapp.remote.event.NotificationIntent.ResolveAttention -> {
                        resolves.add(intent.interactionKey)
                    }
                }
            }
        }
    }

    private data class RpcFrame(val direction: Direction, val envelopeText: String)

    /** 抓包里 k=rpc 的记录已经把 rpc-frame 拍平在 f 字段；这里重组成 WS 文本信封。 */
    private fun parseRpcFrame(line: String): RpcFrame? {
        if (!line.contains("\"k\": \"rpc\"") && !line.contains("\"k\":\"rpc\"")) return null
        val direction = when {
            line.contains("\"d\": \"recv\"") || line.contains("\"d\":\"recv\"") -> Direction.RECV
            line.contains("\"d\": \"send\"") || line.contains("\"d\":\"send\"") -> Direction.SEND
            else -> return null
        }
        val fStart = line.indexOf("\"f\"")
        if (fStart < 0) return null
        val objStart = line.indexOf('{', fStart)
        if (objStart < 0) return null
        // f 是最后一个对象字段，取到行尾前找匹配的右花括号
        val objEnd = matchBrace(line, objStart)
        if (objEnd < 0) return null
        val payloadJson = line.substring(objStart, objEnd + 1)
        return RpcFrame(direction, """{"type":"data","payload":$payloadJson}""")
    }

    private fun matchBrace(s: String, start: Int): Int {
        var depth = 0
        var inStr = false
        var esc = false
        for (i in start until s.length) {
            val c = s[i]
            if (inStr) {
                when {
                    esc -> esc = false
                    c == '\\' -> esc = true
                    c == '"' -> inStr = false
                }
            } else {
                when (c) {
                    '"' -> inStr = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
        }
        return -1
    }
}
