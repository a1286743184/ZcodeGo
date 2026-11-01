package com.zcodeapp.remote.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文件交付闸门的判定边界。
 *
 * 这些断言直接对应「附件上传卡住」这个 bug 的各种成因：
 * 判成 ready 但实际不可发送 → 上传请求被丢弃；
 * 判成 not ready 但实际可发送 → 用户白等 8 秒。
 */
class FileDeliveryGateTest {

    @Test
    fun readyWhenBridgeAliveAndSocketOpen() {
        val json = """{"ws":"open","wsAgeMs":120,"visibility":"visible","bridge":true,"guard":false}"""
        val v = FileDeliveryGate.evaluate(json)
        assertTrue(v.ready)
        assertEquals("ready", v.reason)
    }

    @Test
    fun notReadyWhenSocketClosed() {
        val json = """{"ws":"closed","wsAgeMs":900,"visibility":"visible","bridge":true,"guard":false}"""
        val v = FileDeliveryGate.evaluate(json)
        assertFalse("WS 关闭时页面处于重连窗口，上传请求会被丢弃", v.ready)
        assertEquals("ws-closed", v.reason)
    }

    @Test
    fun notReadyWhenBridgeGone() {
        // 文档被重建：旧回调绑在死掉的 document 上，交出去也不会被处理。
        val json = """{"ws":"open","wsAgeMs":10,"visibility":"visible","bridge":false,"guard":false}"""
        val v = FileDeliveryGate.evaluate(json)
        assertFalse(v.ready)
        assertEquals("bridge-gone", v.reason)
    }

    @Test
    fun notReadyWhenGuardMissing() {
        val v = FileDeliveryGate.evaluate("""{"err":"no-guard"}""")
        assertFalse(v.ready)
        assertEquals("no-guard", v.reason)
    }

    @Test
    fun notReadyOnGarbageOrEmpty() {
        assertEquals("no-snapshot", FileDeliveryGate.evaluate(null).reason)
        assertEquals("no-snapshot", FileDeliveryGate.evaluate("  ").reason)
        assertEquals("no-snapshot", FileDeliveryGate.evaluate("null").reason)
        assertEquals("bad-json", FileDeliveryGate.evaluate("{not json}").reason)
    }

    @Test
    fun notReadyWhenWsStateUnknown() {
        val json = """{"ws":"none","wsAgeMs":-1,"visibility":"visible","bridge":true,"guard":false}"""
        assertFalse(FileDeliveryGate.evaluate(json).ready)
        // 原因码要能区分「从没连过」与「断了」，便于看日志时判断。
        assertEquals("ws-none", FileDeliveryGate.evaluate(json).reason)
    }

    @Test
    fun degradedIsNotReady() {
        val json = """{"ws":"degraded","wsAgeMs":50,"visibility":"visible","bridge":true,"guard":false}"""
        assertFalse(FileDeliveryGate.evaluate(json).ready)
        assertEquals("ws-degraded", FileDeliveryGate.evaluate(json).reason)
    }

    @Test
    fun booleanBridgeMustBeRealBoolean() {
        // 页面若把 bridge 写成字符串 "true"，说明快照格式变了；
        // 这种时候宁可判未就绪并留原因码，也不要靠字符串包含去猜。
        val json = """{"ws":"open","wsAgeMs":10,"bridge":"true"}"""
        assertFalse(FileDeliveryGate.evaluate(json).ready)
        assertEquals("bridge-gone", FileDeliveryGate.evaluate(json).reason)
    }
}
