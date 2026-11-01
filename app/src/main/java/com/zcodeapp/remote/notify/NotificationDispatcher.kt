package com.zcodeapp.remote.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.zcodeapp.remote.BuildConfig
import com.zcodeapp.remote.MainActivity
import com.zcodeapp.remote.R
import com.zcodeapp.remote.diag.DiagLog
import com.zcodeapp.remote.event.NotificationIntent
import com.zcodeapp.remote.event.TaskAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** 渠道定义（v2 D4）：keepalive LOW 常驻、task HIGH 一次、attention HIGH 可撤回。 */
object NotificationChannels {
    const val KEEP_ALIVE = "zrcode_keepalive"
    const val TASK = "zrcode_task"
    const val ATTENTION = "zrcode_attention"

    fun ensure(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(KEEP_ALIVE, context.getString(R.string.keep_alive_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.keep_alive_channel_desc)
                setShowBadge(false)
            }
        )
        // TASK / ATTENTION 用 IMPORTANCE_HIGH：这是「息屏也要冒出来」的前提。
        // 只有 HIGH 及以上才会在锁屏上以浮动形式显示（heads-up），LOW 只进通知栏。
        nm.createNotificationChannel(
            NotificationChannel(TASK, context.getString(R.string.task_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.task_channel_desc)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 180, 120, 180)
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(ATTENTION, context.getString(R.string.attention_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.attention_channel_desc)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 220, 140, 220)
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
        )
    }
}

/**
 * 串行派发器（v2 7.2）：单线程执行 DeliveryOp，回执写回信箱；
 * 通知权限被拒时记 BLOCKED，不伪称成功。
 */
