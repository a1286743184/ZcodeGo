package com.zcodeapp.remote.diag

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * 落盘日志：**按小时分块，长期保留**。
 *
 * 为什么按小时分块（2026-10-27 改）：
 * 单文件滚动（diag.log + diag.log.1）有两个致命问题——
 * 1. 只留一份历史，512KB 写满就丢掉更早的内容，跨天排查时现场早没了；
 * 2. 上传时只能整份传，用户上传的是「一堆混在一起的时间」，无法只取最近两小时。
 *
 * 现在每个自然小时一个文件：`diag-YYYYMMDD-HH.log`。
 * 好处是上传可以精确按时间窗取文件、导出可以按日期挑、清理可以按天删。
 *
 * 设计约束：
 * - **只记类别与状态，不记正文**。URL 只保留 scheme://host/path，query 一律丢弃
 *   （里面有 sid/token/code，属于凭据）。消息体一律不记。
 * - 单文件上限 8MB（一小时写满说明有异常循环，此时新建同小时的续号文件，
 *   而不是丢内容——排查时最需要的就是那段异常）。
 * - 保留策略：默认 14 天。**手机端长期保留**，只在超过上限时按天清理，
 *   由 [retentionDays] 控制，调用方可在设置里调。
 * - 写入在单线程 executor 上串行，不阻塞 UI 与 WebView 线程；每次写盘 flush。
 */
object DiagLog {
    private const val TAG = "ZcodeRemoteDiag"
    private const val PREFIX = "diag-"
    private const val MAX_BYTES = 8L * 1024L * 1024L
    private const val NAME_FMT = "yyyyMMdd-HH"

    /** 日志保留天数。手机端默认长期保留，这个值只用于「自动清理」的上限。 */
    @Volatile
    var retentionDays: Int = 14

    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "zr-diag-log").apply { isDaemon = true }
    }
    private val seq = AtomicLong(0)
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val nameFmt = SimpleDateFormat(NAME_FMT, Locale.US)
    private val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.US)

    @Volatile
    private var dir: File? = null

    fun init(context: Context) {
        dir = File(context.filesDir, "diag").apply { mkdirs() }
    }

    /** 当前小时的日志文件（供设置页展示与即时读取）。 */
    fun logFile(context: Context): File = File(File(context.filesDir, "diag"), currentName())

    private fun currentName(): String = "$PREFIX${nameFmt.format(Date())}.log"

    fun i(event: String, detail: String = "") = write("I", event, detail)
    fun w(event: String, detail: String = "") = write("W", event, detail)
    fun e(event: String, detail: String = "") = write("E", event, detail)

    private fun write(level: String, event: String, detail: String) {
        val d = dir ?: return
        val n = seq.incrementAndGet()
        val line = buildString {
            append(fmt.format(Date()))
            append(' ')
            append(level)
            append(" #")
            append(n)
            append(' ')
            append(event)
            if (detail.isNotEmpty()) {
                append(" | ")
                append(detail)
            }
            append('\n')
        }
        if (com.zcodeapp.remote.BuildConfig.DEBUG) Log.i(TAG, line.trimEnd())
        exec.execute { runCatching { append(d, line) } }
    }

    /**
     * 追加到当前小时文件。
     *
     * 超上限时**新建续号文件**而不是丢弃：一小时写满 8MB 说明那段时间有异常循环，
     * 那正是最需要保留的证据。续号文件形如 `diag-20261027-09.1.log`。
     */
    private fun append(d: File, line: String) {
        val base = currentName().removeSuffix(".log")
        var f = File(d, "$base.log")
        var idx = 0
        while (f.exists() && f.length() > MAX_BYTES && idx < 9) {
            idx++
            f = File(d, "$base.$idx.log")
        }
        f.appendText(line, Charsets.UTF_8)
    }

    /** 列出全部日志文件，按文件名（即时间）升序。 */
    fun listFiles(context: Context): List<File> {
        val d = File(context.filesDir, "diag")
        if (!d.isDirectory) return emptyList()
        return d.listFiles { f -> f.isFile && f.name.startsWith(PREFIX) && f.name.endsWith(".log") }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    /**
     * 取「某个时间点往前 N 小时」覆盖到的文件。
     *
     * 为什么不是简单的「最近 N 个文件」：用户可能隔了几小时才点上传，
     * 那时最近 3 个文件可能是上午的，而他刚遇到的问题在下午。
     * 按时间窗取才能保证「点上传那一刻之前 2 小时」的现场一定在里面。
     *
     * 跨自然日时窗口会自然跨到前一天的文件——文件名带日期，排序即时间序，
     * 不需要特殊处理。
     */
    fun filesWithinHours(context: Context, endMs: Long, hours: Int): List<File> {
        val startMs = endMs - hours * 3600_000L
        val startName = "$PREFIX${nameFmt.format(Date(startMs))}.log"
        val endName = "$PREFIX${nameFmt.format(Date(endMs))}.log"
        // 文件名前缀比较即可：yyyyMMdd-HH 的字典序等于时间序。
        // 同小时的续号文件（.1/.2）名字更长但前缀相同，也要包含进来。
        val startBase = startName.removeSuffix(".log")
        val endBase = endName.removeSuffix(".log")
        return listFiles(context).filter { f ->
            val n = f.name.removeSuffix(".log")
            val base = n.substringBefore('.')
            base >= startBase && base <= endBase
        }
    }

    /** 读出全部日志文本（导出用）。 */
    fun readAll(context: Context): String = runCatching {
        listFiles(context).joinToString("") { it.readText(Charsets.UTF_8) }
    }.getOrDefault("")

    /** 读单个文件（应用内查看器用）。 */
    fun readFile(f: File): String = runCatching {
        f.readText(Charsets.UTF_8)
    }.getOrDefault("")

    fun clear(context: Context) {
        runCatching { listFiles(context).forEach { it.delete() } }
    }

    /**
     * 按保留天数清理。
     *
     * 为什么手机端也清理：日志长期保留是需求，但无限增长会占满用户存储。
     * 默认 14 天，可通过 [retentionDays] 调整。返回删除的文件数。
     */
    fun prune(context: Context, keepDays: Int = retentionDays): Int {
        val cutoff = System.currentTimeMillis() - keepDays * 24L * 3600_000L
        val cutoffDay = dayFmt.format(Date(cutoff))
        var removed = 0
        for (f in listFiles(context)) {
            // 文件名形如 diag-20261027-09.log，取中间 8 位日期。
            val day = f.name.removePrefix(PREFIX).take(8)
            if (day.length == 8 && day < cutoffDay) {
                if (f.delete()) removed++
            }
        }
        return removed
    }

    /** 文件大小合计（设置页展示用）。 */
    fun totalBytes(context: Context): Long = listFiles(context).sumOf { it.length() }

    /**
     * URL 脱敏：只留 scheme://host/path，query 与 fragment 全部丢弃。
     * 配对链接的 sid/token、OAuth 回调的 code/state 都在 query 里，绝不能进日志。
     */
    fun safeUrl(raw: String?): String {
        if (raw.isNullOrBlank()) return "-"
        return runCatching {
            val u = java.net.URI(raw.trim())
            val host = u.host ?: return "-"
            val path = u.rawPath ?: "/"
            "${u.scheme}://$host$path"
        }.getOrElse { "-" }
    }
}
