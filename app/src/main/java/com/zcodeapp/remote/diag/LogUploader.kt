package com.zcodeapp.remote.diag

import android.content.Context
import com.zcodeapp.remote.BuildConfig
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 日志上传：把日志按时间窗逐份推到私有 Gitee 仓库，PC 侧用 tools/fetch_logs.py 拉取。
 *
 * 【2026-10-27 改版：按时间窗多文件上传】
 * 旧实现把所有日志打成一个 zip。问题：用户可能隔了几小时才点上传，
 * 「最近一份文件」里可能没有他要反映的问题；而整份上传又包含了大量无关时段。
 *
 * 现在：
 * - 日志本身按小时分块（见 DiagLog），文件名带日期与小时
 * - 上传时取「点上传那一刻往前 [WINDOW_HOURS] 小时」覆盖到的所有文件
 * - **逐个上传**，每个文件一个云端对象，路径带日期与小时
 * - 因此一次上传可能产生 2~3 个对象（跨小时、跨自然日时更多）
 *
 * 为什么逐个而不是打包：PC 侧要能按日期挑、按小时找；
 * 打成 zip 反而要求先解包才能定位时间段，也失去了「只拉最近 3 个」的可能。
 *
 * 凭据来源（关键）：
 * - **不把 token 编进 release APK**。APK 发布在公开 R2 上，谁都能解包。
 * - debug 构建可用编译期注入（本机自测方便）；release 由用户在设置页粘贴、存本机。
 */
object LogUploader {
    private const val API_BASE = "https://gitee.com/api/v5"
    private const val BRANCH = "master"

    /**
     * 上传目标仓库，来自构建配置（local.properties 的 zr.logOwner / zr.logRepo，
     * 编译期注入 BuildConfig）。开源默认为空：自行部署时配置成自己的私有仓库。
     * 仓库名不是凭据，token 才是——token 的处理见下方凭据来源说明。
     */
    private val owner: String get() = BuildConfig.LOG_UPLOAD_OWNER.trim()
    private val repo: String get() = BuildConfig.LOG_UPLOAD_REPO.trim()

    private const val PREF = "zr_log_upload"
    private const val KEY_TOKEN = "gitee_token"

