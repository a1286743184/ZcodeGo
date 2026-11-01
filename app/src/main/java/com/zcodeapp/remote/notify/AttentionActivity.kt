package com.zcodeapp.remote.notify

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.zcodeapp.remote.MainActivity
import com.zcodeapp.remote.R
import com.zcodeapp.remote.diag.DiagLog

/**
 * 息屏提醒界面。
 *
 * 【2026-10-27 关键修正】旧实现靠 `context.startActivity(AttentionActivity)` 从
 * 通知派发线程直接拉起界面。这条路径**从来没有生效过**：Android 10 起后台启动
 * Activity 受 BAL（Background Activity Launch）限制，而「正在跑前台服务」并不在
 * 豁免名单里。真机日志里 `attention.show` 一条都没有，就是这个原因——
 * 息屏提醒是「写了但没跑」的代码。
 *
 * 现在改成系统认可的做法：把界面挂成通知的 fullScreenIntent。
 * 由系统在锁屏/息屏时替我们拉起，天然绕开 BAL；用户正在用手机时系统只会显示
 * 一条 heads-up，不会打断他——正好是我们想要的语义，也省掉了自己判断屏幕状态。
 *
 * Android 14 起普通应用需要用户手动授予「全屏通知」权限（闹钟/来电类自动获得）。
 * 没有该权限时系统会降级成普通 heads-up 通知，界面不会被拉起；设置页提供入口引导。
 *
 * 界面本身仍保留三重唤醒：
 * 1. Manifest 的 showWhenLocked / turnScreenOn —— 让界面能在锁屏上显示并点亮。
 * 2. 运行时 setShowWhenLocked/setTurnScreenOn + requestDismissKeyguard —— 兜底旧版本。
 * 3. 短暂 WakeLock（ACQUIRE_CAUSES_WAKEUP）—— 保证屏幕真的亮起来。
 */
