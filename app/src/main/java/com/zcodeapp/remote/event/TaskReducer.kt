package com.zcodeapp.remote.event

/**
 * 唯一任务/交互状态真源（v2 D3）。规则：
 * 1) streamId+seq 同物理流去重（重放/乱序丢弃）；
 * 2) 首个快照只建基线：终态不通知，仍 pending 的交互各提醒一次；
 * 3) null 字段不清状态；迟到 running 不回滚终态；
 * 4) 终态通知只在「非终态→终态」合法沿产生，同 run 不重复，新 run 重新武装；
 * 5) resolved 写墓碑，迟到的同 id request 不复活；
 * 6) DOM 低置信候选：终态只发一次「可能已完成」，无 taskKey 的交互候选不通知。
 */
class TaskReducer {
    private data class InteractionState(
        val kind: InteractionKind,
        val notified: Boolean,
        val seq: Long?,
    )

    private data class TaskState(
        var status: String? = null,
        var terminalNotifiedRun: String? = null,
        /** 本轮运行是否已就终态发过通知。只在合法 running 边沿重置，用于跨来源去重。 */
        var terminalNotified: Boolean = false,
        /**
         * 每条物理流各自的最后接受 seq。不同流（controller/tasks-index 的 toSeq 约 3.2 万、
         * sessions-index 的 toSeq 约 6.8 千）序号空间不互通，不能混在一个计数器里比较。
         */
        val streamSeq: HashMap<String, Long> = HashMap(),
        /** 每条流上终态被接受时的 seq，用于判断 running 是「迟到」还是「新的一轮」。 */
        val terminalSeq: HashMap<String, Long> = HashMap(),
        var runningSeq: Long? = null,
        var lowConfidenceNotifiedRun: String? = null,
        val pending: LinkedHashMap<String, InteractionState> = LinkedHashMap(),
        val resolvedTombstones: MutableSet<String> = HashSet(),
        var title: String? = null,
        var baselined: Boolean = false,
    )

    private data class ConnState(
        val tasks: LinkedHashMap<String, TaskState> = LinkedHashMap(),
        val streams: HashMap<String, Long> = HashMap(),
        var baselineEpoch: Long = -1,
    )

    private val connections = HashMap<String, ConnState>()

    fun reset(connectionId: String) {
        connections.remove(connectionId)
    }

