package com.zcodeapp.remote.event

import com.zcodeapp.remote.protocol.ChannelEvent
import com.zcodeapp.remote.protocol.Direction
import com.zcodeapp.remote.protocol.IpcValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G2 fixture 回放：真实脱敏样本 → Observation 断言。
 * fixture 更新时本测试是归一器回归的第一道闸。
 */
class EventNormalizerTest {
    private val normalizer = EventNormalizer({ "conn1" }, { 1L })

    private fun fixtureJson(name: String): IpcValue.JsonV {
        val stream = javaClass.classLoader.getResourceAsStream("fixtures/$name")!!
        val text = stream.readBytes().toString(Charsets.UTF_8)
        return IpcValue.JsonV(text)
    }

    private fun event(arg: IpcValue.JsonV, kind: String = "event") = ChannelEvent(
        direction = Direction.RECV,
        service = null,
        method = null,
        kind = kind,
        arg = arg,
        typeCode = 204,
        messageSeq = 100,
    )

    @Test
    fun tasksIndexDeltaProducesRunningObservation() {
        val obs = normalizer.fromChannelEvent(event(fixtureJson("tasks-index-delta.json")))
        // SNAPSHOT + TASK_RUNNING
        assertEquals(2, obs.size)
        val snapshot = obs[0]
        assertEquals(ObsKind.SNAPSHOT, snapshot.kind)
        assertEquals("session_10000000000000000000000000000001", snapshot.taskKey?.taskId)
        assertEquals("/wsp/sample", snapshot.taskKey?.workspaceId)
        assertEquals("running", snapshot.patch.status)
        assertEquals("Sample Task", snapshot.patch.title)
        assertEquals(32381L, snapshot.seq)
        // runId 取 traceId（一轮运行内稳定），不是每条都在变的 updatedAt
        assertEquals("00000000-0000-4000-8000-00000000007d", snapshot.runId)
        assertTrue(snapshot.streamId!!.startsWith("ti|"))
        // 无 pendingInteraction 时计数显式为 0（reducer 据此在完整快照里撤已知交互）
        assertEquals(0, snapshot.patch.pendingUserInputCount)
        assertEquals(0, snapshot.patch.pendingPermissionCount)
        assertEquals(ObsKind.TASK_RUNNING, obs[1].kind)
    }

    @Test
    fun sessionsIndexSnapshotCoversCompletedAndRunning() {
        val obs = normalizer.fromChannelEvent(event(fixtureJson("sessions-index-snapshot.json")))
        // 2 个会话 × (SNAPSHOT + 状态沿) = 4
        assertEquals(4, obs.size)
        val completed = obs.first { it.taskKey?.taskId == "session_30000000000000000000000000000003" && it.kind != ObsKind.SNAPSHOT }
        assertEquals(ObsKind.TASK_COMPLETED, completed.kind)
        val running = obs.first { it.taskKey?.taskId == "session_running01" && it.kind != ObsKind.SNAPSHOT }
        assertEquals(ObsKind.TASK_RUNNING, running.kind)
    }

    @Test
    fun getTaskMetaResultMapped() {
        val ev = ChannelEvent(
            direction = Direction.RECV,
            service = "zcode-task",
            method = "getTaskMeta",
            kind = "promise-success",
            arg = fixtureJson("get-task-meta-result.json"),
            typeCode = 201,
            messageSeq = 26,
        )
        val obs = normalizer.fromChannelEvent(ev)
        assertEquals(2, obs.size)
        assertEquals("sess_a1b2c3d4-0000-4000-8000-0000000000e1", obs[0].taskKey?.taskId)
        assertEquals(ObsKind.TASK_COMPLETED, obs[1].kind)
    }

    @Test
    fun endToEndReducerNotificationFromFixture() {
        val reducer = TaskReducer()
        // 运行中（基线）
        val running = normalizer.fromChannelEvent(event(fixtureJson("tasks-index-delta.json")))
        running.forEach { reducer.reduce(it) }
        // 终态增量（同一订阅流，toSeq 前进）
        val terminalText = javaClass.classLoader
            .getResourceAsStream("fixtures/tasks-index-delta.json")!!
            .readBytes()
            .toString(Charsets.UTF_8).replace("\"status\": \"running\"", "\"status\": \"completed\"")
            .replace("\"toSeq\": 32381", "\"toSeq\": 32400")
            .replace("\"updatedAt\": 1792926173878", "\"updatedAt\": 1792926200000")
        val terminal = normalizer.fromChannelEvent(event(IpcValue.JsonV(terminalText)))
        val intents = terminal.flatMap { reducer.reduce(it) }
        assertEquals(1, intents.size)
        assertTrue(intents[0] is NotificationIntent.TaskTerminal)
        // 重放终态：不重复
        val again = terminal.flatMap { reducer.reduce(it.copy()) }
        assertTrue(again.isEmpty())
    }

