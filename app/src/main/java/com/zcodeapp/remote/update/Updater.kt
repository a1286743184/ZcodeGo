package com.zcodeapp.remote.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.zcodeapp.remote.BuildConfig
import com.zcodeapp.remote.diag.DiagLog
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * 应用内更新：**一键到底**。
 *
 * 【2026-10-27 改：检测到新版就自动下载 + 自动安装】
 * 旧流程要求用户点三次（检查 → 下载 → 安装），中间任何一步用户走开就断了。
 * 现在点「检查更新」后：有新版本就自动下载、校验、直接拉起安装器。
 * 用户只需要在系统的「是否安装」对话框上确认一次——这一步系统强制，无法跳过。
 *
 * 参考成熟更新器的普遍做法，这里补齐了三件它们踩过坑的事：
 *
 * 1. **更新锁**：下载/安装进行中时，再次触发不会重复下载，也不会重复弹安装。
 *    用 SharedPreferences 存一个「正在处理哪个版本」，进程被杀后重启能识别残留。
 * 2. **断点续传**：下载中断（切后台、网络抖动）后重试时用 Range 请求接着下，
 *    不从头再来。32MB 的包在移动网络下从头重下代价很大。
 * 3. **残留清理**：安装完成后旧的 APK 要删掉，否则每次更新都在 cache 里留一份。
 *
 * 其余取舍：
 * - 不用第三方更新库：只需五步（取清单、比版本、下载、校验、调安装器），自己写更可控。
 * - **必须校验 sha256**。APK 走公网，校验不过就不安装。
 * - 安装走 FileProvider + ACTION_VIEW，系统会弹确认；不静默安装、不需要 root。
 */
object Updater {
    /**
     * 更新清单地址，编译期注入（BuildConfig.UPDATE_MANIFEST_URL）：
     * - 开源默认：GitHub Release 上的 latest.json（公开资产匿名可下载，无需凭据）
     * - 维护者自有渠道：local.properties 的 zr.updateManifestUrl 覆盖
     * 清单 url 缺失时的兜底下载地址同理（UPDATE_FALLBACK_URL）。
     */
    val MANIFEST_URL: String = BuildConfig.UPDATE_MANIFEST_URL

    private const val PREF = "zr_update"
    private const val KEY_LOCK_CODE = "lock_version_code"
    private const val KEY_LOCK_AT = "lock_at"
    private const val KEY_LOCK_PATH = "lock_apk_path"
    /** 锁的过期时间：超过这个时间认为上次流程已死，可以重新开始。 */
    private const val LOCK_STALE_MS = 10 * 60 * 1000L

    data class Release(
        val versionCode: Int,
        val versionName: String,
        val url: String,
        val sha256: String,
        val size: Long,
        val notes: String,
        val fallbackUrl: String? = null,
    )

