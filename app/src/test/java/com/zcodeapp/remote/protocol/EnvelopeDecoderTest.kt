package com.zcodeapp.remote.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvelopeDecoderTest {
    @Test
    fun authAck() {
        val f = EnvelopeDecoder.decode("""{"type":"auth_ack","server_ts":1,"device_sid":"d_1","terminal_sid":"t_1","pair_status":"matched"}""")
        f as RelayFrame.AuthAck
        assertEquals("matched", f.pairStatus)
        assertEquals("d_1", f.deviceSid)
    }

    @Test
    fun dataRpcFrame() {
        val f = EnvelopeDecoder.decode("""{"type":"data","payload":{"zcode_type":"rpc-frame","dataBase64":"BAEGyAEA"}}""")
        f as RelayFrame.Data
        assertEquals("rpc-frame", f.zcodeType)
        assertEquals("BAEGyAEA", f.payload?.str("dataBase64"))
    }

    @Test
    fun malformedNotThrowing() {
        assertTrue(EnvelopeDecoder.decode("not json") is RelayFrame.Malformed)
        assertTrue(EnvelopeDecoder.decode("[1,2]") is RelayFrame.Malformed)
        assertTrue(EnvelopeDecoder.decode("""{"type":"weird"}""") is RelayFrame.Other)
    }
}

class ProtocolPipelineTest {
    private class Collector : PipelineListener {
        val channel = ArrayList<ChannelEvent>()
        val envelopes = ArrayList<String>()
        override fun onChannelEvent(event: ChannelEvent) {
            channel.add(event)
        }

        override fun onEnvelope(event: EnvelopeEvent) {
            event.zcodeType?.let { envelopes.add(it) }
        }
    }

    @Test
    fun initializeFrameEndToEnd() {
        val c = Collector()
        val p = ProtocolPipeline(c)
        p.onWebSocketText(
            Direction.RECV,
            """{"server_ts":1,"type":"data","payload":{"zcode_type":"rpc-frame","bridgeSessionId":"b1","bridgeGeneration":1,"messageSeq":1,"fragmentIndex":0,"fragmentCount":1,"messageBytes":6,"seq":1,"checksum":{"algorithm":"crc32","value":"b4ff6360"},"dataBase64":"BAEGyAEA"}}""",
        )
        assertEquals(1, c.channel.size)
        assertEquals("initialize", c.channel[0].kind)
        assertEquals(200L, c.channel[0].typeCode)
    }

    /**
     * 用真实抓包脱敏样本里成对的两帧验证事件解析：listen(callId=14) → 204(listenerId=14)。
     * 关键点（旧测试写错的两处）：
     * 1) 204 的 id 就是 listen 的 callId，不需要 201 分配 listenerId；
     * 2) 204 的 arg 在 values[1]，是 JsonV，不是 head 的第 3 项。
     * CRC 一律用真实值，否则 RpcAssembler 会正确丢弃该帧。
     */
    @Test
    fun listenThenEventFireResolvesService() {
        val c = Collector()
        val p = ProtocolPipeline(c)

        // 真实 listen：window-controller/onDynamicControllerFrame, callId=14
        p.onWebSocketText(Direction.SEND, frameText("b1", 15, LISTEN_102))
        // 真实 204 事件：listenerId=14，arg 是 controller/tasks-index 的 deltas JSON
        p.onWebSocketText(Direction.RECV, frameText("b1", 52, TASKS_INDEX_DELTA_204))

        val events = c.channel
        assertEquals(2, events.size)
        assertEquals("listen", events[0].kind)
        assertEquals("window-controller", events[0].service)
        assertEquals("onDynamicControllerFrame", events[0].method)

        val fireEvent = events.last()
        assertEquals("event", fireEvent.kind)
        assertEquals("window-controller", fireEvent.service)
        assertEquals("onDynamicControllerFrame", fireEvent.method)
        val arg = fireEvent.arg as IpcValue.JsonV
        assertEquals("controller/tasks-index", (arg.parsed as JsonValue.Obj).str("topic"))
    }

    /** 真实 201 回执：head=[201, 40]，结果在 values[1]，应还原为 zcode-task/getTaskMeta。 */
    @Test
    fun promiseSuccessCarriesResultFromTailValue() {
        val c = Collector()
        val p = ProtocolPipeline(c)
        p.onWebSocketText(Direction.SEND, frameText("b1", 41, GET_TASK_META_CALL_100))
        p.onWebSocketText(Direction.RECV, frameText("b1", 26, GET_TASK_META_RES_201))

        val success = c.channel.last()
        assertEquals("promise-success", success.kind)
        assertEquals("zcode-task", success.service)
        assertEquals("getTaskMeta", success.method)
        val result = success.arg as IpcValue.JsonV
        assertEquals(
            "sess_a1b2c3d4-0000-4000-8000-0000000000e1",
            (result.parsed as JsonValue.Obj).str("taskId"),
        )
    }

    private fun frameText(session: String, seq: Long, dataBase64: String): String {
        val bytes = java.util.Base64.getDecoder().decode(dataBase64)
        val crc = String.format("%08x", java.util.zip.CRC32().apply { update(bytes) }.value)
        return """{"type":"data","payload":{"zcode_type":"rpc-frame","bridgeSessionId":"$session","bridgeGeneration":1,"messageSeq":$seq,"fragmentIndex":0,"fragmentCount":1,"messageBytes":${bytes.size},"seq":$seq,"checksum":{"algorithm":"crc32","value":"$crc"},"dataBase64":"$dataBase64"}}"""
    }

