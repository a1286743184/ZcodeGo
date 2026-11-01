package com.zcodeapp.remote.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.zcodeapp.remote.BuildConfig
import com.zcodeapp.remote.MainActivity
import com.zcodeapp.remote.R
import com.zcodeapp.remote.ZcodeRemoteApp
import com.zcodeapp.remote.diag.DiagLog
import com.zcodeapp.remote.notify.NotificationChannels

/**
 * 常驻保活服务。
 *
 * 目标：用户息屏、切走、甚至锁屏之后，远控会话仍然活着，任务完成/失败/提问
 * 的通知能立刻送达并点亮屏幕。
 *
 * 与旧版 KeepAliveService 的区别：旧版是「用户手动开、10 分钟倒计时」的短时服务，
 * 且被 SettingsStore.l1Available 恒为 false 整个禁掉，等于不存在。
 * 这一版是**常驻**的：开启后一直跑，直到用户主动关闭或系统强杀（被杀后由
 * KeepAliveRestartReceiver 在下次开机/升级时拉回）。
 *
 * 分工（分层策略）：
 * - 本服务：载体 + 提高进程优先级，让 WebView 不被回收、WS 不断。
 * - **PARTIAL_WAKE_LOCK（息屏时）**：这是「息屏还能收到通知」的物理前提。
 *   屏幕一关，SoC 就进 suspend，页面 JS 定时器与 WS 心跳全停，服务端等不到心跳
 *   就把连接判死；亮屏后页面只能重连。光保进程不保 CPU，等于什么都没保。
 * - 通知渠道：真正让息屏亮起的是 HIGH 渠道的通知（见 NotificationDispatcher）。
 * - 开机自启：BootReceiver 重拉本服务，不需要用户再点一次。
 * - 电池优化白名单：用户可一键申请，减少 Doze 期间被冻结的概率。
 *
 * WakeLock 只在**屏幕关闭期间**持有：用户正在用手机时不需要它，
 * 那时候白白耗电没有意义。屏幕亮起立刻释放。
 */
