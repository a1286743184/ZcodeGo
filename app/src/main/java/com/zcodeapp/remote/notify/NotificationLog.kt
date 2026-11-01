package com.zcodeapp.remote.notify

import com.zcodeapp.remote.event.InteractionKind
import com.zcodeapp.remote.event.NotificationIntent
import com.zcodeapp.remote.event.TaskAddress

/**
 * 通知信箱事务语义（v2 7.1/7.2）：唯一键去重、交互实例级撤回、resolved 墓碑防复活、
 * 崩溃恢复只续投未确认记录。InMemory 与 SQLite 共享同一套裁决逻辑。
 */
data class RouteTarget(val url: String, val taskId: String?, val title: String?)

sealed class DeliveryOp {
    data class PostTask(
        val eventKey: String,
        val failed: Boolean,
        val title: String?,
        val route: RouteTarget?,
    ) : DeliveryOp()

    data class PostAttention(
        val eventKey: String,
        val kind: InteractionKind,
        val title: String?,
        val lowConfidence: Boolean,
        val pendingCount: Int,
        val route: RouteTarget?,
    ) : DeliveryOp()

    data class CancelAttention(val eventKey: String) : DeliveryOp()
    data class PostSummary(val taskKey: String, val pendingCount: Int, val route: RouteTarget?) : DeliveryOp()
    data class CancelSummary(val taskKey: String) : DeliveryOp()
}

data class EventRow(
    val eventKey: String,
    val kind: String, // TASK | ATTENTION
    val connectionId: String,
    val taskKey: String,
    val interactionKey: String?,
    val failed: Boolean,
    val kindName: String?,
    val title: String?,
    val lowConfidence: Boolean,
    val routeUrl: String?,
    val routeTaskId: String?,
    val state: String, // PENDING | PROCESSING | DELIVERED | BLOCKED
    val leaseUntil: Long,
    val createdAt: Long,
)

interface NotificationLog {
    fun apply(
        intents: List<NotificationIntent>,
        nowMs: Long,
        routeFor: (TaskAddress) -> RouteTarget?,
    ): List<DeliveryOp>

    fun recover(nowMs: Long): List<DeliveryOp>
    fun markDelivered(eventKeys: List<String>, nowMs: Long)
    fun markBlocked(eventKeys: List<String>, nowMs: Long)
}

abstract class BaseNotificationLog : NotificationLog {
    protected abstract fun findEvent(eventKey: String): EventRow?
    protected abstract fun insertEvent(row: EventRow): Boolean
    protected abstract fun updateEventState(eventKey: String, state: String, leaseUntil: Long, nowMs: Long)
    protected abstract fun findAttention(connectionId: String, taskKey: String, interactionKey: String): String?
    protected abstract fun upsertAttention(connectionId: String, taskKey: String, interactionKey: String, state: String)
    protected abstract fun pendingInteractions(connectionId: String, taskKey: String): List<String>
    protected abstract fun expiredProcessing(nowMs: Long): List<EventRow>

