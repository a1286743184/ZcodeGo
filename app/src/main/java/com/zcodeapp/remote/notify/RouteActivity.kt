package com.zcodeapp.remote.notify

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import com.zcodeapp.remote.MainActivity
import com.zcodeapp.remote.R
import com.zcodeapp.remote.web.RemoteLinksStore

/**
 * 通知点击落点（v2 7.3）：exported=false，仅本应用 PendingIntent 可达；
 * 解析 opaque routeId → 带 URL/taskId 转投 MainActivity 后立即退场。
 */
class RouteActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent?.data?.let { extractRouteId(it) }
        val target = id?.let { RouteRegistry.get(it) }
        if (target != null) {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    action = MainActivity.ACTION_OPEN_ROUTE
                    putExtra(MainActivity.EXTRA_ROUTE_URL, target.url)
                    target.taskId?.let { putExtra(MainActivity.EXTRA_ROUTE_TASK_ID, it) }
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            )
        } else {
            // 路由过期/丢失：回退到最近连接；没有则仅回主页
            val last = RemoteLinksStore(this).latest()
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    action = MainActivity.ACTION_OPEN_ROUTE
                    last?.let { putExtra(MainActivity.EXTRA_ROUTE_URL, it.url) }
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            )
            Toast.makeText(this, R.string.route_task_not_found, Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    private fun extractRouteId(uri: Uri): String? =
        if (uri.scheme == "zcodeapp" && uri.host == "route") uri.lastPathSegment else null
}
