package com.zcodeapp.remote.event

import com.zcodeapp.remote.protocol.ChannelEvent
import com.zcodeapp.remote.protocol.IpcValue
import com.zcodeapp.remote.protocol.JsonValue

/**
 * ChannelEvent → Observation 归一化。
 *
 * 【G2 2026-10-25 实测固化】数据源形态（详见 app/src/test/resources/fixtures/ 与设计文档附录）：
 * 1) 204 事件自描述：arg JSON 自带 topic/subscriptionId/logEpoch/fromSeq/toSeq/payload；
 *    - topic=controller/tasks-index：payload.kind=snapshot|deltas，delta.op=task.upserted，
 *      task.address.{workspacePath,taskId}，task.meta.{title,status,updatedAt}；
 *    - topic=sessions-index/<工作区路径>：frame.payload.snapshot.sessions[]，phase=completedSuccess 等；
 *    - broadcast channel=state:task-groups：分组元数据，不产生观察。
 * 2) 201 回执经 callRegistry 已还原 service/method：zcode-task/getTaskMeta 返回
 *    {taskId,title,status,workspacePath}。
 * 3) 游标用业务流（subscriptionId+logEpoch, toSeq）而非物理 messageSeq，重连后仍单调。
 * 4) 未实测：pendingInteractionSummary 的确切结构（本次采样窗口无提问/审批事件），
 *    保留兼容提取，待真机 G2 补充后收紧。
 */