    private companion object {
        /** cap-192020.jsonl messageSeq=15, crc a0ef5dbf, window-controller/onDynamicControllerFrame, callId=14 */
        const val LISTEN_102 = "BAQGZgYOARF3aW5kb3ctY29udHJvbGxlcgEYb25EeW5hbWljQ29udHJvbGxlckZyYW1lAA=="

        /** cap-192020.jsonl messageSeq=52, crc fc349a31, 1017 字节 */
        const val TASKS_INDEX_DELTA_204 =
            "BAIGzAEGDgXvB3sidG9waWMiOiJjb250cm9sbGVyL3Rhc2tzLWluZGV4Iiwic3Vic2NyaXB0aW9uSWQiOiJlNzEyOGY0YS1jNjhkLTQ4MzYtOWU3MC1iYzI4MTRlYjU0MDYiLCJsb2dFcG9jaCI6ImFiOTljYzRhLWIxMjctNDc0Zi1iMWM3LTlhMWViZDBkNGIzZCIsImZyb21TZXEiOjMyMzgwLCJ0b1NlcSI6MzIzODEsInNlbnRBdCI6MTc5MjkyNjE3NDAzMywicGF5bG9hZCI6eyJraW5kIjoiZGVsdGFzIiwiZGVsdGFzIjpbeyJvcCI6InRhc2sudXBzZXJ0ZWQiLCJ0YXNrIjp7ImFkZHJlc3MiOnsid29ya3NwYWNlUGF0aCI6IkQ6XFxXb3JrXFxTYW1wbGVEb2NzIiwidGFza0lkIjoic2Vzc2lvbl8xMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMSJ9LCJtZXRhIjp7InRhc2tJZCI6InNlc3Npb25fMTAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDEiLCJ0cmFjZUlkIjoiMDAwMDAwMDAtMDAwMC00MDAwLTgwMDAtMDAwMDAwMDAwMDdkIiwidGl0bGUiOiJTYW1wbGUgVGFzayAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICIsIndvcmtzcGFjZVBhdGgiOiJEOlxcV29ya1xcU2FtcGxlRG9jcyIsImNyZWF0ZWRBdCI6MTc5MjkyNTMyNzY4NywidXBkYXRlZEF0IjoxNzkyOTI2MTczODc4LCJtb2RlIjoiZGVmYXVsdCIsInByb3ZpZGVyIjoiZ2xtIiwidGl0bGVPdmVycmlkZGVuIjpmYWxzZSwic3RhdHVzIjoicnVubmluZyJ9LCJtZW1iZXJzaGlwIjp7InBpbm5lZCI6ZmFsc2UsImFyY2hpdmVkIjpmYWxzZSwiYWN0aXZlIjp0cnVlfSwic291cmNlQXZhaWxhYmlsaXR5Ijoib25saW5lIiwibGl2ZVN0YXR1cyI6InJ1bm5pbmciLCJhY3Rpdml0eSI6eyJwaGFzZSI6InJ1bm5pbmciLCJsYXN0QWN0aXZpdHlBdCI6MTc5MjkyNjE3Mzg3OCwiaGFzQmFja2dyb3VuZFdvcmsiOmZhbHNlfX19XX19"

        /** cap-192020.jsonl messageSeq=41, crc 972e63ff, 139 字节 */
        const val GET_TASK_META_CALL_100 =
            "BAQGZAYoAQp6Y29kZS10YXNrAQtnZXRUYXNrTWV0YQQBBWh7IndvcmtzcGFjZVBhdGgiOiJFOlxcV29ya1xcU2FtcGxlUHJvamVjdCAgICAgICAgIiwidGFza0lkIjoic2Vzc19hMWIyYzNkNC0wMDAwLTQwMDAtODAwMC0wMDAwMDAwMDAwZTEifQ=="

        /** cap-192020.jsonl messageSeq=26, crc b4a1384b */
        const val GET_TASK_META_RES_201 =
            "BAIGyQEGKAXrA3sidGFza0lkIjoic2Vzc19hMWIyYzNkNC0wMDAwLTQwMDAtODAwMC0wMDAwMDAwMDAwZTEiLCJ0cmFjZUlkIjoiMDAwMDAwMDAtMDAwMC00MDAwLTgwMDAtMDAwMDAwMDAwMDUwIiwidGl0bGUiOiJTYW1wbGUgRm9yayBUYXNrICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICIsInRpdGxlT3ZlcnJpZGRlbiI6dHJ1ZSwid29ya3NwYWNlUGF0aCI6IkU6XFxXb3JrXFxTYW1wbGVQcm9qZWN0IiwiY3JlYXRlZEF0IjoxNzkyOTIxMDQ0ODMxLCJ1cGRhdGVkQXQiOjE3OTI5MjMwNjEyNjEsIm1vZGUiOiJidWlsZCIsIm1vZGVsIjoic2FtcGxlLW1vZGVsIiwidGhvdWdodExldmVsIjoiaGlnaCIsInByb3ZpZGVyIjoiZ2xtIiwiZm9ya2VkRnJvbVRhc2tJZCI6InNlc3Npb25fMjAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDIiLCJzdGF0dXMiOiJjb21wbGV0ZWQiLCJ0YXJnZXQiOm51bGx9"
    }
}
