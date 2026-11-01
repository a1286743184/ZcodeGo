package com.zcodeapp.remote.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 桥 schema 的解析边界。
 *
 * 重点覆盖 2026-10-27 新增的两类消息：diag（页面侧异常）与带计数的 health。
 * 这两类的字段缺失时不能把整条消息判成畸形——否则一次协议演进就会让诊断能力整体失效。
 */
class BridgeSchemaTest {

    private fun health(doc: String) =
        BridgeSchema.parse(
            """{"v":2,"kind":"health","doc":"$doc","state":"closed","reasonCode":"WS_CLOSED"}""",
        )

    @Test
    fun healthWithCounters() {
        // 先让文档通过 hello 登记之外的解析：schema 本身不要求顺序，这里直接验字段。
        val msg = BridgeSchema.parse(
            """{"v":2,"kind":"health","doc":"d1","state":"degraded","reasonCode":"QUEUE_CAP","dropped":17,"queuedBytes":4194304,"queuedItems":23}""",
        )
        msg as BridgeSchema.BridgeMsg.Health
        assertEquals("degraded", msg.state)
        assertEquals("QUEUE_CAP", msg.reasonCode)
        assertEquals(17L, msg.dropped)
        assertEquals(4_194_304L, msg.queuedBytes)
        assertEquals(23L, msg.queuedItems)
    }

    @Test
    fun healthWithoutCountersStillParses() {
        val msg = health("d1") as BridgeSchema.BridgeMsg.Health
        assertEquals("closed", msg.state)
        assertNull(msg.dropped)
        assertNull(msg.queuedItems)
    }

    @Test
    fun diagCarriesTapContext() {
        val msg = BridgeSchema.parse(
            """{"v":2,"kind":"diag","doc":"d2","level":"error","code":"unhandled-rejection","text":"Web remote control transport stopped.","tap":"button[data-testid=send] \"发送\"","tapAgeMs":842}""",
        )
        msg as BridgeSchema.BridgeMsg.Diag
        assertEquals("unhandled-rejection", msg.code)
        assertEquals("Web remote control transport stopped.", msg.text)
        assertEquals(842L, msg.tapAgeMs)
        assertNotNull(msg.tap)
    }

    @Test
    fun diagTextIsTruncatedNotRejected() {
        val long = "x".repeat(900)
        val msg = BridgeSchema.parse(
            """{"v":2,"kind":"diag","doc":"d3","level":"error","code":"console","text":"$long"}""",
        )
        msg as BridgeSchema.BridgeMsg.Diag
        assertTrue("超长文本应截断而不是整条丢弃", msg.text.length <= 400)
    }

    @Test
    fun diagWithoutTextIsRejected() {
        assertNull(BridgeSchema.parse("""{"v":2,"kind":"diag","doc":"d4","level":"error","code":"x"}"""))
    }

    @Test
    fun wrongVersionRejected() {
        assertNull(BridgeSchema.parse("""{"v":1,"kind":"health","doc":"d5","state":"open"}"""))
    }
}