    fun reduce(obs: Observation): List<NotificationIntent> {
        val conn = connections.getOrPut(obs.connectionId) { ConnState() }

        // 规则 1：物理流去重，按 (stream, task, kind) 记 lastSeq。
        // 关键：一帧会派生多条观察（SNAPSHOT + 状态沿 + 交互请求），它们共享同一个 seq。
        // 若只按 (stream, task) 记 lastSeq，同帧的第二条观察就会被当成重放丢掉——
        // 实测表现为「提问出现的那一帧，任务状态更新了，但提问通知不产生」。
        // 因此去重键必须带上 kind：同 kind 的重复才是重放，跨 kind 是同一帧的不同事实。
        if (obs.kind != ObsKind.SNAPSHOT && obs.streamId != null && obs.seq != null && obs.taskKey != null) {
            val streamKey = obs.streamId + "|" + obs.taskKey!!.key + "|" + obs.kind.name
            val last = conn.streams[streamKey]
            if (last != null && obs.seq!! <= last) return emptyList()
            conn.streams[streamKey] = obs.seq!!
        }

        val out = ArrayList<NotificationIntent>()

        when (obs.kind) {
            ObsKind.SNAPSHOT -> {
                if (conn.baselineEpoch != obs.epoch) {
                    // 新 epoch 的首个快照：全部任务重建基线，历史终态不通知
                    conn.baselineEpoch = obs.epoch
                }
                applySnapshot(conn, obs, out)
            }
            ObsKind.SNAPSHOT_PENDING_CLEAR -> {
                val task = obs.taskKey?.let { task(conn, it) } ?: return emptyList()
                if (task.baselined) {
                    resolveAllPending(obs.connectionId, task, obs.taskKey!!, out)
                }
            }
            ObsKind.TASK_RUNNING -> {
                val addr = obs.taskKey ?: return emptyList()
                val task = task(conn, addr)
                val stamped = obs.seq
                val stream = obs.streamId
                if (stamped != null && stream != null) {
                    val last = task.streamSeq[stream]
                    // 同流重放/乱序：seq 不大于该流已接受的最大值即丢弃。
                    if (last != null && stamped <= last) return emptyList()
                    // 该流上终态之后才到达的 running 是迟到帧，不能把终态拉回 running。
                    val tSeq = task.terminalSeq[stream]
                    if (tSeq != null && stamped < tSeq) return emptyList()
                    task.streamSeq[stream] = stamped
                }
                // run 身份取「本轮运行第一次进入 running 的 seq」，只在状态边沿更新。
                // 运行期间每帧都会带 running 增量（实测 612 帧同一任务），逐帧刷新会让
                // 终态的 runKey 每次都变，导致同一轮运行重复通知。
                if (task.status != "running") {
                    task.runningSeq = stamped ?: task.runningSeq
                    // 合法的「非 running → running」边沿即新的一轮运行：重新武装终态通知。
                    // 迟到 running 帧已被上面的流内 seq 检查丢弃，不会误清零。
                    task.terminalNotified = false
                }
                task.status = "running"
                obs.patch.title?.let { task.title = it }
                task.baselined = true
            }
            ObsKind.TASK_COMPLETED, ObsKind.TASK_FAILED -> {
                val addr = obs.taskKey ?: return emptyList()
                val task = task(conn, addr)
                obs.patch.title?.let { task.title = it }
                val failed = obs.kind == ObsKind.TASK_FAILED
                // runKey 优先用协议给的 runId（实测 traceId 在一轮运行内稳定），
                // 缺失时退回本轮 running 起点 seq。
                val runKey = obs.runId ?: task.runningSeq?.toString() ?: (obs.seq ?: 0L).toString()
                val stamped = obs.seq
                val stream = obs.streamId
                if (stamped != null && stream != null) {
                    val last = task.streamSeq[stream]
                    if (last != null && stamped < last) return emptyList()
                    task.streamSeq[stream] = stamped
                    task.terminalSeq[stream] = stamped
                }
                if (obs.confidence == Confidence.CANDIDATE) {
                    // 规则 6：DOM 低置信终态只提醒一次
                    if (task.lowConfidenceNotifiedRun != runKey) {
                        task.lowConfidenceNotifiedRun = runKey
                        out.add(
                            NotificationIntent.Attention(
                                connectionId = obs.connectionId,
                                taskKey = addr,
                                interactionKey = "lowconf:$runKey",
                                kind = InteractionKind.CONFIRM,
                                title = task.title,
                                seq = obs.seq,
                                lowConfidence = true,
                            )
                        )
                    }
                    return out
                }
                if (!task.baselined) {
                    // 首见即终态（无基线）：视为基线，不通知
                    task.baselined = true
                    task.status = if (failed) "error" else "completed"
                    task.terminalNotifiedRun = runKey
                    task.terminalNotified = true
                    return emptyList()
                }
                // 同一轮运行只通知一次。不能只比 runKey：实测同一任务会从
                // controller/tasks-index（traceId）与 sessions-index/getTaskMeta（只有
                // updatedAt/lastActivityAt）两条流到达，runKey 天然不同却属于同一次运行。
                // terminalNotified 只在合法 running 边沿清零，因此新 run 仍能再通知。
                if (task.terminalNotified) return emptyList()
                if (task.terminalNotifiedRun == runKey) return emptyList() // 同 run 不重复
                task.status = if (failed) "error" else "completed"
                task.terminalNotifiedRun = runKey
                task.terminalNotified = true
                out.add(
                    NotificationIntent.TaskTerminal(
                        connectionId = obs.connectionId,
                        taskKey = addr,
                        runKey = runKey,
                        failed = failed,
                        title = task.title,
                        seq = obs.seq,
                    )
                )
            }
            ObsKind.INTERACTION_REQUESTED -> {
                val addr = obs.taskKey ?: return emptyList() // 规则 6：无身份不通知
                val task = task(conn, addr)
                obs.patch.title?.let { task.title = it }
                val iid = obs.interactionId ?: return emptyList()
                if (iid in task.resolvedTombstones) return emptyList() // 规则 5
                if (task.pending.containsKey(iid)) {
                    // 已 pending：刷新 kind/seq 但不重复通知
                    task.pending[iid] = task.pending[iid]!!.copy(seq = obs.seq ?: task.pending[iid]!!.seq)
                    return emptyList()
                }
                if (!task.baselined) task.baselined = true
                task.pending[iid] = InteractionState(
                    kind = obs.interactionKind ?: InteractionKind.CONFIRM,
                    notified = true,
                    seq = obs.seq,
                )
                out.add(
                    NotificationIntent.Attention(
                        connectionId = obs.connectionId,
                        taskKey = addr,
                        interactionKey = iid,
                        kind = obs.interactionKind ?: InteractionKind.CONFIRM,
                        title = task.title,
                        seq = obs.seq,
                        lowConfidence = obs.confidence == Confidence.CANDIDATE,
                    )
                )
            }
            ObsKind.INTERACTION_RESOLVED -> {
                val addr = obs.taskKey ?: return emptyList()
                val task = task(conn, addr)
                val iid = obs.interactionId ?: return emptyList()
                if (task.pending.remove(iid) != null || iid !in task.resolvedTombstones) {
                    task.resolvedTombstones.add(iid)
                    out.add(
                        NotificationIntent.ResolveAttention(
                            connectionId = obs.connectionId,
                            taskKey = addr,
                            interactionKey = iid,
                        )
                    )
                } else {
                    task.resolvedTombstones.add(iid)
                }
            }
        }
        return out
    }

