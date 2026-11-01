package com.zcodeapp.remote.security

import android.net.Uri

/** 日志脱敏（v2 6.2）：URL 一律去 query/fragment 后才可出现在日志/错误文本中。 */
object Redactor {
    fun url(raw: String): String = runCatching {
        val uri = Uri.parse(raw)
        val sb = StringBuilder()
        uri.scheme?.let { sb.append(it).append("://") }
        uri.host?.let { sb.append(it) }
        uri.path?.let { sb.append(it) }
        sb.toString()
    }.getOrDefault("<invalid-url>")

    fun brief(s: String?, max: Int = 48): String? =
        s?.take(max)?.replace('\n', ' ')?.let { if (s.length > max) "$it…" else it }
}
