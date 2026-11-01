package com.zcodeapp.remote.notify

import com.zcodeapp.remote.event.InteractionKind
import com.zcodeapp.remote.event.NotificationIntent
import com.zcodeapp.remote.event.TaskAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationLogTest {
    private val conn = "conn1"
    private val addr = TaskAddress(conn, null, "task-1")
    private fun routeFor(@Suppress("UNUSED_PARAMETER") a: TaskAddress) = RouteTarget("https://zcode.z.ai/remote/v4?sid=x", a.taskId, null)

    @Test
    fun terminalDedupe() {
        val log = InMemoryNotificationLog()
        val intent = NotificationIntent.TaskTerminal(conn, addr, "r1", failed = false, title = "t", seq = 1)
        val ops1 = log.apply(listOf(intent), 1000L, ::routeFor)
        assertEquals(1, ops1.size)
        assertTrue(ops1[0] is DeliveryOp.PostTask)
        val ops2 = log.apply(listOf(intent), 2000L, ::routeFor)
        assertTrue(ops2.isEmpty())
    }

    @Test
    fun attentionDedupeAndResolve() {
        val log = InMemoryNotificationLog()
        val att = NotificationIntent.Attention(conn, addr, "A1", InteractionKind.PERMISSION, "t", 1)
        val ops = log.apply(listOf(att), 1000L, ::routeFor)
        assertEquals(1, ops.size)
        assertTrue(ops[0] is DeliveryOp.PostAttention)
        assertTrue(log.apply(listOf(att), 1100L, ::routeFor).isEmpty())

        val resolve = NotificationIntent.ResolveAttention(conn, addr, "A1")
        val rOps = log.apply(listOf(resolve), 1200L, ::routeFor)
        // 撤回 A1 + 剩余 0 时撤销汇总
        assertEquals(2, rOps.size)
        assertTrue(rOps.any { it is DeliveryOp.CancelAttention })
        assertTrue(rOps.any { it is DeliveryOp.CancelSummary })

        // 墓碑：迟到的同 id 请求不复活
        assertTrue(log.apply(listOf(att), 1300L, ::routeFor).isEmpty())
    }

    @Test
    fun summaryPostedWhenMultiplePending() {
        val log = InMemoryNotificationLog()
        log.apply(
            listOf(NotificationIntent.Attention(conn, addr, "A1", InteractionKind.PERMISSION, "t", 1)),
            1000L, ::routeFor
        )
        val ops = log.apply(
            listOf(NotificationIntent.Attention(conn, addr, "A2", InteractionKind.PERMISSION, "t", 2)),
            1100L, ::routeFor
        )
        // A2 通知 + 汇总
        assertEquals(2, ops.size)
        assertTrue(ops.any { it is DeliveryOp.PostSummary })

        val rOps = log.apply(listOf(NotificationIntent.ResolveAttention(conn, addr, "A1")), 1200L, ::routeFor)
        // A1 撤回 + 汇总降级撤销
        assertTrue(rOps.any { it is DeliveryOp.CancelAttention })
        assertTrue(rOps.any { it is DeliveryOp.CancelSummary })
    }

    @Test
    fun recoverRepostsExpiredProcessing() {
        val log = InMemoryNotificationLog()
        log.apply(listOf(NotificationIntent.TaskTerminal(conn, addr, "r1", false, "t", 1)), 1000L, ::routeFor)
        // 未回执 + 租约过期 → 恢复重投
        val recovered = log.recover(1000L + BaseNotificationLog.LEASE_MS + 1)
        assertEquals(1, recovered.size)
        // 回执后不再恢复
        log.markDelivered(
            listOf("term|conn1|conn1|-|task-1|r1|false"),
            2000L
        )
        assertTrue(log.recover(9999_000L).isEmpty())
    }
}