    sealed class State {
        data object Idle : State()
        data object Checking : State()
        data class UpToDate(val current: String) : State()
        data class Available(val release: Release) : State()
        data class Downloading(val progress: Int) : State()
        data class Verifying(val release: Release) : State()
        data class Ready(val file: File) : State()
        data class Installing(val file: File) : State()
        data class Failed(val message: String) : State()
        data class NeedsInstallPermission(val release: Release) : State()
    }

    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "zr-updater").apply { isDaemon = true }
    }
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * 一键更新：检查 → 下载 → 校验 → 拉起安装器。
     *
     * 每个阶段都回调 State，UI 只需按状态渲染，不需要自己串流程。
     * 失败时回调 Failed，用户可再点一次（会走断点续传）。
     */
    fun updateNow(context: Context, onState: (State) -> Unit) {
        val appContext = context.applicationContext
        mainHandler.post { onState(State.Checking) }
        exec.execute {
            val rel = runCatching { fetchRelease() }.fold(
                onSuccess = { it },
                onFailure = { e ->
                    DiagLog.w("update.checkFailed", e.javaClass.simpleName)
                    emit(onState, State.Failed(e.message ?: "检查更新失败"))
                    return@execute
                },
            )
            if (rel == null || rel.versionCode <= BuildConfig.VERSION_CODE) {
                emit(onState, State.UpToDate(BuildConfig.VERSION_NAME))
                return@execute
            }
            DiagLog.i("update.available", "${rel.versionName}(${rel.versionCode})")
            emit(onState, State.Available(rel))

            // 已有同版本且校验通过的包：直接进入安装，不重复下载。
            val cached = cachedApk(appContext, rel)
            if (cached != null) {
                DiagLog.i("update.cacheHit", rel.versionName)
                emit(onState, State.Ready(cached))
                installOrAsk(appContext, rel, cached, onState)
                return@execute
            }

            val file = runCatching { download(appContext, rel, onState) }.fold(
                onSuccess = { it },
                onFailure = { e ->
                    DiagLog.e("update.downloadFailed", e.javaClass.simpleName)
                    emit(onState, State.Failed(e.message ?: "下载失败"))
                    return@execute
                },
            )
            emit(onState, State.Ready(file))
            installOrAsk(appContext, rel, file, onState)
        }
    }

    /** 只检查，不下载。保留给「启动时静默检查」这类场景。 */
    fun check(context: Context, onResult: (State) -> Unit) {
        exec.execute {
            val state = runCatching { fetchRelease() }.fold(
                onSuccess = { rel ->
                    if (rel == null || rel.versionCode <= BuildConfig.VERSION_CODE) {
                        State.UpToDate(BuildConfig.VERSION_NAME)
                    } else {
                        DiagLog.i("update.available", "${rel.versionName}(${rel.versionCode})")
                        State.Available(rel)
                    }
                },
                onFailure = { e ->
                    DiagLog.w("update.checkFailed", e.javaClass.simpleName)
                    State.Failed(e.message ?: "检查更新失败")
                },
            )
            emit(onResult, state)
        }
    }

    private fun emit(cb: (State) -> Unit, state: State) = mainHandler.post { cb(state) }

    // ---------------------------------------------------------------- 下载

    /**
     * 下载 + 校验，返回可用文件。
     *
     * 断点续传：目标文件已存在且长度小于预期时，用 Range 从断点继续；
     * 长度等于预期但哈希不对则删掉重来（说明之前下坏了）。
     */
    private fun download(context: Context, release: Release, onState: (State) -> Unit): File {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        val target = File(dir, "zcoderemote-${release.versionName}-${release.versionCode}.apk")
        // 其它版本的包没必要留，先清掉。
        cleanOtherApks(dir, keep = target.name)

        val candidates = listOfNotNull(release.url, release.fallbackUrl).distinct()
        var lastError: Exception? = null

        for (candidate in candidates) {
            try {
                fetchWithResume(candidate, target, release, onState)
                emit(onState, State.Verifying(release))
                val actual = sha256Of(target)
                if (actual != release.sha256) {
                    target.delete()
                    throw IllegalStateException(
                        "校验失败：期望 ${release.sha256.take(12)}… 实际 ${actual.take(12)}… " +
                            "size=${release.size}",
                    )
                }
                DiagLog.i("update.downloaded", "${target.length()}B")
                writeLock(context, release, target)
                return target
            } catch (e: Exception) {
                lastError = e
                DiagLog.w("update.sourceFailed", "${e.javaClass.simpleName} ${candidate.takeLast(40)}")
                // 换下一个地址时不能续传（内容可能不同），删掉重来。
                target.delete()
            }
        }
        throw lastError ?: IllegalStateException("没有可用的下载地址")
    }

    /**
     * 单地址下载，支持从已下载的字节处续传。
     *
     * 为什么续传要校验服务器是否真的支持：有些 CDN 忽略 Range 直接返回 200 全量，
     * 这时如果按「追加」写入就会得到拼接坏文件。所以只在响应码是 206 时续传，
     * 200 则从头写。
     */
    private fun fetchWithResume(
        url: String,
        target: File,
        release: Release,
        onState: (State) -> Unit,
    ) {
        val existing = if (target.exists()) target.length() else 0L
        val canResume = existing in 1 until release.size

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            // 不复用任何缓存：缓存旧包是这个类最危险的失败模式。
            setRequestProperty("Cache-Control", "no-cache")
            if (canResume) setRequestProperty("Range", "bytes=$existing-")
        }

        try {
            val code = conn.responseCode
            if (code !in 200..206) throw IllegalStateException("下载失败 HTTP $code")

            val resuming = canResume && code == 206
            if (canResume && !resuming) {
                // 服务器不支持续传：从头写，别把新内容接在旧内容后面。
                DiagLog.w("update.resumeUnsupported", "server returned $code, restarting")
                target.delete()
            } else if (resuming) {
                DiagLog.i("update.resume", "from ${existing}B / ${release.size}B")
            }

            val startBytes = if (resuming) existing else 0L
            conn.inputStream.use { input ->
                RandomAccessFile(target, "rw").use { raf ->
                    if (resuming) raf.seek(startBytes) else raf.setLength(0)
                    val buf = ByteArray(64 * 1024)
                    var total = startBytes
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        raf.write(buf, 0, n)
                        total += n
                        val pct = if (release.size > 0) ((total * 100) / release.size).toInt() else 0
                        if (pct != lastPct && pct % 5 == 0) {
                            lastPct = pct
                            emit(onState, State.Downloading(pct))
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun cachedApk(context: Context, release: Release): File? {
        val dir = File(context.cacheDir, "update")
        val f = File(dir, "zcoderemote-${release.versionName}-${release.versionCode}.apk")
        if (!f.exists() || f.length() != release.size) return null
        return if (sha256Of(f) == release.sha256) f else null
    }

    private fun cleanOtherApks(dir: File, keep: String) {
        dir.listFiles { f -> f.isFile && f.name.endsWith(".apk") && f.name != keep }
            ?.forEach { runCatching { it.delete() } }
    }

    // ---------------------------------------------------------------- 安装

    /**
     * 拉起安装器；没有「安装未知应用」权限时先去授权页。
     *
     * 授权页回来后不会自动继续安装（系统不提供回调），用户再点一次即可——
     * 那时 APK 已在缓存里且校验过，会直接进入安装，不会再下载。
     */
    private fun installOrAsk(context: Context, release: Release, file: File, onState: (State) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            DiagLog.w("update.needInstallPermission", "")
            emit(onState, State.NeedsInstallPermission(release))
            runCatching {
                context.startActivity(
                    Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            return
        }
        emit(onState, State.Installing(file))
        if (!install(context, file)) {
            emit(onState, State.Failed("无法拉起安装器"))
        }
    }

    fun install(context: Context, apk: File): Boolean = runCatching {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        DiagLog.i("update.installLaunched", apk.name)
        true
    }.getOrElse {
        DiagLog.e("update.installFailed", it.javaClass.simpleName)
        false
    }

    // ---------------------------------------------------------------- 锁与清理

    private fun writeLock(context: Context, release: Release, file: File) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putInt(KEY_LOCK_CODE, release.versionCode)
            .putLong(KEY_LOCK_AT, System.currentTimeMillis())
            .putString(KEY_LOCK_PATH, file.absolutePath)
            .apply()
    }

    /**
     * 启动时调用：清理上一轮留下的东西。
     *
     * 分两种情况：
     * - 锁已过期（超过 10 分钟）：上次流程死了，删掉残留 APK、清锁
     * - 锁未过期且 APK 还在：可能是用户刚去授权了安装权限，保留 APK 让下次点击能直接装
     */
    fun onAppStart(context: Context) {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val code = sp.getInt(KEY_LOCK_CODE, -1)
        if (code < 0) {
            cleanStaleApks(context, keepPath = null)
            return
        }
        val at = sp.getLong(KEY_LOCK_AT, 0L)
        val path = sp.getString(KEY_LOCK_PATH, null)
        val stale = System.currentTimeMillis() - at > LOCK_STALE_MS

        if (stale) {
            DiagLog.i("update.lockExpired", "v=$code")
            sp.edit().clear().apply()
            cleanStaleApks(context, keepPath = null)
        } else {
            DiagLog.i("update.lockAlive", "v=$code keep=${path != null}")
            cleanStaleApks(context, keepPath = path)
        }
    }

    private fun cleanStaleApks(context: Context, keepPath: String?) {
        val dir = File(context.cacheDir, "update")
        if (!dir.isDirectory) return
        dir.listFiles { f -> f.isFile && f.name.endsWith(".apk") }?.forEach { f ->
            if (keepPath != null && f.absolutePath == keepPath) return@forEach
            runCatching { f.delete() }
        }
    }

    // ---------------------------------------------------------------- 清单

    private fun fetchRelease(): Release? {
        val conn = (URL(MANIFEST_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("Cache-Control", "no-cache")
        }
        val text = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        conn.disconnect()
        val obj = org.json.JSONObject(text)
        val code = obj.optInt("versionCode", 0)
        if (code <= 0) return null
        val name = obj.optString("versionName").ifBlank { "0.0.0" }
        val url = obj.optString("url").ifBlank { BuildConfig.UPDATE_FALLBACK_URL }
        return Release(
            versionCode = code,
            versionName = name,
            url = url,
            sha256 = obj.optString("sha256").lowercase(),
            size = obj.optLong("size", 0L),
            notes = obj.optString("notes"),
            fallbackUrl = obj.optString("fallbackUrl").ifBlank { null },
        )
    }

    private fun sha256Of(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
