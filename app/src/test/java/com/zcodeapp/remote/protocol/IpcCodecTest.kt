package com.zcodeapp.remote.protocol

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class IpcCodecTest {
    /** v2 附录 B.2 实测样本：6 字节、无 13 字节前缀、CRC b4ff6360 */
    @Test
    fun decodesCapturedSample() {
        val bytes = Base64.getDecoder().decode("BAEGyAEA")
        assertEquals(6, bytes.size)
        val values = IpcCodec.decode(bytes)
        assertEquals(2, values.size)
        val head = values[0] as IpcValue.ArrV
        assertEquals(1, head.items.size)
        assertEquals(200L, (head.items[0] as IpcValue.IntV).value)
        assertTrue(values[1] is IpcValue.Undefined)
    }

    /**
     * 真实 listen 帧（cap-192020.jsonl, messageSeq=1, crc b3d0554b）：
     * head = Arr(4)[102, 0, "broadcast", "onMessage"]，尾值 Undefined。
     * 注意 callId 是 Int 而非字符串，且 head 有 4 个元素——旧断言把它写成 2 元素/Str 是错的。
     */
    @Test
    fun valueStreamOfListenCall() {
        val bytes = Base64.getDecoder().decode("BAQGZgYAAQlicm9hZGNhc3QBCW9uTWVzc2FnZQA=")
        assertEquals(29, bytes.size)
        val values = IpcCodec.decode(bytes)
        assertEquals(2, values.size)
        val head = values[0] as IpcValue.ArrV
        assertEquals(4, head.items.size)
        assertEquals(102L, (head.items[0] as IpcValue.IntV).value) // REQ_EVENT_LISTEN
        assertEquals(0L, (head.items[1] as IpcValue.IntV).value)   // callId
        assertEquals("broadcast", (head.items[2] as IpcValue.StrV).value)
        assertEquals("onMessage", (head.items[3] as IpcValue.StrV).value)
        assertTrue(values[1] is IpcValue.Undefined)
    }

    @Test
    fun varintEdgeAndOverflow() {
        // 300 = 0b100101100 → varint AC 02
        val v = IpcCodec.decode(byteArrayOf(6, 0xAC.toByte(), 0x02))
        assertEquals(300L, (v[0] as IpcValue.IntV).value)
        // 5 字节仍带续位 → 溢出
        try {
            IpcCodec.decode(byteArrayOf(6, 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte()))
            fail("expected overflow")
        } catch (e: IpcCodecException) {
            assertEquals("varint truncated", e.message)
        }
    }

    @Test
    fun truncatedStringRejected() {
        try {
            IpcCodec.decode(byteArrayOf(IpcCodec.TAG_STRING.toByte(), 0x05, 'a'.code.toByte()))
            fail("expected truncation error")
        } catch (e: IpcCodecException) {
            // expected
        }
    }

    @Test
    fun unknownTagRejected() {
        try {
            IpcCodec.decode(byteArrayOf(0x7F))
            fail("expected unknown tag")
        } catch (e: IpcCodecException) {
            // expected
        }
    }

    @Test
    fun jsonArrayNesting() {
        // Array(2)[ Int(7), Array(0) ]
        val v = IpcCodec.decode(byteArrayOf(4, 2, 6, 7, 4, 0))
        val arr = v[0] as IpcValue.ArrV
        assertEquals(2, arr.items.size)
        assertEquals(7L, (arr.items[0] as IpcValue.IntV).value)
        assertEquals(0, (arr.items[1] as IpcValue.ArrV).items.size)
    }
}
