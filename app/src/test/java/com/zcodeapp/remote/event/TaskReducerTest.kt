package com.zcodeapp.remote.event

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskReducerTest {
    private val conn = "conn1"
    private val addr = TaskAddress(conn, null, "task-1")

    private fun obs(
        kind: ObsKind,
        seq: Long? = null,
        task: TaskAddress = addr,
        interaction: String? = null,
        interactionKind: InteractionKind? = null,
        run: String? = null,
        patch: TaskPatch = TaskPatch(),
        confidence: Confidence = Confidence.VERIFIED,
        source: Source = Source.PAGE_RPC,
        stream: String = "s1",
    ) = Observation(
        connectionId = conn, epoch = 1, source = source,
        streamId = stream, seq = seq, observedAtMs = 1,
        taskKey = task, interactionId = interaction, runId = run,
        interactionKind = interactionKind,
        kind = kind, patch = patch, confidence = confidence,
    )

    @Test
    fun baselineSnapshotWithTerminalDoesNotNotify() {
        val r = TaskReducer()
        val out = r.reduce(
            obs(ObsKind.SNAPSHOT, seq = 10, patch = TaskPatch(status = "completed", title = "t")),
        )
        assertTrue(out.isEmpty())
        // 随后同 run 的显式终态事件也被吸收
        val out2 = r.reduce(
            obs(ObsKind.TASK_COMPLETED, seq = 10, run = null, patch = TaskPatch(status = "completed")),
        )
        assertTrue(out2.isEmpty())
    }

    @Test
    fun runningThenCompletedNotifies() {
        val r = TaskReducer()
        r.reduce(obs(ObsKind.TASK_RUNNING, seq = 1, run = "run-1", patch = TaskPatch(status = "running")))
        val out = r.reduce(obs(ObsKind.TASK_COMPLETED, seq = 5, run = "run-1"))
        assertEquals(1, out.size)
        val intent = out[0] as NotificationIntent.TaskTerminal
        assertEquals("run-1", intent.runKey)
        assertTrue(!intent.failed)
        // 重放：不重复
        assertTrue(r.reduce(obs(ObsKind.TASK_COMPLETED, seq = 5, run = "run-1")).isEmpty())
    }

    @Test
    fun newRunNotifiesAgain() {
        val r = TaskReducer()
        r.reduce(obs(ObsKind.TASK_RUNNING, seq = 1, run = "r1"))
        r.reduce(obs(ObsKind.TASK_COMPLETED, seq = 5, run = "r1"))
        r.reduce(obs(ObsKind.TASK_RUNNING, seq = 10, run = "r2"))
        val out = r.reduce(obs(ObsKind.TASK_COMPLETED, seq = 20, run = "r2"))
        assertEquals(1, out.size)
    }

    @Test
    fun lateRunningDoesNotRollbackTerminal() {
        val r = TaskReducer()
        r.reduce(obs(ObsKind.TASK_RUNNING, seq = 1, run = "r1"))
        r.reduce(obs(ObsKind.TASK_COMPLETED, seq = 10, run = "r1"))
        assertTrue(r.reduce(obs(ObsKind.TASK_RUNNING, seq = 3, run = "r1")).isEmpty())
        assertTrue(r.reduce(obs(ObsKind.TASK_COMPLETED, seq = 10, run = "r1")).isEmpty())
    }

    @Test
    fun streamSeqDedup() {
        val r = TaskReducer()
        r.reduce(obs(ObsKind.TASK_RUNNING, seq = 5, run = "r1"))
        // 同 stream 回放更小 seq：丢弃
        assertTrue(r.reduce(obs(ObsKind.TASK_COMPLETED, seq = 4, run = "r1")).isEmpty())
    }

    @Test
    fun attentionLifecycle() {
        val r = TaskReducer()
        r.reduce(obs(ObsKind.SNAPSHOT, seq = 1, patch = TaskPatch(status = "running")))
        val req = r.reduce(
            obs(ObsKind.INTERACTION_REQUESTED, seq = 2, interaction = "A1", interactionKind = InteractionKind.PERMISSION)
        )
        assertEquals(1, req.size)
        // 同 id 重复请求不重复通知
        assertTrue(
            r.reduce(
                obs(ObsKind.INTERACTION_REQUESTED, seq = 3, interaction = "A1", interactionKind = InteractionKind.PERMISSION)
            ).isEmpty()
        )
        // 处理完成：撤回 + 墓碑
        val res = r.reduce(obs(ObsKind.INTERACTION_RESOLVED, seq = 4, interaction = "A1"))
        assertEquals(1, res.size)
        assertTrue(res[0] is NotificationIntent.ResolveAttention)
        // 迟到的同 id 请求不复活
        assertTrue(
            r.reduce(
                obs(ObsKind.INTERACTION_REQUESTED, seq = 5, interaction = "A1", interactionKind = InteractionKind.PERMISSION)
            ).isEmpty()
        )
    }

    @Test
    fun concurrentInteractionsIndependent() {
        val r = TaskReducer()
        val b = TaskAddress(conn, null, "task-B")
        r.reduce(obs(ObsKind.SNAPSHOT, seq = 1, patch = TaskPatch(status = "running")))
        r.reduce(obs(ObsKind.SNAPSHOT, seq = 1, task = b, patch = TaskPatch(status = "running")))
        r.reduce(obs(ObsKind.INTERACTION_REQUESTED, seq = 2, interaction = "A1"))
        r.reduce(obs(ObsKind.INTERACTION_REQUESTED, seq = 3, interaction = "A2"))
        r.reduce(obs(ObsKind.INTERACTION_REQUESTED, seq = 4, task = b, interaction = "B1"))
        val out = r.reduce(obs(ObsKind.INTERACTION_RESOLVED, seq = 5, interaction = "A1"))
        assertEquals(1, out.size)
        assertEquals("A1", (out[0] as NotificationIntent.ResolveAttention).interactionKey)
    }

    @Test
    fun snapshotPendingClearResolvesAll() {
        val r = TaskReducer()
        r.reduce(obs(ObsKind.SNAPSHOT, seq = 1, patch = TaskPatch(status = "running")))
        r.reduce(obs(ObsKind.INTERACTION_REQUESTED, seq = 2, interaction = "A1"))
        r.reduce(obs(ObsKind.INTERACTION_REQUESTED, seq = 3, interaction = "A2"))
        val out = r.reduce(
            obs(ObsKind.SNAPSHOT, seq = 4, patch = TaskPatch(status = "running", pendingPermissionCount = 0))
        )
        // 两个交互各产生一条撤回
        assertEquals(2, out.size)
        assertTrue(out.all { it is NotificationIntent.ResolveAttention })
    }

    @Test
    fun domLowConfidenceTerminalNotifiesOnce() {
        val r = TaskReducer()
        r.reduce(obs(ObsKind.SNAPSHOT, seq = 1, patch = TaskPatch(status = "running")))
        val out1 = r.reduce(
            obs(ObsKind.TASK_COMPLETED, seq = 2, run = "r1", confidence = Confidence.CANDIDATE, source = Source.DOM)
        )
        assertEquals(1, out1.size)
        assertTrue((out1[0] as NotificationIntent.Attention).lowConfidence)
        assertTrue(
            r.reduce(
                obs(ObsKind.TASK_COMPLETED, seq = 3, run = "r1", confidence = Confidence.CANDIDATE, source = Source.DOM)
            ).isEmpty()
        )
    }

    @Test
    fun domInteractionWithoutTaskIgnored() {
        val r = TaskReducer()
        val out = r.reduce(
            obs(ObsKind.INTERACTION_REQUESTED, seq = 1, task = TaskAddress(conn, null, ""), interaction = "x").let {
                it.copy(taskKey = null)
            }
        )
        assertTrue(out.isEmpty())
    }
}