class KeepAliveService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    /** 息屏期间持有的 CPU 锁：让 JS 心跳继续跑。 */
    private var cpuLock: PowerManager.WakeLock? = null

    /** 监听屏幕开关，按需获取/释放 CPU 锁。 */
    private var screenReceiver: BroadcastReceiver? = null

    /** 心跳：更新常驻通知上的「已保活时长」，并顺带触发后台健康巡检。 */
    private val heartbeat = object : Runnable {
        override fun run() {
            updateNotification()
            // 巡检页面连接是否真的还活着。息屏后页面可能已经静默死亡
            // （CPU 冻结导致心跳停摆，或渲染进程被 ROM 回收），
            // 这里是我们唯一能发现并兜底恢复的时机。
            runCatching {
                (application as? ZcodeRemoteApp)?.sessionController?.checkBackgroundHealth()
            }
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    /**
     * 原生代发心跳：按页面原本的 10 秒节奏，替被冻结的页面把心跳发出去。
     *
     * 为什么单独一条定时链而不是并进 [heartbeat]：
     * 两者的周期不同且不能互相拖累——巡检是 30 秒、代发是 10 秒；
     * 巡检里会做 `evaluateJavascript` 探测，若串在一起，一次慢探测就会
     * 把下一次代发心跳推后，心跳节奏一乱服务端照样判死。
     *
     * 这个定时链**无条件跑**（保活服务活着就说明用户开了保活），
     * 由控制器自己判断当前是否该代发（见 `nativeHeartbeatTick`）。
     */
    private val nativeBeat = object : Runnable {
        override fun run() {
            runCatching {
                (application as? ZcodeRemoteApp)?.sessionController?.nativeHeartbeatTick()
            }
            handler.postDelayed(this, NATIVE_BEAT_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                DiagLog.i("keepalive.stop", "user")
                KeepAliveState.setEnabled(this, false)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                ensureChannel()
                startAsForeground()
                KeepAliveState.setEnabled(this, true)
                KeepAliveState.markStarted(this)
                registerScreenReceiver()
                syncCpuLock()
                handler.removeCallbacks(heartbeat)
                handler.postDelayed(heartbeat, HEARTBEAT_MS)
                handler.removeCallbacks(nativeBeat)
                handler.postDelayed(nativeBeat, NATIVE_BEAT_MS)
                DiagLog.i("keepalive.start", "since=${KeepAliveState.startedAt(this)}")
            }
        }
        // START_STICKY：被系统回收后由系统尝试重建。真正可靠的重启靠
        // BootReceiver（开机/升级），这条只是尽力而为。
        return START_STICKY
    }

    /**
     * 用户从最近任务里划掉 App 时，系统不会走 onDestroy 的常规路径。
     *
     * 保活是用户显式打开的常驻能力，被划掉不该静默失效——
     * 这里主动重建服务（START_STICKY 对「任务被移除」这条路径不生效）。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        DiagLog.w("keepalive.taskRemoved", "enabled=${KeepAliveState.isEnabled(this)}")
        if (KeepAliveState.isEnabled(this)) {
            start(this)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(heartbeat)
        handler.removeCallbacks(nativeBeat)
        releaseCpuLock("service-destroy")
        runCatching { screenReceiver?.let { unregisterReceiver(it) } }
        screenReceiver = null
        DiagLog.w("keepalive.destroy", "enabled=${KeepAliveState.isEnabled(this)}")
        super.onDestroy()
    }

    /** 监听屏幕开关，只用于记日志（持锁已改为无条件，见 syncCpuLock）。 */
    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        DiagLog.i("keepalive.screen", "off")
                        // 锁本已持有（服务启动时就拿了）；这里只是确认它没丢。
                        syncCpuLock()
                    }
                    Intent.ACTION_SCREEN_ON -> DiagLog.i("keepalive.screen", "on")
                    Intent.ACTION_USER_PRESENT -> DiagLog.i("keepalive.screen", "user-present")
                }
            }
        }
        runCatching {
            registerReceiver(
                receiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_USER_PRESENT)
                },
            )
            screenReceiver = receiver
        }.onFailure { DiagLog.e("keepalive.screen.fail", it.message ?: "unknown") }
    }

    /**
     * 按屏幕状态同步 CPU 锁。
     *
     * 【2026-10-31 修正：改为无条件持有，不再等屏幕广播】
     *
     * 旧实现只在收到 `ACTION_SCREEN_OFF` 时才 acquire。真机日志（0.3.7）里
     * **一条 `keepalive.lock` 都没有**，而 `native.beat` 的实际间隔被拉成 30 秒
     * （代码里写的是 10 秒）——这正是 CPU 休眠后 Handler 被冻结、
     * 只在维护窗口才跑一次的典型表现。
     *
     * 两个事实合起来说明：这个 ROM 没有把 `ACTION_SCREEN_OFF` 送到我们的
     * 动态注册 Receiver（或者送达时机已经太晚），锁从未拿到，于是
     * 「息屏保活」在物理层面就不成立。
     *
     * 现在改为**服务活着就持锁**，不再依赖任何屏幕广播：
     * - 保活是用户显式打开的常驻能力，他期望的正是「一直在跑」；
     * - 屏幕亮着时持 PARTIAL_WAKE_LOCK 的额外耗电很小
     *   （只保 CPU、不点亮屏幕，且用户正在前台用手机，CPU 本来就在跑）；
     * - 屏幕广播仍保留监听，但只用于记日志，不再决定持不持锁。
     *
     * 释放时机：用户关掉保活（服务 stopSelf）或服务被销毁。
     */
    private fun syncCpuLock() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        if (cpuLock?.isHeld == true) return
        runCatching {
            cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zrcode:keepalive-cpu").apply {
                setReferenceCounted(false)
                acquire()
            }
            DiagLog.i("keepalive.lock", "acquired screenOff=${!pm.isInteractive}")
        }.onFailure { DiagLog.e("keepalive.lock.fail", it.message ?: "unknown") }
    }

    private fun releaseCpuLock(reason: String) {
        cpuLock?.let { lock ->
            runCatching { if (lock.isHeld) lock.release() }
            DiagLog.i("keepalive.lock", "released reason=$reason")
        }
        cpuLock = null
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(NotificationChannels.KEEP_ALIVE) == null) {
            NotificationChannels.ensure(this)
        }
    }

    private fun startAsForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        runCatching {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, KeepAliveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val since = KeepAliveState.startedAt(this)
        val elapsed = if (since > 0) System.currentTimeMillis() - since else 0L
        return NotificationCompat.Builder(this, NotificationChannels.KEEP_ALIVE)
            .setSmallIcon(R.drawable.ic_stat_remote_control)
            .setContentTitle(getString(R.string.keep_alive_title))
            .setContentText(getString(R.string.keep_alive_text, formatDuration(elapsed)))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.keep_alive_stop), stopIntent)
            .build()
    }

    private fun formatDuration(ms: Long): String {
        val totalMin = ms / 60_000
        return when {
            totalMin < 60 -> getString(R.string.keep_alive_duration_min, totalMin)
            else -> getString(R.string.keep_alive_duration_hour, totalMin / 60, totalMin % 60)
        }
    }

    companion object {
        const val ACTION_STOP = "com.zcodeapp.remote.action.STOP_KEEPALIVE"
        private const val NOTIFICATION_ID = 1001
        /**
         * 心跳间隔。
         *
         * 30 秒是为了让后台健康巡检足够密：页面心跳若停摆，我们要在
         * 一两分钟内发现并重连，而不是等用户下次亮屏才发现断了。
         * 通知刷新本身很轻（同 ID、onlyAlertOnce），不会打扰用户。
         */
        private const val HEARTBEAT_MS = 30_000L

        /**
         * 原生代发心跳的周期：与页面自身的心跳间隔一致（实测 10 秒）。
         *
         * 为什么要和页面一致而不是更密：更密没有意义——服务端要的是「持续有帧」，
         * 10 秒已经满足；更密只会在页面自己恢复发送后造成重复帧。
         * 更疏则会踩到服务端的 30 秒 ack 超时（见 bundle 的 heartbeatAckTimeoutMs）。
         */
        private const val NATIVE_BEAT_MS = 10_000L

        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, KeepAliveService::class.java))
            }.onFailure { DiagLog.e("keepalive.start.fail", it.message ?: "unknown") }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeepAliveService::class.java))
        }

        /** 用户设置里开关变化时调用：开就起服务，关就停。 */
        fun apply(context: Context, enabled: Boolean) {
            if (enabled) start(context) else {
                KeepAliveState.setEnabled(context, false)
                stop(context)
            }
        }
    }
}