class NotificationDispatcher(
    private val context: Context,
    private val log: NotificationLog,
) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "zrcode-notify") }

    fun submit(intents: List<NotificationIntent>, routeFor: (TaskAddress) -> RouteTarget?) {
        if (intents.isEmpty()) return
        executor.execute {
            val now = System.currentTimeMillis()
            val ops = log.apply(intents, now, routeFor)
            execute(ops, now)
        }
    }

    fun recover() {
        executor.execute {
            val now = System.currentTimeMillis()
            execute(log.recover(now), now)
        }
    }

    private fun execute(ops: List<DeliveryOp>, now: Long) {
        val nm = NotificationManagerCompat.from(context)
        val notificationsEnabled = nm.areNotificationsEnabled()
        // 【0.4.4 诊断】release 构建此前没有任何派发日志，真机上无法区分
        // 「息屏提醒没成功」到底是「事件根本没到」（页面冻结，见保活链路）
        // 还是「事件到了但没弹」（FSI 授权被更新重置 / 屏幕状态判定）。
        // 每次派发记一条：op 计数 + 通知开关 + 屏幕状态 + 全屏通知授权。
        // FSI 是否真挂上由后两者推导：屏幕息屏/锁屏 且 fsi=true 才挂。
        DiagLog.i(
            "notify.dispatch",
            "ops=${ops.size} postTask=${ops.count { it is DeliveryOp.PostTask }}" +
                " postAttn=${ops.count { it is DeliveryOp.PostAttention }}" +
                " enabled=$notificationsEnabled" +
                " screen=${AttentionActivity.shouldUseFullScreen(context)}" +
                " fsi=${AttentionActivity.canUseFullScreenIntent(context)}",
        )
        // debug 诊断：每次派发都记一行（只记类别/事件键哈希，不记正文），
        // 用于真机验证「提问是否到达」「撤回是否执行」，无需在 UI 上点按钮。
        if (BuildConfig.DEBUG) {
            android.util.Log.i(
                "ZcodeRemoteDiag",
                "dispatch ops=" + ops.map {
                    when (it) {
                        is DeliveryOp.PostTask -> "PostTask(${stableId(it.eventKey)})"
                        is DeliveryOp.PostAttention -> "PostAttention(${stableId(it.eventKey)})"
                        is DeliveryOp.CancelAttention -> "CancelAttention(${stableId(it.eventKey)})"
                        is DeliveryOp.PostSummary -> "PostSummary"
                        is DeliveryOp.CancelSummary -> "CancelSummary"
                    }
                } + " enabled=$notificationsEnabled" + " t=" + System.currentTimeMillis()
            )
        }
        val delivered = ArrayList<String>()
        for (op in ops) {
            when (op) {
                is DeliveryOp.PostTask -> {
                    val id = stableId(op.eventKey)
                    if (notificationsEnabled) {
                        nm.notify(
                            id,
                            buildTaskNotification(
                                failed = op.failed,
                                title = op.title,
                                route = op.route,
                                attentionKind = if (op.failed) AttentionActivity.KIND_FAILED
                                else AttentionActivity.KIND_COMPLETED,
                            ),
                        )
                    }
                    delivered.add(op.eventKey)
                }
                is DeliveryOp.PostAttention -> {
                    val id = stableId(op.eventKey)
                    if (notificationsEnabled) {
                        nm.notify(
                            id,
                            buildAttentionNotification(
                                title = op.title,
                                lowConfidence = op.lowConfidence,
                                pendingCount = op.pendingCount,
                                route = op.route,
                                attentionKind = AttentionActivity.KIND_QUESTION,
                            ),
                        )
                    }
                    delivered.add(op.eventKey)
                }
                is DeliveryOp.CancelAttention -> nm.cancel(stableId(op.eventKey))
                is DeliveryOp.PostSummary -> {
                    if (notificationsEnabled) {
                        nm.notify(stableId("sum|" + op.taskKey), buildSummaryNotification(op.pendingCount, op.route))
                    }
                }
                is DeliveryOp.CancelSummary -> nm.cancel(stableId("sum|" + op.taskKey))
            }
        }
        if (notificationsEnabled) {
            log.markDelivered(delivered, System.currentTimeMillis())
        } else {
            log.markBlocked(delivered, System.currentTimeMillis())
        }
    }

    private fun buildTaskNotification(
        failed: Boolean,
        title: String?,
        route: RouteTarget?,
        attentionKind: String,
    ) =
        NotificationCompat.Builder(context, NotificationChannels.TASK)
            .setSmallIcon(R.drawable.ic_stat_remote_control)
            .setContentTitle(
                context.getString(if (failed) R.string.notify_task_failed else R.string.notify_task_completed)
            )
            .setContentText(title?.let { clip(it) } ?: "")
            .setAutoCancel(true)
            .setContentIntent(routeIntent(route))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .applyFullScreen(attentionKind, title, null, route)
            .build()

    private fun buildAttentionNotification(
        title: String?,
        lowConfidence: Boolean,
        pendingCount: Int,
        route: RouteTarget?,
        attentionKind: String,
    ) =
        NotificationCompat.Builder(context, NotificationChannels.ATTENTION)
            .setSmallIcon(R.drawable.ic_stat_remote_control)
            .setContentTitle(
                context.getString(
                    if (lowConfidence) R.string.notify_task_completed else R.string.notify_attention
                )
            )
            .setContentText(
                buildString {
                    title?.let { append(clip(it)) }
                    if (pendingCount > 1) {
                        if (isNotEmpty()) append(" · ")
                        append(context.getString(R.string.notify_attention_summary, pendingCount))
                    }
                }
            )
            .setAutoCancel(!lowConfidence)
            .setOngoing(false)
            .setContentIntent(routeIntent(route))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .applyFullScreen(
                attentionKind,
                title,
                context.getString(R.string.notify_attention_summary, pendingCount),
                route,
            )
            .build()

    /**
     * 息屏/锁屏时把提醒界面挂成 fullScreenIntent。
     *
     * 【2026-10-27 关键修正】旧实现是从派发线程直接 `startActivity(AttentionActivity)`。
     * 那条路在 Android 10+ 被 BAL（后台启动 Activity）拦住，「前台服务在跑」不在豁免名单里，
     * 真机日志里 `attention.show` 一条都没有——这个功能写了但从来没跑起来过。
     *
     * 挂 fullScreenIntent 是系统认可的做法：锁屏时由系统替我们拉起界面，绕开 BAL；
     * 用户正在用手机时系统只显示 heads-up，不打断他。这正是我们要的语义，
     * 也省掉了自己判断屏幕状态那一层。
     *
     * 三种情况不挂，避免整条通知被 ROM 丢弃（比降级成 heads-up 更糟）：
     * 1. 屏幕亮着且没锁屏：用户正在用，heads-up 够了；
     * 2. 用户没授予「全屏通知」权限（Android 14+）；
     * 3. 构造 PendingIntent 失败。
     */
    private fun NotificationCompat.Builder.applyFullScreen(
        kind: String,
        title: String?,
        body: String?,
        route: RouteTarget?,
    ): NotificationCompat.Builder {
        if (!AttentionActivity.shouldUseFullScreen(context)) return this
        if (!AttentionActivity.canUseFullScreenIntent(context)) return this
        val pi = runCatching {
            PendingIntent.getActivity(
                context,
                stableId("fsi|$kind|${route?.taskId.orEmpty()}"),
                AttentionActivity.intentFor(context, kind, title, body, route?.url, route?.taskId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }.getOrNull() ?: return this
        return setFullScreenIntent(pi, true)
    }

    private fun buildSummaryNotification(pendingCount: Int, route: RouteTarget?) =
        NotificationCompat.Builder(context, NotificationChannels.ATTENTION)
            .setSmallIcon(R.drawable.ic_stat_remote_control)
            .setContentTitle(context.getString(R.string.notify_attention))
            .setContentText(context.getString(R.string.notify_attention_summary, pendingCount))
            .setAutoCancel(true)
            .setContentIntent(routeIntent(route))
            .build()

    private fun routeIntent(route: RouteTarget?): PendingIntent? {
        route ?: return mainIntent()
        val routeId = RouteRegistry.register(route)
        val intent = Intent(context, RouteActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("zcodeapp://route/$routeId")
        }
        return PendingIntent.getActivity(
            context,
            stableId(routeId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun mainIntent(): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun clip(s: String): String = if (s.length > 60) s.take(60) + "…" else s

    private fun stableId(key: String): Int = key.hashCode()
}