    private fun applySnapshot(conn: ConnState, obs: Observation, out: MutableList<NotificationIntent>) {
        val addr = obs.taskKey ?: return
        val task = task(conn, addr)
        obs.patch.title?.let { task.title = it }
        val firstBaseline = !task.baselined
        task.baselined = true

        val pendingIds = obs.patch.pendingIds
        if (pendingIds != null) {
            // 快照给出实例列表：补齐新交互、撤掉消失的交互
            val seen = HashSet<String>()
            for (iid in pendingIds) {
                seen.add(iid)
                if (iid in task.resolvedTombstones) continue
                if (!task.pending.containsKey(iid)) {
                    task.pending[iid] = InteractionState(InteractionKind.CONFIRM, notified = true, seq = obs.seq)
                    if (!firstBaseline) {
                        out.add(
                            NotificationIntent.Attention(
                                connectionId = obs.connectionId,
                                taskKey = addr,
                                interactionKey = iid,
                                kind = InteractionKind.CONFIRM,
                                title = task.title,
                                seq = obs.seq,
                            )
                        )
                    }
                }
            }
            val iter = task.pending.entries.iterator()
            while (iter.hasNext()) {
                val e = iter.next()
                if (e.key !in seen) {
                    iter.remove()
                    task.resolvedTombstones.add(e.key)
                    if (!firstBaseline) {
                        out.add(
                            NotificationIntent.ResolveAttention(
                                connectionId = obs.connectionId,
                                taskKey = addr,
                                interactionKey = e.key,
                            )
                        )
                    }
                }
            }
        } else {
            val pc = (obs.patch.pendingPermissionCount ?: 0) + (obs.patch.pendingUserInputCount ?: 0)
            if (pc == 0 && (obs.patch.pendingPermissionCount != null || obs.patch.pendingUserInputCount != null)) {
                // 完整可信快照 pending=0：撤全部已知 pending（v2 规则 3）
                if (!firstBaseline) resolveAllPending(obs.connectionId, task, addr, out)
            }
            // 计数非零但无实例列表：不做增删（不按计数猜测）
        }

        obs.patch.status?.let { status ->
            if (firstBaseline) {
                task.status = status
                val stream = obs.streamId
                if (obs.seq != null && stream != null) {
                    task.streamSeq[stream] = obs.seq
                }
                if (isTerminalStatus(status)) {
                    // 基线即终态：吸收同一 run 的后续终态事件，防止首开 App 通知历史。
                    // 必须按语义集合判断，不能只比字面量 "completed"——真实数据里
                    // 会话快照用的是 completedSuccess / completedInterrupted。
                    task.runningSeq = obs.seq ?: task.runningSeq
                    task.terminalNotifiedRun = obs.runId ?: obs.seq?.toString() ?: "baseline"
                    // 同一事实经另一条流再来（实测 sessions-index 快照 + getTaskMeta 回执）
                    // 也不能通知；terminalNotified 必须在基线处就置位。
                    task.terminalNotified = true
                    if (obs.seq != null && stream != null) {
                        task.terminalSeq[stream] = obs.seq
                    }
                }
            }
        }
    }

    private fun isTerminalStatus(status: String): Boolean =
        status in TERMINAL_STATUSES

    private fun resolveAllPending(connectionId: String, task: TaskState, addr: TaskAddress, out: MutableList<NotificationIntent>) {
        val iter = task.pending.entries.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            iter.remove()
            task.resolvedTombstones.add(e.key)
            out.add(
                NotificationIntent.ResolveAttention(
                    connectionId = connectionId,
                    taskKey = addr,
                    interactionKey = e.key,
                )
            )
        }
    }

    private fun task(conn: ConnState, addr: TaskAddress): TaskState =
        conn.tasks.getOrPut(addr.key) { TaskState() }

    fun pendingCount(connectionId: String, taskKey: TaskAddress): Int =
        connections[connectionId]?.tasks?.get(taskKey.key)?.pending?.size ?: 0

    private companion object {
        /** 实测终态值：meta.status 为 completed；activity.phase 另见 completedSuccess / completedInterrupted。 */
        val TERMINAL_STATUSES = setOf(
            "completed", "completedSuccess", "completedInterrupted", "error", "failed",
        )
    }
}