    /**
     * G2 后半实测回归：Agent 提问出现时的真实形态。
     * 交互身份在 meta.pendingInteraction（单对象），liveStatus=waiting。
     * 注意 interactionId 前缀是 perm_ 但 kind 是 userInput——不能按前缀推类型。
     */
    @Test
    fun pendingInteractionProducesAttentionWithRealId() {
        val obs = normalizer.fromChannelEvent(event(fixtureJson("tasks-index-interaction-requested.json")))
        val requested = obs.firstOrNull { it.kind == ObsKind.INTERACTION_REQUESTED }
        assertTrue("未产出 INTERACTION_REQUESTED", requested != null)
        requested!!
        assertEquals("perm_3572b093-2ff7-45ce-9054-79c733a5a23d", requested.interactionId)
        assertEquals(InteractionKind.USER_INPUT, requested.interactionKind)
        assertEquals(Confidence.VERIFIED, requested.confidence)
        // 计数来自 activity.pendingInteractions，不是交互身份来源
        assertEquals(1, requested.patch.pendingUserInputCount)
        assertEquals(0, requested.patch.pendingPermissionCount)
    }

    /**
     * 回答后的真实形态：pendingInteraction 消失、liveStatus 回到 running。
     * 归一器应发出 SNAPSHOT_PENDING_CLEAR，由 reducer 按 interactionId 精确撤下。
     */
    @Test
    fun clearedInteractionEmitsPendingClear() {
        val reducer = TaskReducer()
        val addr = TaskAddress("conn1", "/wsp/sample", "session_40000000000000000000000000000004")

        // 基线：同任务的 running、无交互。用内联 JSON 以便序号早于提问帧（真实序列：
        // toSeq 54029 running → 54248 提问 → 54534 回答后 running）。
        val baseline = normalizer.fromChannelEvent(
            event(
                IpcValue.JsonV(
                    """
                    {"topic":"controller/tasks-index","subscriptionId":"s","logEpoch":"e",
                     "fromSeq":54028,"toSeq":54029,
                     "payload":{"kind":"deltas","deltas":[{"op":"task.upserted","task":{
                       "address":{"workspacePath":"/wsp/sample","taskId":"session_40000000000000000000000000000004"},
                       "meta":{"taskId":"session_40000000000000000000000000000004",
                               "traceId":"49adefcf-4033-41ee-b229-303e0c5cc93b",
                               "title":"Sample Task With Question","workspacePath":"/wsp/sample",
                               "updatedAt":1792936112000,"status":"running"},
                       "liveStatus":"running",
                       "activity":{"phase":"running","lastActivityAt":1792936112000,"hasBackgroundWork":false}}}]}}
                    """.trimIndent()
                )
            )
        )
        baseline.forEach { reducer.reduce(it) }
        assertEquals(0, reducer.pendingCount("conn1", addr))

        // 提问出现：登记交互并通知一次
        val req = normalizer.fromChannelEvent(event(fixtureJson("tasks-index-interaction-requested.json")))
        val reqIntents = req.flatMap { reducer.reduce(it) }
        val att = reqIntents.filterIsInstance<NotificationIntent.Attention>().single()
        assertEquals("perm_3572b093-2ff7-45ce-9054-79c733a5a23d", att.interactionKey)
        assertEquals(InteractionKind.USER_INPUT, att.kind)
        assertEquals(1, reducer.pendingCount("conn1", addr))

        // 回答后（toSeq 54534）：撤下该 interaction，且只撤这一条
        val cleared = normalizer.fromChannelEvent(event(fixtureJson("tasks-index-interaction-cleared.json")))
        val clrIntents = cleared.flatMap { reducer.reduce(it) }
        val res = clrIntents.filterIsInstance<NotificationIntent.ResolveAttention>().single()
        assertEquals("perm_3572b093-2ff7-45ce-9054-79c733a5a23d", res.interactionKey)
        assertEquals(0, reducer.pendingCount("conn1", addr))
    }

