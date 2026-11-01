package com.zcodeapp.remote.event

/**
 * 统一观察模型（v2 D3）：所有来源（页面 RPC / DOM / 未来原生 Relay）先归一为 Observation，
 * 再进入唯一 TaskReducer。身份字段不全的观察只能作为低置信候选。
 */
enum class Source { PAGE_RPC, PAGE_EVENT, DOM, NATIVE_RPC }
enum class Confidence { VERIFIED, CANDIDATE }

data class TaskAddress(
    val connectionId: String,
    val workspaceId: String?,
    val taskId: String,
) {
    val key: String get() = "$connectionId|${workspaceId ?: "-"}|$taskId"
}

enum class ObsKind {
    /** 完整任务索引快照（建立基线） */
    SNAPSHOT,
    TASK_RUNNING,
    TASK_COMPLETED,
    TASK_FAILED,
    INTERACTION_REQUESTED,
    INTERACTION_RESOLVED,
    /** 任务级 pending 归零的完整可信快照信号 */
    SNAPSHOT_PENDING_CLEAR,
}

enum class InteractionKind { PERMISSION, USER_INPUT, CONFIRM }

data class TaskPatch(
    val status: String? = null,
    val title: String? = null,
    val pendingPermissionCount: Int? = null,
    val pendingUserInputCount: Int? = null,
    val pendingIds: List<String>? = null,
)

data class Observation(
    val connectionId: String,
    val epoch: Long,
    val source: Source,
    val streamId: String? = null,
    val seq: Long? = null,
    val observedAtMs: Long = 0L,
    val taskKey: TaskAddress? = null,
    val interactionId: String? = null,
    val interactionKind: InteractionKind? = null,
    val runId: String? = null,
    val kind: ObsKind,
    val patch: TaskPatch = TaskPatch(),
    val confidence: Confidence = Confidence.VERIFIED,
)

/** reducer 输出的通知意图，交由 NotificationLog 在事务内裁决。 */
sealed class NotificationIntent {
    abstract val connectionId: String
    abstract val taskKey: TaskAddress

    data class TaskTerminal(
        override val connectionId: String,
        override val taskKey: TaskAddress,
        val runKey: String,
        val failed: Boolean,
        val title: String?,
        val seq: Long?,
    ) : NotificationIntent()

    data class Attention(
        override val connectionId: String,
        override val taskKey: TaskAddress,
        val interactionKey: String,
        val kind: InteractionKind,
        val title: String?,
        val seq: Long?,
        val lowConfidence: Boolean = false,
    ) : NotificationIntent()

    data class ResolveAttention(
        override val connectionId: String,
        override val taskKey: TaskAddress,
        val interactionKey: String,
    ) : NotificationIntent()
}