class AttentionActivity : Activity() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val kind = intent.getStringExtra(EXTRA_KIND) ?: KIND_ATTENTION
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val body = intent.getStringExtra(EXTRA_BODY).orEmpty()
        val routeUrl = intent.getStringExtra(EXTRA_ROUTE_URL)
        val taskId = intent.getStringExtra(EXTRA_ROUTE_TASK_ID)

        DiagLog.i("attention.show", "kind=$kind hasRoute=${routeUrl != null}")

        setupWindow()
        acquireWakeLock()

        val root = buildUi(kind, title, body) { openTarget(routeUrl, taskId) }
        setContentView(root)

        // 15 秒无操作自动退出：用户没理它就说明不重要，
        // 不该让一个熄屏弹窗一直占着屏幕耗电。
        Handler(Looper.getMainLooper()).postDelayed({ finishSafely() }, AUTO_DISMISS_MS)
    }

    private fun setupWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(KeyguardManager::class.java)
            km?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 状态栏与导航栏保持透明，让提醒界面看起来是一整块，而不是嵌在系统壳里。
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        wakeLock = runCatching {
            pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "zrcode:attention",
            ).apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_MS)
            }
        }.getOrNull()
    }

    /**
     * 极简提醒界面。
     *
     * 配色跟页面一致：深色底 + 浅色字。息屏弹窗固定用深色，
     * 因为它在暗环境下出现，浅色底会刺眼。
     */
    private fun buildUi(kind: String, title: String, body: String, onOpen: () -> Unit): ViewGroup {
        val bg = Color.parseColor("#161616")
        val fg = Color.parseColor("#F5F5F5")
        val muted = Color.parseColor("#9E9E9E")
        val accent = when (kind) {
            KIND_FAILED -> Color.parseColor("#F28B82")
            KIND_QUESTION -> Color.parseColor("#E1AB66")
            else -> Color.parseColor("#4CAF50")
        }
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(28), dp(28), dp(28), dp(28))
        }

        // 状态条：一条 3dp 的彩色短线，用颜色区分「完成 / 失败 / 提问」。
        root.addView(android.view.View(this).apply {
            setBackgroundColor(accent)
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(3)).apply {
                bottomMargin = dp(24)
            }
        })

        root.addView(TextView(this).apply {
            text = when (kind) {
                KIND_FAILED -> getString(R.string.notify_task_failed)
                KIND_QUESTION -> getString(R.string.notify_attention)
                else -> getString(R.string.notify_task_completed)
            }
            setTextColor(accent)
            textSize = 13f
            letterSpacing = 0.08f
        })

        root.addView(TextView(this).apply {
            text = title.ifBlank { getString(R.string.app_name) }
            setTextColor(fg)
            textSize = 22f
            setPadding(0, dp(10), 0, 0)
        })

        if (body.isNotBlank()) {
            root.addView(TextView(this).apply {
                text = body
                setTextColor(muted)
                textSize = 14f
                setLineSpacing(dp(4).toFloat(), 1f)
                setPadding(0, dp(10), 0, 0)
            })
        }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(32), 0, 0)
        }
        actions.addView(Button(this).apply {
            text = getString(R.string.attention_open)
            setTextColor(bg)
            setBackgroundColor(fg)
            setOnClickListener {
                onOpen()
                finishSafely()
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f)
        })
        actions.addView(Button(this).apply {
            text = getString(R.string.attention_dismiss)
            setTextColor(muted)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { finishSafely() }
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                marginStart = dp(10)
            }
        })
        root.addView(actions)
        return root
    }

    private fun openTarget(routeUrl: String?, taskId: String?) {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_ROUTE
            putExtra(MainActivity.EXTRA_ROUTE_URL, routeUrl)
            putExtra(MainActivity.EXTRA_ROUTE_TASK_ID, taskId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        runCatching { startActivity(intent) }
    }

    private fun finishSafely() {
        runCatching { finish() }
    }

    override fun onDestroy() {
        wakeLock?.let { runCatching { if (it.isHeld) it.release() } }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_KIND = "attention_kind"
        const val EXTRA_TITLE = "attention_title"
        const val EXTRA_BODY = "attention_body"
        const val EXTRA_ROUTE_URL = "attention_route_url"
        const val EXTRA_ROUTE_TASK_ID = "attention_route_task_id"

        const val KIND_COMPLETED = "completed"
        const val KIND_FAILED = "failed"
        const val KIND_QUESTION = "question"
        const val KIND_ATTENTION = "attention"

        private const val WAKE_LOCK_MS = 10_000L
        private const val AUTO_DISMISS_MS = 15_000L

        /**
         * 是否该用全屏提醒。
         *
         * 【2026-10-27】现在只用来决定「通知要不要挂 fullScreenIntent」，
         * 不再决定「要不要自己 startActivity」——后者受 BAL 限制从来没成功过。
         * 屏幕关着或锁屏时才挂：用户正在用手机时全屏弹窗会打断他手上的事，
         * 那种情况一条 heads-up 就够了。
         */
        fun shouldUseFullScreen(context: Context): Boolean {
            val pm = context.getSystemService(PowerManager::class.java) ?: return false
            val km = context.getSystemService(KeyguardManager::class.java)
            val screenOff = !pm.isInteractive
            val locked = km?.isKeyguardLocked == true
            return screenOff || locked
        }

        /**
         * 系统是否允许我们使用全屏通知（Android 14+ 需用户手动授予）。
         *
         * 拿不到时不能硬挂 fullScreenIntent：部分 ROM 会直接丢弃整条通知，
         * 反而比降级成 heads-up 更糟。
         */
        fun canUseFullScreenIntent(context: Context): Boolean {
            val nm = context.getSystemService(android.app.NotificationManager::class.java)
                ?: return false
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                nm.canUseFullScreenIntent()
            } else {
                true
            }
        }

        /** 跳到系统里「允许全屏通知」的开关页（Android 14+）。 */
        fun openFullScreenIntentSettings(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
            val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return runCatching { context.startActivity(intent) }.isSuccess
        }

        fun intentFor(
            context: Context,
            kind: String,
            title: String?,
            body: String?,
            routeUrl: String?,
            taskId: String?,
        ): Intent = Intent(context, AttentionActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
            putExtra(EXTRA_KIND, kind)
            putExtra(EXTRA_TITLE, title.orEmpty())
            putExtra(EXTRA_BODY, body.orEmpty())
            putExtra(EXTRA_ROUTE_URL, routeUrl)
            putExtra(EXTRA_ROUTE_TASK_ID, taskId)
        }
    }
}