class EventNormalizer(
    private val connectionId: () -> String,
    private val epoch: () -> Long,
) {
    fun fromChannelEvent(ev: ChannelEvent): List<Observation> {
        val out = ArrayList<Observation>()
        when (ev.kind) {
            "event" -> handleEvent(ev, out)
            "promise-success" -> handlePromiseSuccess(ev, out)
        }
        return out
    }

    private fun handleEvent(ev: ChannelEvent, out: MutableList<Observation>) {
        val root = (ev.arg as? IpcValue.JsonV)?.parsed as? JsonValue.Obj ?: return
        val topic = root.str("topic") ?: return
        when {
            topic == "controller/tasks-index" -> tasksIndex(root, out)
            topic.startsWith("sessions-index/") -> sessionsIndex(root, out)
            else -> Unit // state:task-groups 等元数据通道：不产生观察
        }
    }

    private fun tasksIndex(root: JsonValue.Obj, out: MutableList<Observation>) {
        val payload = root.obj("payload") ?: return
        val seq = root.num("toSeq")
        val stream = streamOf(root)
        when (payload.str("kind")) {
            "deltas" -> {
                val deltas = payload.arr("deltas") ?: return
                for (delta in deltas.items) {
                    if (delta !is JsonValue.Obj) continue
                    when (delta.str("op")) {
                        "task.upserted" -> delta.obj("task")?.let { emitTask(it, seq, stream, out) }
                        // task.removed 等未知 op：不猜
                    }
                }
            }
            "snapshot" -> {
                val tasks = payload.arr("tasks") ?: payload.arr("items") ?: return
                for (t in tasks.items) {
                    if (t is JsonValue.Obj) emitTask(t, seq, stream, out)
                }
            }
        }
    }

    private fun sessionsIndex(root: JsonValue.Obj, out: MutableList<Observation>) {
        // 外层可能再包一层 frame（实测：wireVersion/kind/logicalFrameId + frame{topic,toSeq,payload}）
        val frame = root.obj("frame") ?: root
        val payload = frame.obj("payload") ?: return
        val snapshot = payload.obj("snapshot") ?: return
        val sessions = snapshot.arr("sessions") ?: return
        val seq = frame.num("toSeq") ?: root.num("toSeq")
        val stream = "six|" + (frame.str("subscriptionId") ?: root.str("subscriptionId") ?: "")
        for (s in sessions.items) {
            if (s !is JsonValue.Obj) continue
            val sessionId = s.str("sessionId") ?: continue
            val phase = s.str("phase") ?: continue
            emitObservation(
                seq = seq,
                stream = stream,
                taskId = sessionId,
                workspace = s.str("workspaceId"),
                status = phase,
                title = s.str("title"),
                updatedAt = s.num("lastActivityAt"),
                out = out,
            )
        }
    }

    private fun handlePromiseSuccess(ev: ChannelEvent, out: MutableList<Observation>) {
        if (ev.service != "zcode-task" || ev.method != "getTaskMeta") return
        val result = (ev.arg as? IpcValue.JsonV)?.parsed as? JsonValue.Obj ?: return
        val taskId = result.str("taskId") ?: return
        emitObservation(
            seq = ev.messageSeq.takeIf { it >= 0 },
            stream = "meta|" + connectionId(),
            taskId = taskId,
            workspace = result.str("workspacePath"),
            status = result.str("status"),
            title = result.str("title"),
            updatedAt = result.num("updatedAt"),
            // 回执里也带 traceId（实测有）；必须与索引流用同一个 runId 身份，
            // 否则同一轮运行经两条路径到达会被当成两轮运行而重复通知。
            traceId = result.str("traceId"),
            out = out,
        )
    }

    private fun emitTask(task: JsonValue.Obj, seq: Long?, stream: String, out: MutableList<Observation>) {
        val address = task.obj("address")
        val meta = task.obj("meta")
        val activity = task.obj("activity")
        val taskId = address?.str("taskId") ?: meta?.str("taskId") ?: return
        // 【2026-10-25 实抓】交互身份在 meta.pendingInteraction，是单个对象：
        //   {"interactionId":"perm_...","kind":"userInput","toolName":"AskUserQuestion"}
        // 出现提问时 liveStatus 同时变为 waiting；回答后该字段与计数一起消失。
        // meta.pendingInteractionSummary / activity.pendingInteractions 只是计数
        // （{"permissionCount":0,"userInputCount":1}），不含身份，不能当交互来源。
        val pendingInteraction = meta?.obj("pendingInteraction") ?: activity?.obj("pendingInteraction")
        emitObservation(
            seq = seq,
            stream = stream,
            taskId = taskId,
            workspace = address?.str("workspacePath") ?: meta?.str("workspacePath"),
            status = meta?.str("status"),
            phase = activity?.str("phase"),
            title = meta?.str("title"),
            updatedAt = meta?.num("updatedAt"),
            traceId = meta?.str("traceId"),
            pending = meta?.obj("pendingInteractionSummary") ?: activity?.obj("pendingInteractions"),
            pendingInteraction = pendingInteraction,
            out = out,
        )
    }

    private fun emitObservation(
        seq: Long?,
        stream: String,
        taskId: String,
        workspace: String?,
        status: String?,
        title: String?,
        updatedAt: Long?,
        traceId: String? = null,
        phase: String? = null,
        pending: JsonValue.Obj? = null,
        pendingInteraction: JsonValue.Obj? = null,
        out: MutableList<Observation>,
    ) {
        val addr = TaskAddress(connectionId(), workspace, taskId)
        // runId 必须是「一次运行」的稳定身份。实测同一任务的 traceId 在整轮运行内不变
        // （612 条增量共用一个），而 updatedAt 每条都在变；用 updatedAt 会把同一轮运行
        // 的多次终态更新当成多次运行，导致重复通知。
        val runId = traceId ?: updatedAt?.toString()
        // 实测：pendingInteraction 缺席时，同一 task 对象里 liveStatus 会是 running/completed，
        // 表示该任务当前确实没有待处理交互。把这个事实写成显式 0 计数交给 reducer 的
        // 完整快照路径处理（v2 规则 3），而不是另发一条「清除」观察——
        // 后者会与同帧的状态沿共享 seq，被重放去重误杀。
        val waiting = pendingInteraction != null
        val patch = TaskPatch(
            status = status,
            title = title?.take(80),
            pendingPermissionCount = if (waiting) pending?.num("permissionCount")?.toInt() else 0,
            pendingUserInputCount = if (waiting) pending?.num("userInputCount")?.toInt() else 0,
            pendingIds = pending?.arr("ids")?.items?.mapNotNull { (it as? JsonValue.Str)?.value },
        )
        val base = Observation(
            connectionId = connectionId(),
            epoch = epoch(),
            source = Source.PAGE_RPC,
            streamId = stream,
            seq = seq,
            observedAtMs = System.currentTimeMillis(),
            taskKey = addr,
            runId = runId,
            kind = ObsKind.SNAPSHOT,
            patch = patch,
        )
        out.add(base)
        // activity.phase 比 meta.status 更细（completedSuccess / completedInterrupted），
        // 两者任一落入终态即视为终态；只认 status 会漏掉 interrupted 分支。
        val effective = status ?: phase
        when {
            effective in TERMINAL_SUCCESS || phase in TERMINAL_SUCCESS ->
                out.add(base.copy(kind = ObsKind.TASK_COMPLETED))
            effective in TERMINAL_FAILURE || phase in TERMINAL_FAILURE ->
                out.add(base.copy(kind = ObsKind.TASK_FAILED))
            effective in RUNNING_STATUSES || phase == "running" ->
                out.add(base.copy(kind = ObsKind.TASK_RUNNING))
        }
        // 交互身份：出现 pendingInteraction 即一条请求；消失由上面的显式 0 计数表达。
        // 撤销必须按 interactionId 精确对应，不能按任务或计数批量撤（v2 规则 3/5）。
        val iid = pendingInteraction?.str("interactionId")?.takeIf { it.isNotBlank() }
        if (iid != null) {
            out.add(
                base.copy(
                    kind = ObsKind.INTERACTION_REQUESTED,
                    interactionId = iid,
                    interactionKind = interactionKindOf(pendingInteraction.str("kind")),
                    // 交互身份来自协议字段而非 DOM 推断，属于已核实事实
                    confidence = Confidence.VERIFIED,
                )
            )
        }
    }

    /**
     * 实测 kind 取值：`userInput`（Agent 提问）。审批（permission）在用户全授权时不会出现，
     * 其字面值未在本轮样本中验证过，因此这里只映射已见值，未知值按 CONFIRM 兜底但保留原始语义。
     */
    private fun interactionKindOf(kind: String?): InteractionKind = when (kind) {
        "userInput", "elicitation" -> InteractionKind.USER_INPUT
        "permission" -> InteractionKind.PERMISSION
        else -> InteractionKind.CONFIRM
    }

    private fun streamOf(root: JsonValue.Obj): String =
        "ti|" + (root.str("subscriptionId") ?: "") + "|" + (root.str("logEpoch") ?: "")

    fun domCandidate(candidate: String, taskHint: String?, title: String?, now: Long = System.currentTimeMillis()): Observation? {
        val task = taskHint?.takeIf { it.isNotBlank() } ?: return null
        val addr = TaskAddress(connectionId(), null, task)
        return when (candidate) {
            "confirm-visible" -> Observation(
                connectionId = connectionId(),
                epoch = epoch(),
                source = Source.DOM,
                observedAtMs = now,
                taskKey = addr,
                interactionId = "dom:$task:${title?.let { hashOf(it) } ?: "static"}",
                interactionKind = InteractionKind.CONFIRM,
                kind = ObsKind.INTERACTION_REQUESTED,
                patch = TaskPatch(title = title),
                confidence = Confidence.CANDIDATE,
            )
            else -> null
        }
    }

    private fun hashOf(s: String): String = s.fold(0x811c9dc5.toInt()) { acc, c -> ((acc * 0x01000193) xor c.code) }.toString(16)

    private companion object {
        /** 实测 meta.status 终态值：completed；activity.phase 另见 completedSuccess / completedInterrupted。 */
        val TERMINAL_SUCCESS = setOf("completed", "completedSuccess", "completedInterrupted")
        val TERMINAL_FAILURE = setOf("error", "failed")
        val RUNNING_STATUSES = setOf("running", "executing", "repairing", "queued")
    }
}
