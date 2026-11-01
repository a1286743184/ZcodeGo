package com.zcodeapp.remote.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁住原生代发心跳的门控逻辑。
 *
 * 这些用例全部来自 2026-10-30 真机日志里实际出现的形态：
 *
 * ```
 * 23:00:04 bg.health recover=true reason=silent-13284796
 *          bizAge=13284s rxAge=13284s txAge=13284s
 * ```
 *
 * 三个年龄值完全相同且每 30 秒 +30s，而探针每次都返回了 JSON——
 * 也就是「渲染进程活着、evaluateJavascript 能执行，但页面定时器被停了」。
 *
 * 判错的代价分两头：把 `page-alive` 当成失败来告警会淹没日志；
 * 把「没 socket」当成成功又会让真正的问题看起来已修好。所以边界要锁死。
 */
class NativeHeartbeatTest {

    @Test
    fun `页面自己还在发心跳时跳过代发`() {
        // 页面 2 秒前刚发过：远在 30 秒窗口内，原生不该插手。
        // 这条保证前台行为与改动前完全一致。
        val raw = """{"result":"page-alive","pageAgeMs":2000}"""
        val r = NativeHeartbeat.parseResult(raw)
        assertFalse("页面自己活着时不该算代发成功", r.sent)
        assertEquals("page-alive", r.reason)
        assertEquals(2000L, r.pageAgeMs)
    }

    @Test
    fun `页面定时器被冻结后原生代发`() {
        // 复现日志现场：页面最后一次发送是 13284 秒前（3.7 小时）。
        val raw = """{"result":"sent","pageAgeMs":13284000,"type":"pair_status_query","sentAt":1793373069595}"""
        val r = NativeHeartbeat.parseResult(raw)
        assertTrue("冻结后必须代发", r.sent)
        assertEquals("sent", r.reason)
        assertEquals(13284000L, r.pageAgeMs)
    }

    @Test
    fun `页面从未发过帧时也代发`() {
        // pageAgeMs 为 -1 表示页面还没发过任何帧（刚加载或桥刚建立）。
        // 这时不该跳过：没有历史不代表页面活着，正是需要兜底的时候。
        val raw = """{"result":"sent","pageAgeMs":-1}"""
        val r = NativeHeartbeat.parseResult(raw)
        assertTrue("没有历史发送记录时应当代发", r.sent)
        assertEquals(-1L, r.pageAgeMs)
    }

    @Test
    fun `没有 socket 引用时明确失败`() {
        // bootstrap.js 未生效、或页面换了 WebSocket 实现时走到这里。
        // 必须能被识别成「没发出去」，否则会误判成已修复。
        val r = NativeHeartbeat.parseResult("""{"result":"no-socket"}""")
        assertFalse(r.sent)
        assertEquals("no-socket", r.reason)
    }

    @Test
    fun `socket 未处于 OPEN 时明确失败`() {
        // CONNECTING(0)/CLOSING(2)/CLOSED(3) 时 send 只会抛异常，
        // 该走的是重建路径（checkBackgroundHealth），不是代发心跳。
        val r = NativeHeartbeat.parseResult("""{"result":"socket-not-open","readyState":3}""")
        assertFalse(r.sent)
        assertEquals("socket-not-open", r.reason)
    }

    @Test
    fun `没有捕获到心跳帧模板时不自己拼帧`() {
        // 自己拼帧等于把协议常量复制进 App，页面升级换字段就会发出畸形帧。
        // 宁可这次不发、让上层走重建，也不发一条可能被服务端断开的帧。
        val r = NativeHeartbeat.parseResult("""{"result":"no-shape"}""")
        assertFalse(r.sent)
        assertEquals("no-shape", r.reason)
    }

    @Test
    fun `脚本异常不崩、按失败处理`() {
        val r = NativeHeartbeat.parseResult("""{"result":"error","message":"InvalidStateError"}""")
        assertFalse(r.sent)
        assertEquals("error", r.reason)
    }

    @Test
    fun `空结果与坏 JSON 都按失败处理`() {
        // evaluateJavascript 回调在页面被销毁时可能给 null/空串。
        assertFalse(NativeHeartbeat.parseResult(null).sent)
        assertFalse(NativeHeartbeat.parseResult("").sent)
        assertFalse(NativeHeartbeat.parseResult("null").sent)
        assertEquals("no-result", NativeHeartbeat.parseResult(null).reason)
        assertEquals("bad-json", NativeHeartbeat.parseResult("{oops").reason)
    }

    @Test
    fun `代发周期必须短于服务端 ack 超时`() {
        // 服务端 ack 超时是 30 秒（bundle 里 heartbeatAckTimeoutMs ?? 3e4）。
        // 代发周期若等于或超过它，一次抖动就会让服务端判死。
        assertTrue(
            "代发周期 ${NativeHeartbeat.PAGE_INTERVAL_MS}ms 必须留出余量",
            NativeHeartbeat.PAGE_INTERVAL_MS * 2 < 30_000L,
        )
    }

    @Test
    fun `页面存活窗口覆盖三次心跳`() {
        // 单次慢 tick 不该让原生抢着代发；超过 3 个周期仍无发送才算被冻结。
        assertEquals(
            NativeHeartbeat.PAGE_INTERVAL_MS * 3,
            NativeHeartbeat.PAGE_ALIVE_MS,
        )
    }

    @Test
    fun `代发脚本带上原生时间戳而非页面时钟`() {
        // 冻结若影响到了时钟推进，页面 Date.now() 本身就不可信。
        // 脚本必须把原生时间直接嵌进去，而不是在页面里现算。
        val script = NativeHeartbeat.build(1793373069595L)
        assertTrue("脚本应嵌入原生时间戳", script.contains("1793373069595"))
        assertTrue("脚本应检查 socket 是否 OPEN", script.contains("readyState !== 1"))
        assertTrue("脚本应跳过页面仍存活的情况", script.contains("page-alive"))
        assertTrue("脚本应原样回放捕获到的帧", script.contains("__zrHeartbeatShape"))
    }

    @Test
    fun `页面存活窗口不吞掉刚加载的页面`() {
        // pageAgeMs 落在窗口边界上时必须判为存活（不代发），
        // 边界内一秒才交给原生。这里锁住常量关系，避免以后调参调错方向。
        assertTrue(NativeHeartbeat.PAGE_ALIVE_MS > NativeHeartbeat.PAGE_INTERVAL_MS)
    }
}