    /** 墓碑：交互撤下后，迟到的同 id 请求不得复活（独立用例，避免序号复用干扰）。 */
    @Test
    fun resolvedInteractionDoesNotResurrect() {
        val reducer = TaskReducer()
        // 直接用提问帧建立基线并登记（首次见即交互，会产生一次 Attention）
        val req = normalizer.fromChannelEvent(event(fixtureJson("tasks-index-interaction-requested.json")))
        val first = req.flatMap { reducer.reduce(it) }
        assertEquals(1, first.filterIsInstance<NotificationIntent.Attention>().size)

        // 清除（toSeq 更大）
        val cleared = normalizer.fromChannelEvent(event(fixtureJson("tasks-index-interaction-cleared.json")))
        assertEquals(1, cleared.flatMap { reducer.reduce(it) }.filterIsInstance<NotificationIntent.ResolveAttention>().size)

        // 再次收到同 id 请求：墓碑生效，不再通知
        val again = normalizer.fromChannelEvent(event(fixtureJson("tasks-index-interaction-requested.json")))
        assertTrue(again.flatMap { reducer.reduce(it) }.none { it is NotificationIntent.Attention })
    }

    @Test
    fun unknownTopicsIgnored() {
        val ev = event(IpcValue.JsonV("""{"topic":"controller/workspaces","payload":{}}"""))
        assertTrue(normalizer.fromChannelEvent(ev).isEmpty())
        val groups = event(IpcValue.JsonV("""{"channel":"state:task-groups","payload":{}}"""))
        assertTrue(normalizer.fromChannelEvent(groups).isEmpty())
    }

    /**
     * 实测缺陷回归：cap-192020.jsonl 的 toSeq 32873 / 32874 是同一任务同一次运行的
     * 两条 completed（updatedAt 分别为 1792926331707 / 1792926332241，traceId 相同）。
     * runId 若取 updatedAt，两条会被当成两轮运行，产生两次通知。
     */
    @Test
    fun sameRunTwoCompletedDeltasNotifyOnce() {
        val reducer = TaskReducer()
        // 先建立 running 基线
        normalizer.fromChannelEvent(event(fixtureJson("tasks-index-delta.json")))
            .forEach { reducer.reduce(it) }

        fun completedDelta(toSeq: Long, updatedAt: Long): IpcValue.JsonV = IpcValue.JsonV(
            """
            {"topic":"controller/tasks-index","subscriptionId":"sub-1","logEpoch":"ep-1",
             "fromSeq":${toSeq - 1},"toSeq":$toSeq,"sentAt":$updatedAt,
             "payload":{"kind":"deltas","deltas":[{"op":"task.upserted","task":{
               "address":{"workspacePath":"/wsp/sample","taskId":"session_10000000000000000000000000000001"},
               "meta":{"taskId":"session_10000000000000000000000000000001",
                       "traceId":"00000000-0000-4000-8000-00000000007d",
                       "title":"Sample Task","workspacePath":"/wsp/sample",
                       "updatedAt":$updatedAt,"status":"completed"},
               "membership":{"pinned":false,"archived":false,"active":true},
               "sourceAvailability":"online","liveStatus":"completed",
               "activity":{"phase":"completedInterrupted","lastActivityAt":$updatedAt,"hasBackgroundWork":false}}}]}}
            """.trimIndent()
        )

        val first = normalizer.fromChannelEvent(event(completedDelta(32873, 1792926331707)))
            .flatMap { reducer.reduce(it) }
        assertEquals(1, first.size)
        assertTrue(first[0] is NotificationIntent.TaskTerminal)

        // 同一轮运行的第二条 completed：不得再发一条
        val second = normalizer.fromChannelEvent(event(completedDelta(32874, 1792926332241)))
            .flatMap { reducer.reduce(it) }
        assertTrue(second.isEmpty())
    }

    /**
     * activity.phase 提供比 meta.status 更细的终态：completedInterrupted 也必须是 TASK_COMPLETED，
     * 否则 interrupted 分支永远不会通知。
     */
    @Test
    fun interruptedPhaseMapsToCompleted() {
        val json = IpcValue.JsonV(
            """
            {"topic":"controller/tasks-index","subscriptionId":"s","logEpoch":"e","toSeq":10,
             "payload":{"kind":"deltas","deltas":[{"op":"task.upserted","task":{
               "address":{"workspacePath":"/w","taskId":"t-int"},
               "meta":{"taskId":"t-int","traceId":"tr-1","status":"completed","updatedAt":1},
               "activity":{"phase":"completedInterrupted","lastActivityAt":1,"hasBackgroundWork":false}}}]}}
            """.trimIndent()
        )
        val obs = normalizer.fromChannelEvent(event(json))
        assertTrue(obs.any { it.kind == ObsKind.TASK_COMPLETED })
    }
}
