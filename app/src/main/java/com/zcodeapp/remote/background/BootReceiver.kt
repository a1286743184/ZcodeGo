package com.zcodeapp.remote.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zcodeapp.remote.diag.DiagLog

/**
 * 开机 / 应用升级后重新拉起保活服务。
 *
 * 为什么必须有这个：系统在关机、升级、或长期冻结后会杀掉前台服务，
 * 用户不可能每次都手动打开 App 再点一次开关。这里读磁盘上的开关状态，
 * 开着就自动恢复——这是「息屏也能收到通知」能长期成立的前提。
 *
 * 同时恢复待确认的提醒：升级后 PendingIntent 会失效（包被替换），
 * 已排的闹钟不会自己回来。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }
        DiagLog.i("boot.received", "action=$action enabled=${KeepAliveState.isEnabled(context)}")
        if (KeepAliveState.isEnabled(context)) {
            KeepAliveState.ensureStarted(context)
            KeepAliveService.start(context)
        }
    }
}
