package com.zcodeapp.remote.notify

import java.util.LinkedList
import java.util.UUID

/**
 * 通知点击路由（v2 7.3）：PendingIntent 只带不可猜的 opaque routeId，
 * URL/taskId 由 App 私有内存表解析，TTL 10 分钟，LRU 上限 100。
 */
object RouteRegistry {
    private const val TTL_MS = 10 * 60 * 1000L
    private const val MAX_ENTRIES = 100

    private data class Entry(val target: RouteTarget, val expiresAt: Long)

    private val entries = LinkedHashMap<String, Entry>()

    @Synchronized
    fun register(target: RouteTarget): String {
        val id = UUID.randomUUID().toString().substring(0, 12)
        entries[id] = Entry(target, System.currentTimeMillis() + TTL_MS)
        while (entries.size > MAX_ENTRIES) {
            val eldest = entries.entries.iterator().next()
            entries.remove(eldest.key)
        }
        return id
    }

    @Synchronized
    fun get(id: String): RouteTarget? {
        val e = entries[id] ?: return null
        if (System.currentTimeMillis() > e.expiresAt) {
            entries.remove(id)
            return null
        }
        return e.target
    }

    @Synchronized
    fun prune() {
        val now = System.currentTimeMillis()
        val dead = LinkedList<String>()
        for ((k, v) in entries) if (now > v.expiresAt) dead.add(k)
        for (k in dead) entries.remove(k)
    }
}
