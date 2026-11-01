package com.zcodeapp.remote.web

import android.content.Context
import android.net.Uri
import com.zcodeapp.remote.security.LinkVault
import org.json.JSONObject

/**
 * 最近连接（v2 6.2）：URL 含 sid/mid，密文存储；标题明文仅作展示。
 * 密钥失效（解密失败）时丢弃该条目，要求用户重新配对。
 */
class RemoteLinksStore(context: Context) {
    private val sp = context.getSharedPreferences("zrcode_recent", Context.MODE_PRIVATE)

    data class RecentLink(val title: String, val url: String, val ts: Long)

    fun record(url: String) {
        val cipher = LinkVault.encrypt(url.toByteArray(Charsets.UTF_8)) ?: return
        val title = titleOf(url)
        val ts = System.currentTimeMillis()
        val entry = JSONObject().put("t", title).put("c", cipher).put("ts", ts)
        sp.edit().putString(keyOf(url), entry.toString()).apply()
        trim()
    }

    fun list(): List<RecentLink> {
        val out = ArrayList<RecentLink>()
        for ((k, v) in sp.all) {
            if (v !is String || !k.startsWith(PREFIX)) continue
            try {
                val obj = JSONObject(v)
                val cipher = obj.getString("c")
                val plain = LinkVault.decrypt(cipher) ?: continue // 密钥失效丢弃
                out.add(RecentLink(obj.getString("t"), String(plain, Charsets.UTF_8), obj.getLong("ts")))
            } catch (e: Exception) {
                sp.edit().remove(k).apply()
            }
        }
        return out.sortedByDescending { it.ts }
    }

    fun latest(): RecentLink? = list().firstOrNull()

    fun clear() {
        sp.edit().clear().apply()
    }

    private fun trim() {
        val keys = sp.all.entries
            .filter { it.key.startsWith(PREFIX) && it.value is String }
            .mapNotNull { e ->
                runCatching { Pair(e.key, JSONObject(e.value as String).getLong("ts")) }.getOrNull()
            }
        if (keys.size <= MAX) return
        keys.sortedByDescending { it.second }.drop(MAX).forEach { sp.edit().remove(it.first).apply() }
    }

    private fun titleOf(url: String): String {
        val uri = Uri.parse(url)
        val name = uri.getQueryParameter("name")
        if (!name.isNullOrBlank()) return name.take(40)
        val sid = uri.getQueryParameter("sid")
        if (!sid.isNullOrBlank()) return "session ${sid.takeLast(6)}"
        return "zcode.z.ai"
    }

    private fun keyOf(url: String): String = PREFIX + UrlPolicy.connectionId(url)

    private companion object {
        const val PREFIX = "link_"
        const val MAX = 10
    }
}