    override fun apply(
        intents: List<NotificationIntent>,
        nowMs: Long,
        routeFor: (TaskAddress) -> RouteTarget?,
    ): List<DeliveryOp> {
        val ops = ArrayList<DeliveryOp>()
        for (intent in intents) {
            when (intent) {
                is NotificationIntent.TaskTerminal -> {
                    val key = "term|${intent.connectionId}|${intent.taskKey.key}|${intent.runKey}|${intent.failed}"
                    if (findEvent(key) == null) {
                        insertEvent(
                            EventRow(
                                eventKey = key, kind = "TASK",
                                connectionId = intent.connectionId, taskKey = intent.taskKey.key,
                                interactionKey = null, failed = intent.failed,
                                kindName = null, title = intent.title, lowConfidence = false,
                                routeUrl = routeFor(intent.taskKey)?.url,
                                routeTaskId = routeFor(intent.taskKey)?.taskId,
                                state = "PROCESSING", leaseUntil = nowMs + LEASE_MS, createdAt = nowMs,
                            )
                        )
                        ops.add(DeliveryOp.PostTask(key, intent.failed, intent.title, routeFor(intent.taskKey)))
                    }
                }
                is NotificationIntent.Attention -> {
                    val key = "att|${intent.connectionId}|${intent.taskKey.key}|${intent.interactionKey}"
                    val existingState = findAttention(intent.connectionId, intent.taskKey.key, intent.interactionKey)
                    if (existingState == "RESOLVED") continue // 墓碑：迟到请求不复活
                    if (existingState == "PENDING") continue  // 已通知
                    if (findEvent(key) == null) {
                        upsertAttention(intent.connectionId, intent.taskKey.key, intent.interactionKey, "PENDING")
                        insertEvent(
                            EventRow(
                                eventKey = key, kind = "ATTENTION",
                                connectionId = intent.connectionId, taskKey = intent.taskKey.key,
                                interactionKey = intent.interactionKey, failed = false,
                                kindName = intent.kind.name, title = intent.title,
                                lowConfidence = intent.lowConfidence,
                                routeUrl = routeFor(intent.taskKey)?.url,
                                routeTaskId = routeFor(intent.taskKey)?.taskId,
                                state = "PROCESSING", leaseUntil = nowMs + LEASE_MS, createdAt = nowMs,
                            )
                        )
                        val count = pendingInteractions(intent.connectionId, intent.taskKey.key).size
                        ops.add(
                            DeliveryOp.PostAttention(
                                key, intent.kind, intent.title, intent.lowConfidence, count,
                                routeFor(intent.taskKey),
                            )
                        )
                        if (count > 1) {
                            ops.add(DeliveryOp.PostSummary(intent.taskKey.key, count, routeFor(intent.taskKey)))
                        }
                    }
                }
                is NotificationIntent.ResolveAttention -> {
                    val state = findAttention(intent.connectionId, intent.taskKey.key, intent.interactionKey)
                    upsertAttention(intent.connectionId, intent.taskKey.key, intent.interactionKey, "RESOLVED")
                    if (state == "PENDING") {
                        val key = "att|${intent.connectionId}|${intent.taskKey.key}|${intent.interactionKey}"
                        ops.add(DeliveryOp.CancelAttention(key))
                        val remaining = pendingInteractions(intent.connectionId, intent.taskKey.key).size
                        if (remaining > 1) {
                            ops.add(DeliveryOp.PostSummary(intent.taskKey.key, remaining, null))
                        } else {
                            ops.add(DeliveryOp.CancelSummary(intent.taskKey.key))
                        }
                    }
                }
            }
        }
        return ops
    }

    override fun recover(nowMs: Long): List<DeliveryOp> {
        val ops = ArrayList<DeliveryOp>()
        for (row in expiredProcessing(nowMs)) {
            updateEventState(row.eventKey, "PROCESSING", nowMs + LEASE_MS, nowMs)
            val route = row.routeUrl?.let { RouteTarget(it, row.routeTaskId, row.title) }
            ops.add(
                if (row.kind == "TASK") {
                    DeliveryOp.PostTask(row.eventKey, row.failed, row.title, route)
                } else {
                    DeliveryOp.PostAttention(
                        row.eventKey,
                        kindOf(row.kindName),
                        row.title,
                        row.lowConfidence,
                        1,
                        route,
                    )
                }
            )
        }
        return ops
    }

    override fun markDelivered(eventKeys: List<String>, nowMs: Long) {
        for (k in eventKeys) updateEventState(k, "DELIVERED", 0, nowMs)
    }

    override fun markBlocked(eventKeys: List<String>, nowMs: Long) {
        for (k in eventKeys) updateEventState(k, "BLOCKED", 0, nowMs)
    }

    private fun kindOf(name: String?): InteractionKind =
        InteractionKind.entries.firstOrNull { it.name == name } ?: InteractionKind.CONFIRM

    companion object {
        const val LEASE_MS = 60_000L
    }
}

/** 纯内存实现：单测与预览用。 */
class InMemoryNotificationLog : BaseNotificationLog() {
    private val events = HashMap<String, EventRow>()
    private val attention = HashMap<String, String>() // conn|task|interaction -> state

    override fun findEvent(eventKey: String): EventRow? = events[eventKey]
    override fun insertEvent(row: EventRow): Boolean {
        if (events.containsKey(row.eventKey)) return false
        events[row.eventKey] = row
        return true
    }

    override fun updateEventState(eventKey: String, state: String, leaseUntil: Long, nowMs: Long) {
        events[eventKey]?.let { events[eventKey] = it.copy(state = state, leaseUntil = leaseUntil) }
    }

    override fun findAttention(connectionId: String, taskKey: String, interactionKey: String): String? =
        attention["$connectionId|$taskKey|$interactionKey"]

    override fun upsertAttention(connectionId: String, taskKey: String, interactionKey: String, state: String) {
        attention["$connectionId|$taskKey|$interactionKey"] = state
    }

    override fun pendingInteractions(connectionId: String, taskKey: String): List<String> =
        attention.filterKeys { it.startsWith("$connectionId|$taskKey|") && it.substringAfterLast('|') != "" }
            .filterValues { it == "PENDING" }.keys.map { it.substringAfterLast('|') }

    override fun expiredProcessing(nowMs: Long): List<EventRow> =
        events.values.filter { it.state == "PROCESSING" && it.leaseUntil < nowMs }
}