    /**
     * 默认上传窗口：向前 2 小时。
     *
     * 用户点上传的时刻往前推 2 小时，覆盖到的日志文件全部上传。
     * 按小时分块时，这个窗口通常会命中 2~3 个文件（含边界上的两个小时）。
     */
    const val WINDOW_HOURS = 2

    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "zr-log-upload").apply { isDaemon = true }
    }

    sealed class Result {
        /** 上传成功。files 是上传成功的文件名列表。 */
        data class Ok(val files: List<String>, val bytes: Long) : Result()
        data class Failed(val reason: String, val uploaded: Int = 0) : Result()
        data object NoToken : Result()
        data object NoLogs : Result()
    }

    fun savedToken(context: Context): String =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_TOKEN, "").orEmpty()

    fun saveToken(context: Context, token: String) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY_TOKEN, token.trim()).apply()
    }

    fun clearToken(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(KEY_TOKEN).apply()
    }

    fun token(context: Context): String {
        val build = BuildConfig.LOG_UPLOAD_TOKEN.orEmpty().trim()
        if (build.isNotEmpty()) return build
        return savedToken(context)
    }

    fun isConfigured(context: Context): Boolean =
        token(context).isNotEmpty() && owner.isNotEmpty() && repo.isNotEmpty()

    /** 默认上传窗口内会涉及的文件（供设置页展示「将上传 N 个文件」）。 */
    fun defaultWindowFiles(context: Context): List<File> =
        DiagLog.filesWithinHours(context, System.currentTimeMillis(), WINDOW_HOURS)

    /** 上传默认窗口（向前 2 小时）。 */
    fun upload(context: Context, onResult: (Result) -> Unit) =
        uploadFiles(context, defaultWindowFiles(context), onResult)

    /** 上传指定文件集合（应用内日志管理器选中若干文件时用）。 */
    fun uploadFiles(context: Context, files: List<File>, onResult: (Result) -> Unit) {
        val appContext = context.applicationContext
        val tok = token(appContext)
        if (tok.isEmpty() || owner.isEmpty() || repo.isEmpty()) {
            mainHandler.post { onResult(Result.NoToken) }
            return
        }
        if (files.isEmpty()) {
            mainHandler.post { onResult(Result.NoLogs) }
            return
        }
        exec.execute {
            val result = runCatching { doUpload(appContext, tok, files) }
                .getOrElse { e ->
                    DiagLog.e("upload.failed", e.javaClass.simpleName)
                    Result.Failed(e.message ?: e.javaClass.simpleName)
                }
            mainHandler.post { onResult(result) }
        }
    }

    private fun doUpload(context: Context, tok: String, files: List<File>): Result {
        val device = deviceId()
        var uploaded = 0
        var bytes = 0L
        val okNames = ArrayList<String>()

        for (f in files) {
            if (!f.exists() || f.length() == 0L) continue
            val content = f.readBytes()
            // 云端路径保留日期与小时：PC 侧按文件名即可定位时间段，不需要解包。
            val remotePath = "device_logs/$device/${f.name}"
            if (putFile(tok, remotePath, content)) {
                uploaded++
                bytes += content.size
                okNames.add(f.name)
                DiagLog.i("upload.file.ok", "name=${f.name} bytes=${content.size}")
            } else {
                DiagLog.e("upload.file.fail", "name=${f.name}")
            }
        }

        return when {
            uploaded == 0 -> Result.Failed("全部文件上传失败，请检查 token 是否有仓库写权限")
            uploaded < files.size -> Result.Failed(
                "部分上传失败：${uploaded}/${files.size}",
                uploaded = uploaded,
            )
            else -> {
                // 附一份清单，PC 侧不必猜是哪台设备、哪个版本、什么时间。
                putFile(tok, "device_logs/$device/meta.json", metaJson(context).toByteArray(Charsets.UTF_8))
                Result.Ok(okNames, bytes)
            }
        }
    }

    private fun metaJson(context: Context): String = JSONObject()
        .put("device", deviceId())
        .put("appVersion", BuildConfig.VERSION_NAME)
        .put("versionCode", BuildConfig.VERSION_CODE)
        .put("uploadedAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date()))
        .put("windowHours", WINDOW_HOURS)
        .put("retentionDays", DiagLog.retentionDays)
        .toString()

    /**
     * Gitee Contents API：先查是否已存在（同名要给 sha 才能覆盖），再 POST 或 PUT。
     * 返回 true 表示服务端确认写入（200/201）。
     */
    private fun putFile(tok: String, path: String, content: ByteArray): Boolean {
        val base = "$API_BASE/repos/$owner/$repo/contents/$path"
        var sha: String? = null
        runCatching {
            val conn = open("$base?access_token=$tok&ref=$BRANCH", "GET")
            if (conn.responseCode == 200) {
                val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                sha = JSONObject(body).optString("sha").ifBlank { null }
            }
            conn.disconnect()
        }

        val body = JSONObject()
            .put("message", "chore: upload ${File(path).name}")
            .put("content", android.util.Base64.encodeToString(content, android.util.Base64.NO_WRAP))
            .put("branch", BRANCH)
            .apply { sha?.let { put("sha", it) } }
            .toString()

        val conn = open("$base?access_token=$tok", if (sha != null) "PUT" else "POST")
        return runCatching {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..201) {
                val err = conn.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }
                DiagLog.e("upload.http", "code=$code path=$path body=${err?.take(160)}")
            }
            conn.disconnect()
            code in 200..201
        }.getOrElse {
            DiagLog.e("upload.exception", it.javaClass.simpleName)
            false
        }
    }

    private fun open(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }

    /** 设备标识：优先 ro.serialno，取不到退到型号 + 版本，保证 PC 侧能分目录。 */
    private fun deviceId(): String {
        val serial = runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("getprop", "ro.serialno"))
            p.inputStream.bufferedReader().readText().trim().also { p.waitFor() }
        }.getOrNull().orEmpty()
        if (serial.isNotEmpty() && serial != "unknown") return sanitize(serial)
        return sanitize("${android.os.Build.MODEL}_${BuildConfig.VERSION_NAME}")
    }

    private fun sanitize(s: String): String =
        s.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "unknown-device" }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 供设置页展示：默认窗口会传几个文件、多大。 */
    fun describeWindow(context: Context): String {
        val files = defaultWindowFiles(context)
        val bytes = files.sumOf { it.length() }
        return "%d 个文件，%.1f KB".format(files.size, bytes / 1024.0)
    }
}
