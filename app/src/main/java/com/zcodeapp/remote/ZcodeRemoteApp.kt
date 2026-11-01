package com.zcodeapp.remote

import android.app.Application
import android.app.LocaleManager
import android.app.NotificationManager
import android.os.Build
import android.os.LocaleList
import com.zcodeapp.remote.background.KeepAliveService
import com.zcodeapp.remote.background.KeepAliveState
import com.zcodeapp.remote.diag.DiagLog
import com.zcodeapp.remote.notify.NotificationChannels
import com.zcodeapp.remote.notify.NotificationDispatcher
import com.zcodeapp.remote.notify.SqliteNotificationLog
import com.zcodeapp.remote.shell.SettingsStore
import com.zcodeapp.remote.ui.theme.PageThemeStore
import com.zcodeapp.remote.update.Updater
import com.zcodeapp.remote.web.RemoteLinksStore
import com.zcodeapp.remote.web.WebViewSessionController
import java.util.Locale

class ZcodeRemoteApp : Application() {
    lateinit var settings: SettingsStore
    lateinit var links: RemoteLinksStore
    lateinit var dispatcher: NotificationDispatcher

    /**
     * WebView 会话控制器：**跟进程走，不跟 Activity 走**。
     *
     * 保活要求会话在 Activity 被回收后仍然存活（息屏时系统常回收 Activity）。
     * 若控制器挂在 Activity 上，重建时会新建一个没有 WebView 的实例，
     * 旧 WebView 被孤立，WS 与任务订阅一起断掉——后台保活就形同虚设。
     */
    lateinit var sessionController: WebViewSessionController
        private set

    override fun onCreate() {
        super.onCreate()
        DiagLog.init(this)
        DiagLog.i("app.start", "v=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})")
        applyPreferredLocale()
        // 上次同步到的页面配色要在首帧之前就绪：否则入口页会先按系统主题画一帧，
        // 再跳到页面配色，视觉上闪一下。
        PageThemeStore.load(this)
        // 清理上一轮更新留下的锁与残留 APK。
        // 放在启动时做，而不是下载前：进程可能在上次下载途中被杀，
        // 那时缓存里会留半截文件，不清理会一直占空间、也会干扰续传判断。
        Updater.onAppStart(this)
        NotificationChannels.ensure(this)
        // 【0.4.4 诊断】进程每次启动记一次通知与全屏通知的授权状态。
        // 用户实测：Android 14+ 的 USE_FULL_SCREEN_INTENT 授权在每次应用更新后
        // 被系统重置（平台行为，非本应用 bug），导致息屏提醒悄悄失效直到重新授权。
        // 这条日志让「重置发生」在 diag 里可见，不用再靠用户复述。
        run {
            val nm = getSystemService(NotificationManager::class.java)
            val fsi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                nm?.canUseFullScreenIntent()
            } else {
                null
            }
            DiagLog.i("notify.grant", "notif=${nm?.areNotificationsEnabled()} fsi=${fsi ?: "n/a(<14)"}")
        }
        settings = SettingsStore(this)
        links = RemoteLinksStore(this)
        val log = SqliteNotificationLog(this)
        dispatcher = NotificationDispatcher(this, log)
        dispatcher.recover()
        sessionController = WebViewSessionController(this)
        // 进程被系统杀掉后重启（不是开机；开机由 BootReceiver 处理）：
        // 用户开着保活就把它拉回来，否则「切走一会儿再回来」会静默失去保活。
        if (KeepAliveState.isEnabled(this)) {
            KeepAliveState.ensureStarted(this)
            KeepAliveService.start(this)
        }
    }

    /**
     * 本项目面向中文用户：把简体中文设为**应用级语言**。
     *
     * 实测（模拟器，系统语言 en-US）：不设置时整个界面显示英文，
     * 而文案与远控页都是中文语境，中英混排很突兀。
     *
     * 必须在资源解析之前生效，所以这里用系统提供的 per-app locale
     * （Android 13+ 的 LocaleManager），而不是改 resources.configuration——
     * 后者在 Application.onCreate 里改已经晚于 Activity 的资源解析，界面不会变。
     */
    private fun applyPreferredLocale() {
        val zh = Locale.SIMPLIFIED_CHINESE
        Locale.setDefault(zh)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        runCatching {
            val lm = getSystemService(LocaleManager::class.java) ?: return@runCatching
            val current = lm.applicationLocales
            if (current.size() > 0 && current[0].language == zh.language) return@runCatching
            lm.applicationLocales = LocaleList(zh)
        }
    }
}