/**
 * 保活开关与启动时间的持久化。
 *
 * 为什么需要落盘：开机自启的 Receiver 运行时没有 Activity、也没有内存里的状态，
 * 只能从磁盘判断「用户上次是不是开着」。启动时间用于在常驻通知上显示已保活多久。
 */
object KeepAliveState {
    private const val PREF = "zrcode_keepalive"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_STARTED_AT = "started_at"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun startedAt(context: Context): Long =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getLong(KEY_STARTED_AT, 0L)

    fun markStarted(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putLong(KEY_STARTED_AT, System.currentTimeMillis()).apply()
    }

    /** 开机后重新拉起时保留原来的起始时间，让「已保活多久」连续。 */
    fun ensureStarted(context: Context) {
        if (startedAt(context) == 0L) markStarted(context)
    }

    /** 是否已加入电池优化白名单（Doze 下更不容易被冻结）。 */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * 申请电池优化白名单。
     *
     * 这是系统弹窗，用户必须自己确认；被拒绝时不做任何事——
     * 应用不能绕过，只能下次再引导。
     */
    fun requestIgnoreBatteryOptimizations(context: Context) {
        val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = android.net.Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure {
                // 部分 ROM 没有这个页面：退到电池优化列表，让用户手动找。
                runCatching {
                    context.startActivity(
                        Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
    }

    /**
     * 通知权限是否可用。
     *
     * 保活通知与任务提醒都依赖它；被拒时设置页要明确告诉用户，
     * 而不是让开关看起来「开着」但实际什么都收不到。
     */
    fun notificationsEnabled(context: Context): Boolean =
        androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun diagSummary(context: Context): String =
        "enabled=${isEnabled(context)} batt=${isIgnoringBatteryOptimizations(context)}" +
            " notif=${notificationsEnabled(context)}"
}
