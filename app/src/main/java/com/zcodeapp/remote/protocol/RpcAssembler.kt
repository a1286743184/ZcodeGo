package com.zcodeapp.remote.protocol

import java.util.zip.CRC32

/**
 * rpc-frame 分片重组（v2 附录 B.4）：按 bridgeSessionId|bridgeGeneration|messageSeq 聚合分片，
 * 全部到齐后校验 messageBytes 与 CRC32。超限/缺片/坏帧不进入业务状态机，返回 Discarded。
 */
class RpcAssembler(
    private val maxSlots: Int = 64,
    private val maxFragmentsPerMessage: Int = 128,
    private val maxFragmentBytes: Int = 512 * 1024,
    private val maxMessageBytes: Int = 16 * 1024 * 1024,
) {
    sealed class Result {
        object NeedMore : Result()
        data class Complete(
            val bridgeSessionId: String?,
            val bridgeGeneration: Long?,
            val messageSeq: Long,
            val bytes: ByteArray,
        ) : Result()

        data class Discarded(val reason: String) : Result()
    }

    private class Slot(
        val fragments: Array<ByteArray?>,
        var received: Int,
        val messageBytes: Long,
        val crcHex: String?,
    )

    private val slots = LinkedHashMap<String, Slot>()
    var discardCount = 0L
        private set

    fun onFrame(payload: JsonValue.Obj?): Result {
        if (payload == null) return Result.Discarded("payload null")
        val bs = payload.str("bridgeSessionId")
        val bg = payload.num("bridgeGeneration")
        val mi = payload.num("messageSeq")
        val fi = payload.num("fragmentIndex") ?: return Result.Discarded("no fragmentIndex")
        val fc = payload.num("fragmentCount") ?: return Result.Discarded("no fragmentCount")
        val mb = payload.num("messageBytes") ?: return Result.Discarded("no messageBytes")
        val b64 = payload.str("dataBase64") ?: return Result.Discarded("no dataBase64")

        if (mi == null) return Result.Discarded("no messageSeq")
        if (fi < 0 || fi >= fc || fc < 1 || fc > maxFragmentsPerMessage) {
            discardCount++
            return Result.Discarded("fragment meta out of range fi=$fi fc=$fc")
        }
        if (mb < 0 || mb > maxMessageBytes) {
            discardCount++
            return Result.Discarded("messageBytes out of range $mb")
        }

        val data = try {
            java.util.Base64.getDecoder().decode(b64)
        } catch (e: IllegalArgumentException) {
            discardCount++
            return Result.Discarded("base64 invalid")
        }
        if (data.size > maxFragmentBytes) {
            discardCount++
            return Result.Discarded("fragment too large ${data.size}")
        }

        val key = "$bs|$bg|$mi"
        val slot = slots[key]
        if (slot == null) {
            if (slots.size >= maxSlots) {
                val eldest = slots.entries.firstOrNull { it.value.received < it.value.fragments.size }
                    ?: slots.entries.first()
                slots.remove(eldest.key)
                discardCount++
            }
            val arr = arrayOfNulls<ByteArray>(fc.toInt())
            arr[fi.toInt()] = data
            slots[key] = Slot(arr, 1, mb, checksumHex(payload))
        } else {
            if (slot.fragments.getOrNull(fi.toInt()) != null) {
                return Result.NeedMore
            }
            slot.fragments[fi.toInt()] = data
            slot.received++
        }

        val s = slots[key] ?: return Result.Discarded("slot evicted")
        if (s.received < s.fragments.size) return Result.NeedMore

        val total = s.fragments.sumOf { (it?.size ?: 0).toLong() }
        if (total != s.messageBytes) {
            slots.remove(key)
            discardCount++
            return Result.Discarded("messageBytes mismatch got=$total want=${s.messageBytes}")
        }
        val assembled = ByteArray(total.toInt())
        var offset = 0
        for (frag in s.fragments) {
            if (frag == null) {
                slots.remove(key)
                discardCount++
                return Result.Discarded("missing fragment despite count")
            }
            System.arraycopy(frag, 0, assembled, offset, frag.size)
            offset += frag.size
        }
        slots.remove(key)
        val crc = CRC32().apply { update(assembled) }
        val hex = String.format("%08x", crc.value)
        val expected = s.crcHex
        if (expected != null && !expected.equals(hex, ignoreCase = true)) {
            discardCount++
            return Result.Discarded("crc mismatch want=$expected got=$hex")
        }
        return Result.Complete(bs, bg, mi, assembled)
    }

    fun reset() {
        slots.clear()
    }

    private fun checksumHex(payload: JsonValue.Obj): String? {
        val cs = payload.obj("checksum") ?: return null
        if (cs.str("algorithm") != "crc32") return null
        return cs.str("value")
    }
}
