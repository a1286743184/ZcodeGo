package com.zcodeapp.remote.notify

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * SQLite 信箱（v2 D1：不引 Room，事务内完成去重/墓碑/租约）。
 * 表结构对齐设计文档 7.1；去重记录与 resolved 墓碑同库保留。
 */
class SqliteNotificationLog(context: Context) : BaseNotificationLog() {
    private val helper = Helper(context)

    private class Helper(context: Context) : SQLiteOpenHelper(context, "zrcode_notify.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE notification_event (
                    event_key TEXT PRIMARY KEY,
                    kind TEXT NOT NULL,
                    connection_id TEXT NOT NULL,
                    task_key TEXT NOT NULL,
                    interaction_key TEXT,
                    failed INTEGER NOT NULL DEFAULT 0,
                    kind_name TEXT,
                    title TEXT,
                    low_confidence INTEGER NOT NULL DEFAULT 0,
                    route_url TEXT,
                    route_task_id TEXT,
                    state TEXT NOT NULL,
                    lease_until INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE attention_state (
                    connection_id TEXT NOT NULL,
                    task_key TEXT NOT NULL,
                    interaction_key TEXT NOT NULL,
                    state TEXT NOT NULL,
                    PRIMARY KEY (connection_id, task_key, interaction_key)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX idx_notify_state ON notification_event(state, lease_until)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS notification_event")
            db.execSQL("DROP TABLE IF EXISTS attention_state")
            onCreate(db)
        }
    }

    override fun findEvent(eventKey: String): EventRow? {
        helper.readableDatabase.rawQuery(
            "SELECT event_key,kind,connection_id,task_key,interaction_key,failed,kind_name,title,low_confidence,route_url,route_task_id,state,lease_until,created_at FROM notification_event WHERE event_key=?",
            arrayOf(eventKey),
        ).use { c ->
            if (!c.moveToFirst()) return null
            return rowOf(c)
        }
    }

    override fun insertEvent(row: EventRow): Boolean {
        val db = helper.writableDatabase
        db.beginTransaction()
        return try {
            val r = db.insertWithOnConflict("notification_event", null, row.toValues(), SQLiteDatabase.CONFLICT_IGNORE)
            db.setTransactionSuccessful()
            r != -1L
        } finally {
            db.endTransaction()
        }
    }

    override fun updateEventState(eventKey: String, state: String, leaseUntil: Long, nowMs: Long) {
        val db = helper.writableDatabase
        db.execSQL(
            "UPDATE notification_event SET state=?, lease_until=? WHERE event_key=?",
            arrayOf(state, leaseUntil, eventKey)
        )
    }

    override fun findAttention(connectionId: String, taskKey: String, interactionKey: String): String? {
        helper.readableDatabase.rawQuery(
            "SELECT state FROM attention_state WHERE connection_id=? AND task_key=? AND interaction_key=?",
            arrayOf(connectionId, taskKey, interactionKey),
        ).use { c ->
            if (!c.moveToFirst()) return null
            return c.getString(0)
        }
    }

    override fun upsertAttention(connectionId: String, taskKey: String, interactionKey: String, state: String) {
        val db = helper.writableDatabase
        db.execSQL(
            "INSERT INTO attention_state(connection_id,task_key,interaction_key,state) VALUES(?,?,?,?) " +
                "ON CONFLICT(connection_id,task_key,interaction_key) DO UPDATE SET state=excluded.state",
            arrayOf(connectionId, taskKey, interactionKey, state)
        )
    }

    override fun pendingInteractions(connectionId: String, taskKey: String): List<String> {
        val out = ArrayList<String>()
        helper.readableDatabase.rawQuery(
            "SELECT interaction_key FROM attention_state WHERE connection_id=? AND task_key=? AND state='PENDING'",
            arrayOf(connectionId, taskKey),
        ).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out
    }

    override fun expiredProcessing(nowMs: Long): List<EventRow> {
        val out = ArrayList<EventRow>()
        helper.readableDatabase.rawQuery(
            "SELECT event_key,kind,connection_id,task_key,interaction_key,failed,kind_name,title,low_confidence,route_url,route_task_id,state,lease_until,created_at FROM notification_event WHERE state='PROCESSING' AND lease_until<?",
            arrayOf(nowMs.toString()),
        ).use { c ->
            while (c.moveToNext()) out.add(rowOf(c))
        }
        return out
    }

    private fun rowOf(c: android.database.Cursor): EventRow = EventRow(
        eventKey = c.getString(0),
        kind = c.getString(1),
        connectionId = c.getString(2),
        taskKey = c.getString(3),
        interactionKey = if (c.isNull(4)) null else c.getString(4),
        failed = c.getInt(5) != 0,
        kindName = if (c.isNull(6)) null else c.getString(6),
        title = if (c.isNull(7)) null else c.getString(7),
        lowConfidence = c.getInt(8) != 0,
        routeUrl = if (c.isNull(9)) null else c.getString(9),
        routeTaskId = if (c.isNull(10)) null else c.getString(10),
        state = c.getString(11),
        leaseUntil = c.getLong(12),
        createdAt = c.getLong(13),
    )

    private fun EventRow.toValues(): android.content.ContentValues =
        android.content.ContentValues().apply {
            put("event_key", eventKey)
            put("kind", kind)
            put("connection_id", connectionId)
            put("task_key", taskKey)
            put("interaction_key", interactionKey)
            put("failed", if (failed) 1 else 0)
            put("kind_name", kindName)
            put("title", title)
            put("low_confidence", if (lowConfidence) 1 else 0)
            put("route_url", routeUrl)
            put("route_task_id", routeTaskId)
            put("state", state)
            put("lease_until", leaseUntil)
            put("created_at", createdAt)
        }
}
