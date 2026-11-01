package com.zcodeapp.remote.protocol

import java.util.Base64
import java.util.zip.CRC32
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RpcAssemblerTest {
    private fun frame(
        seq: Long,
        messageSeq: Long,
        fragmentIndex: Int,
        fragmentCount: Int,
        data: ByteArray,
        messageBytes: Long,
        crc: String? = crc32(data),
        session: String = "bridge-1",
        generation: Long = 1,
    ): JsonValue.Obj {
        val payload = linkedMapOf<String, JsonValue>()
        payload["bridgeSessionId"] = JsonValue.Str(session)
        payload["bridgeGeneration"] = JsonValue.Num(generation.toString())
        payload["messageSeq"] = JsonValue.Num(messageSeq.toString())
        payload["fragmentIndex"] = JsonValue.Num(fragmentIndex.toString())
        payload["fragmentCount"] = JsonValue.Num(fragmentCount.toString())
        payload["messageBytes"] = JsonValue.Num(messageBytes.toString())
        payload["seq"] = JsonValue.Num(seq.toString())
        payload["checksum"] = JsonValue.Obj(
            linkedMapOf(
                "algorithm" to JsonValue.Str("crc32"),
                "value" to JsonValue.Str(crc ?: "deadbeef"),
            )
        )
        payload["dataBase64"] = JsonValue.Str(Base64.getEncoder().encodeToString(data))
        payload["zcode_type"] = JsonValue.Str("rpc-frame")
        return JsonValue.Obj(payload)
    }

    private fun crc32(data: ByteArray): String {
        val c = CRC32().apply { update(data) }
        return String.format("%08x", c.value)
    }

    @Test
    fun singleFragmentSample() {
        val payload = frame(
            seq = 1, messageSeq = 1, fragmentIndex = 0, fragmentCount = 1,
            data = Base64.getDecoder().decode("BAEGyAEA"), messageBytes = 6,
        )
        val r = RpcAssembler().onFrame(payload)
        assertTrue(r is RpcAssembler.Result.Complete)
        r as RpcAssembler.Result.Complete
        assertEquals(6, r.bytes.size)
        assertEquals("b4ff6360", String.format("%08x", CRC32().apply { update(r.bytes) }.value))
    }

    @Test
    fun multiFragmentAssembly() {
        val a = byteArrayOf(1, 2, 3)
        val b = byteArrayOf(4, 5)
        val fullCrc = crc32(a + b)
        val assembler = RpcAssembler()
        val r1 = assembler.onFrame(frame(1, 9, 0, 2, a, 5, crc = fullCrc))
        assertTrue(r1 is RpcAssembler.Result.NeedMore)
        val r2 = assembler.onFrame(frame(2, 9, 1, 2, b, 5, crc = fullCrc))
        assertTrue(r2 is RpcAssembler.Result.Complete)
        assertEquals(byteArrayOf(1, 2, 3, 4, 5).toList(), (r2 as RpcAssembler.Result.Complete).bytes.toList())
    }

    @Test
    fun crcMismatchDiscarded() {
        val data = byteArrayOf(9, 9)
        val r = RpcAssembler().onFrame(frame(1, 1, 0, 1, data, 2, crc = "00000000"))
        assertTrue(r is RpcAssembler.Result.Discarded)
    }

    @Test
    fun duplicateFragmentIdempotent() {
        val a = byteArrayOf(1)
        val assembler = RpcAssembler()
        assembler.onFrame(frame(1, 5, 0, 2, a, 3))
        val r = assembler.onFrame(frame(1, 5, 0, 2, a, 3))
        assertTrue(r is RpcAssembler.Result.NeedMore)
    }

    @Test
    fun lengthMismatchDiscarded() {
        val r = RpcAssembler().onFrame(frame(1, 1, 0, 1, byteArrayOf(1, 2), messageBytes = 5))
        assertTrue(r is RpcAssembler.Result.Discarded)
    }

    @Test
    fun fragmentCountOutOfRange() {
        val r = RpcAssembler().onFrame(frame(1, 1, 5, 2, byteArrayOf(1), 1))
        assertTrue(r is RpcAssembler.Result.Discarded)
    }
}
