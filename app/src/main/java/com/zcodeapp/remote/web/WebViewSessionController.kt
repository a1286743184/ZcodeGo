package com.zcodeapp.remote.web

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.graphics.toArgb
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.zcodeapp.remote.BuildConfig
import com.zcodeapp.remote.ZcodeRemoteApp
import com.zcodeapp.remote.capture.InjectionInstaller
import com.zcodeapp.remote.diag.DiagLog
import com.zcodeapp.remote.capture.NarrowBridge
import com.zcodeapp.remote.event.SourceLeaseCoordinator
import com.zcodeapp.remote.event.TaskReducer
import com.zcodeapp.remote.protocol.Direction
import com.zcodeapp.remote.protocol.JsonValue
import com.zcodeapp.remote.ui.theme.PageTheme
import com.zcodeapp.remote.ui.theme.PageThemeStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * WebView 会话控制器（v2 D4）：WebView 创建/加载/保存/销毁全部收敛于此，
 * 不散落在 composable 重组逻辑中；加载幂等（URL 未变不重载）；
 * 渲染进程崩溃重建有界退避。会话对象与页面状态流由本类统一暴露。
 */
class WebViewSessionController(private val app: ZcodeRemoteApp) {

    var webView: WebView? = null
        private set
    var session: RemoteSession? = null
        private set

    /**
     * WebView 是否还活着（进程内、未销毁）。
     *
     * 用途：Activity 重建时判断要不要 restoreState。保活期间 Activity 常被回收，
     * 但进程与 WebView 都还在——那种情况下重放旧快照会打断正在跑的会话。
     */
    val hasLiveWebView: Boolean get() = webView != null

    val loading = MutableStateFlow(false)
    val pageError = MutableStateFlow<String?>(null)
    val pageState = MutableStateFlow(PageState.IDLE)

    private val _generation = MutableStateFlow(0)

    /**
     * WebView 实例代次：每次「销毁后重建」递增一次。
     *
     * 【2026-10-27 白屏根因】后台巡检发现渲染进程被回收后，旧实现销毁 WebView
     * 再用 Application/Activity context 新建一个——但**新实例从未挂到视图树上**。
     * Compose 的 AndroidView 只在首次组合时调 factory，控制器换了 WebView 它并不知道；
     * 旧 View 已被 destroy 并从 parent 移除，于是 Box 变空，露出页面底色（浅色主题下
     * 就是一片白茫茫）。用户必须退回入口页再点一次链接，走一遍全新的组合才能恢复。
     *
     * 现在把代次暴露给 UI：RemoteScreen 用 key(generation) 包住 AndroidView，
     * 代次一变就重新组合，factory 重新拿到（并挂载）新的 WebView。
     */
    val generation: StateFlow<Int> = _generation

    private val leases = SourceLeaseCoordinator()
    private val reducer = TaskReducer()

    /** 主线程定时器：用于渲染进程崩溃后的退避重建。 */
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastTargetUrl: String? = null
    private var pendingLoadUrl: String? = null
    private var pendingRestore: Bundle? = null
    private var renderGoneCount = 0
    /** 自愈重载次数与上次时间，防止「一探测就重载」的抖动。 */
    private var selfHealCount = 0
    private var lastSelfHealAtMs = 0L
    /** 上次「掉出会话后重新导航」的时间，防止回前台时反复导航。 */
    private var lastSessionRecoverAtMs = 0L
    /** 上次后台健康恢复的时间，防止巡检时反复重建 WebView。 */
    private var lastBgRecoverAtMs = 0L

    /**
     * 后台巡检发现「连接不健康」时记下的原因，等到回前台再统一处理。
     *
     * 为什么不在后台直接重载：后台重载用户看不到效果，代价却是丢会话、丢草稿；
     * 而且重载后又要重新握手，握不上又触发下一轮重载（真机日志里 90 秒一轮）。
     * 回前台后页面自己就有完整恢复链路，真的需要兜底时由
     * [probeAndRecoverSession] 统一判定一次。
     */
    private var pendingBgRecoverReason: String? = null

    /** 待完成的页面内任务跳转（通知点击用），页面重载后自动重试。 */
    private var pendingJumpTaskId: String? = null
    private var pendingJumpAtMs: Long = 0L

    /**
     * 页面是否已经完成过一次加载。
     *
     * 用途：返回键在页面还没就绪时不该等探针（那会拿到 no-answer 再让用户白等），
     * 直接按「最外层」处理。它只看「有没有 onPageFinished 过」，
     * 不代表页面内容正确——内容正确与否由 nav.js 自己判定。
     */
    private var pageLoaded = false

    /**
     * 返回判定是否正在进行。
     *
     * 连点两次返回时，第二次的 evaluateJavascript 可能落在第一次回调之前。
     * 两个都判「页面消化了」会连退两层（会话 → 列表 → 设置），用户会觉得
     * 「按一次退了两屏」。这里用一次性闸门挡住。
     */
    private var backInFlight = false

    /**
     * 「再按一次退出」的上次提示时刻。
     *
     * 三条返回入口（返回键、右滑、壳子）共享这一个时间戳：
     * 用户在两个入口之间交替按也不会被连退两次。
     */
    private var lastExitPromptAtMs = 0L

    /**
     * 本次导航的发起方标记。由发起处设置、在 onPageStarted 里消费。
     *
     * 用途：区分「我们让页面重载」与「页面自己 reload」。
     * 后者在 0.3.5 日志里出现过（page.start 前没有任何原生触发），
     * 是页面 recoverConnection() 失败后的自保动作，我们拦不住；
     * 但如果误记成我们触发的，就会把排查方向带偏。
     */
    private var pendingNavOrigin: String? = null

    /**
     * 同步页面配色到原生壳。
     *
     * 为什么要在页面加载完成后单独做一次：页面主题存在 localStorage，
     * 首帧可能先用默认主题渲染，等脚本跑完才切到用户设置。只在 onPageFinished
     * 读一次就够了，之后用户改主题会触发页面重绘但 URL 不变，靠定时复查太浪费；
     * 这里额外在页面可见时（onResume）再读一次，覆盖「切后台改了主题再回来」。
     */
    fun syncPageTheme() {
        val wv = webView ?: return
        wv.evaluateJavascript(PageThemeProbeScript.build()) { raw ->
            val text = decodeJsStringResult(raw)
            if (text.isEmpty()) return@evaluateJavascript
            val obj = runCatching {
                JsonValue.parse(text) as? JsonValue.Obj
            }.getOrNull() ?: return@evaluateJavascript
            applyPageTheme(obj.str("bg"), obj.str("fg"), obj.str("cls"), "probe")
        }
    }

    /**
     * 是否正处于 OAuth 授权流程中。
     *
     * 进入授权域即置位；回到远控页（授权完成或用户放弃）即清除。
     * 置位期间，授权域内的**所有路径**都留在 App 内，不做外跳——
     * 授权页登录成功后会在自己的域内跳几次才 302 回远控页，
     * 只要中途有一次被判成外部交给系统 Chrome，回调就再也回不到 App。
     */
    private var authFlowActive = false

    /** 网页发起 `<input type=file>` 时等待回填的回调。 */
    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null

    /** 是否正在等系统文件选择器返回。用来在 onPause 时跳过 WebView 挂起。 */
    private var filePickInProgress = false

    /**
     * 本次 onPause 是否属于「后台保活」场景。
     *
     * 由上层在切后台前用 setKeepAliveIntent 声明：保活期间我们不挂起 WebView、
     * 并让页面认为自己是可见的，否则页面会自己把 WS 关掉（见 onPause 的说明）。
     *
     * 注意：这个标志在**整个保活期间**保持为真（不只是 onPause 那一瞬），
     * 因为后台健康巡检也要用它判断「现在是不是应该替页面兜底」。
     * 由上层在回到前台、或用户关掉保活时清除。
     */
    private var keepAliveRequested = false

    /** 网页申请相机/麦克风时等待权限结果。 */
    private var pendingPermissionRequest: PermissionRequest? = null

    /**
     * 文件选择与运行时权限需要 Activity 才能发起，控制器本身没有这些能力。
     * 由 MainActivity 在 onCreate 时挂上来；为 null 时相关能力静默降级（返回 false）。
     */
    var fileChooserHost: FileChooserHost? = null

    /** 把网页的 file chooser 约束交给 Activity 去拉起系统选择器。 */
    interface FileChooserHost {
        fun launch(params: WebChromeClient.FileChooserParams)
        fun requestPermissions(permissions: Array<String>)
    }

    /** Activity 拿到选择结果后回填。uri 为 null 表示用户取消。 */
    fun onFileChooserResult(uris: Array<Uri>?) {
        val cb = pendingFileCallback ?: return
        pendingFileCallback = null
        filePickInProgress = false
        webView?.removeCallbacks(filePickWatchdog)
        // 先解除屏蔽再回填：回填会同步触发页面的 change 处理与上传，
        // 那一刻页面必须是「可见且已配对」的。
        webView?.let { setFilePickGuard(it, false) }
        // 取消选择：直接回填 null，不需要等链路。
        if (uris == null || uris.isEmpty()) {
            cb.onReceiveValue(uris)
            DiagLog.i("file.picked", "count=0")
            return
        }
        DiagLog.i("file.picked", "count=${uris.size}")
        // 关键：不要立刻把文件交给页面。
        //
        // 即使守卫生效，页面也可能因为别的原因（真实后台、系统回收）刚经历过一次挂起，
        // 此刻 transport 仍在重连窗口里。这个窗口内页面发出的上传请求会被
        // sendPayload 判成 unavailable 直接丢弃，而且不会重试——文件挂在输入框上方
        // 一直卡着，正是用户报的现象。
        //
        // 所以先把文件握在原生侧，等页面报告「已配对 + WS 开着」再交出去。
        // 交出去的时机对了，页面的 change 处理与上传就能一次成功。
        deliverWhenReady(uris, cb)
    }

    /**
     * 等页面链路就绪后再把选择结果交给它。
     *
     * 判据来自页面自己的状态（__zrWsState / __zrFilePick.snapshot），不是猜时间：
     * - 页面加载中（bridge 还没装好）→ 继续等
     * - WS 未 open → 继续等（页面正在重连）
     * - 超时 → 仍然交付，并把现场写进日志，让下次能看出是「等不到」而不是「没等」
     */
    private fun deliverWhenReady(uris: Array<Uri>, cb: ValueCallback<Array<Uri>>) {
        val wv = webView
        if (wv == null) {
            cb.onReceiveValue(uris)
            return
        }
        val startedAt = System.currentTimeMillis()
        var delivered = false

        val probe = object : Runnable {
            override fun run() {
                if (delivered) return
                val elapsed = System.currentTimeMillis() - startedAt
                wv.evaluateJavascript(
                    "(window.__zrFilePick ? window.__zrFilePick.snapshot() : '{\"err\":\"no-guard\"}')",
                ) { raw ->
                    if (delivered) return@evaluateJavascript
                    val text = decodeJsStringResult(raw)
                    val verdict = FileDeliveryGate.evaluate(text)

                    if (verdict.ready) {
                        delivered = true
                        DiagLog.i("file.deliver", "ready after ${elapsed}ms reason=${verdict.reason} state=$text")
                        cb.onReceiveValue(uris)
                        return@evaluateJavascript
                    }
                    if (elapsed >= FILE_DELIVER_TIMEOUT_MS) {
                        // 等不到也要交：用户选的文件不能丢。同时把现场记下来，
                        // 下次看日志就能区分「链路一直没起来」与「等够了但还没好」。
                        delivered = true
                        DiagLog.w(
                            "file.deliver.timeout",
                            "after ${elapsed}ms reason=${verdict.reason} state=$text",
                        )
                        cb.onReceiveValue(uris)
                        return@evaluateJavascript
                    }
                    wv.postDelayed(this, FILE_DELIVER_POLL_MS)
                }
            }
        }
        wv.post(probe)
    }

    /**
     * 常驻武装开关：页面一加载完成就调，此后整个文档生命周期有效。
     *
     * 只驱动 hold 标志；文件选择走 setFilePickGuard（另一对 begin/end），
     * 两者在脚本内部独立，互不干扰（选文件期间切后台不会被误判成可见）。
     *
     * 为什么不再有 keepAliveBegin/End 那一对：常驻 hold 已经覆盖整个生命周期，
     * 再叠一个「按生命周期反复拨」的标志既冗余、又容易漏清（一旦置真就没人撤）。
     * 页面重载后 onPageFinished 会按当前设置再对齐一次，所以 hold 能自愈。
     *
     * 脚本内部还会按连接健康动态生效（ws=closed 时自动放行），
     * 所以这里不需要也不应该由原生去判断「现在能不能挡」。
     *
     * 【2026-10-30 取证修正】必须回读真实结果。
     *
     * 旧实现调完 evaluateJavascript 就打印 `web.hold fn=holdBegin`——那行日志是
     * **无条件打印的**，脚本不存在（注入失败、document-start 未生效、页面换了 origin）
     * 时照样打印。真机日志里全是 `fn=holdBegin`，但页面仍然在回前台时自己
     * recoverConnection()，说明「打印了」不等于「装上了」。
     *
     * 现在把 shield 的实际状态读回来记日志：installed / hold / guard / wsHealthy。
     * 没有这一条，任何关于屏蔽是否生效的判断都是猜测。
     */
    private fun setHoldShield(wv: WebView, active: Boolean) {
        val fn = if (active) "holdBegin" else "holdEnd"
        wv.evaluateJavascript(
            "(function(){var s=window.__zrVisibilityShield;" +
                "if(!s)return 'MISSING';" +
                "s.$fn();" +
                "return JSON.stringify({hold:s.isHold(),guard:s.isActive()," +
                "wsHealthy:s.snapshot?JSON.parse(s.snapshot()).wsHealthy:null});})()",
        ) { raw ->
            val text = decodeJsStringResult(raw)
            DiagLog.i("web.hold", "fn=$fn result=$text")
        }
    }

    /** 开关「文件选择期间保持可见」的守卫。 */
    private fun setFilePickGuard(wv: WebView, active: Boolean) {
        wv.evaluateJavascript(
            "(window.__zrFilePick && window.__zrFilePick.${if (active) "begin" else "end"}())",
            null,
        )
        DiagLog.i("file.guard", "active=$active")
        if (active) {
            wv.postDelayed(filePickWatchdog, FILE_PICK_GUARD_TIMEOUT_MS)
        } else {
            wv.removeCallbacks(filePickWatchdog)
        }
    }

    /** 看门狗：超时仍未拿到选择结果就强制解除守卫，避免长期抑制挂起。 */
    private val filePickWatchdog = Runnable {
        if (filePickInProgress) {
            DiagLog.w("file.guard.timeout", "clearing stale guard")
            filePickInProgress = false
            webView?.let { wv ->
                wv.evaluateJavascript("(window.__zrFilePick && window.__zrFilePick.end())", null)
            }
        }
    }

    /** Activity 拿到权限结果后回填。 */
    fun onPermissionResult(permissions: Array<String>, grantResults: IntArray) {
        val req = pendingPermissionRequest ?: return
        pendingPermissionRequest = null
        val wanted = req.resources.filter {
            it == PermissionRequest.RESOURCE_VIDEO_CAPTURE ||
                it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
        }
        val allGranted = permissions.isNotEmpty() &&
            grantResults.isNotEmpty() &&
            grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (allGranted) req.grant(wanted.toTypedArray()) else req.deny()
    }

    private fun trackAuthFlow(url: String) {
        authFlowActive = when {
            UrlPolicy.isOAuthAuthorizeUrl(url) -> true
            // 授权域内的后续跳转继续算在流程里
            UrlPolicy.isAuthHostUrl(url) && authFlowActive -> true
            // 回到远控页：流程结束
            UrlPolicy.isRemotePageUrl(url) -> false
            else -> authFlowActive
        }
    }

    private val bridge = NarrowBridge(
        onWs = { dir: Direction, text: String -> session?.onWsText(dir, text) },
        onHealth = { state, reason, detail -> session?.onBridgeHealth(state, reason, detail) },
        onDom = { candidate, taskHint, _ -> session?.onDomCandidate(candidate, taskHint) },
        onDiag = { level, code, text, tap, tapAgeMs -> onPageDiag(level, code, text, tap, tapAgeMs) },
        // 页面主题变化实时回传：用户在网页右上角调色板里切换深浅色时，
        // 页面不重载、onPageFinished 不会再触发，只有这条链路能让壳层跟上。
        onTheme = { bg, fg, cls -> applyPageTheme(bg, fg, cls, "watch") },
    )

    /**
     * 把页面配色写进 PageThemeStore。两条来源共用这一处：
     * 桥消息（实时）与 evaluateJavascript 探测（页面加载完成时兜底）。
     */
    private fun applyPageTheme(bg: String?, fg: String?, cls: String?, source: String) {
        val background = PageThemeStore.parseColor(bg)
        val foreground = PageThemeStore.parseColor(fg)
        if (background == null || foreground == null) {
            DiagLog.w("theme.probe.skip", "src=$source bg=$bg fg=$fg")
            return
        }
        val theme = PageTheme(background = background, foreground = foreground)
        val changed = PageThemeStore.update(app, theme)
        if (changed) {
            DiagLog.i(
                "theme.sync",
                "src=$source dark=${theme.isDark} cls=$cls " +
                    "bg=#${Integer.toHexString(background.toArgb()).takeLast(6)}",
            )
        }
    }

    /**
     * 页面侧自报异常落盘。
     *
     * 这是「点击没反应」类故障唯一的直接证据：页面 JS 抛错不会触发 WebView 的任何回调，
     * 只靠原生回调永远看不到。tap 与 tapAgeMs 用来把错误和「用户刚点了哪里」对上。
     */
    private fun onPageDiag(level: String, code: String, text: String, tap: String?, tapAgeMs: Long?) {
        val detail = buildString {
            append("code=").append(code)
            if (!tap.isNullOrBlank()) {
                append(" tap=").append(tap)
                tapAgeMs?.let { append(" tapAge=").append(it).append("ms") }
            }
            append(" text=").append(text)
        }
        if (level == "warn") DiagLog.w("page.diag", detail) else DiagLog.e("page.diag", detail)
    }

    /**
     * G1 诊断快照：桥消息按 kind 的到达计数 + 管线健康。仅含类别与数量，不含任何正文。
     * debug 构建同时写一条 logcat，便于真机验证「桥消息真的到达原生」。
     */
    fun diagnostics(): String {
        val h = session?.pipeline?.health()
        val text = "bridge=${bridge.arrivalCounts} rpcComplete=${h?.rpcComplete} " +
            "discarded=${h?.rpcDiscarded} decodeFailures=${h?.decodeFailures} " +
            "needsResync=${h?.needsResync} pageState=${pageState.value} " +
            "nav=${lastNavState}"
        if (BuildConfig.DEBUG) android.util.Log.i(DIAG_TAG, text)
        return text
    }

    /** 最近一次读到的页面层级（nav.js 的 state）。诊断面板与日志都用它。 */
    private var lastNavState: String = "-"

    /**
     * 读一次页面层级并落到日志。
     *
     * 什么时候调：页面加载完成、以及每次返回判定之前。
     * 为什么值得单独记一条：用户报「按返回没反应」时，唯一的直接证据就是
     * 「页面当时认为自己在哪一层」。没有这条，只能靠猜是脚本没注入、还是层级判错。
     */
    fun logNavState(reason: String) {
        val wv = webView ?: return
        wv.evaluateJavascript(PageNavScript.state()) { raw ->
            val text = BackRouter.decode(raw)
            if (text.isEmpty()) {
                lastNavState = "missing"
                DiagLog.w("nav.state", "reason=$reason script-missing")
                return@evaluateJavascript
            }
            lastNavState = text
            DiagLog.i("nav.state", "reason=$reason $text")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun getOrCreate(context: Context): WebView {
        webView?.let { cached ->
            (cached.parent as? ViewGroup)?.removeView(cached)
            return cached
        }
        val wv = KeepAliveWebView(context) { keepAliveRequested }
        wv.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        wv.overScrollMode = WebView.OVER_SCROLL_NEVER
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = "$userAgentString ZcodeRemote/${BuildConfig.VERSION_NAME}"
        }
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        // 【2026-10-31 关键修复：不让渲染进程因「不可见」被降级】
        //
        // 真机日志证明 App 切后台后页面被冻结：定时器不再跑、WS 的 onmessage
        // 也不再派发（`rxAge` 从 392s 一路涨到 1293s 且从不回落），
        // 但 `evaluateJavascript` 仍然能执行——这说明渲染进程活着，只是被挂起了。
        //
        // 我们之前只骗过了页面 JS 读到的 `document.visibilityState`，
        // 那改不了 Chromium 内部对「这个 WebView 是否可见」的判断。
        // 真正影响调度的是渲染进程优先级策略：
        //
        // `setRendererPriorityPolicy(IMPORTANT, waived = false)`
        // 第二个参数是「不可见时是否放弃 IMPORTANT 优先级」。
        // 传 false = 即使 View 不可见也保持 IMPORTANT，Chromium 就不会
        // 把它当成可回收/可冻结的后台渲染进程。
        //
        // 这是保活能成立的物理前提：不设它，页面在后台必然被降级，
        // 后面所有心跳、重连、通知逻辑都建立在一个不再被调度的页面上。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        }
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(wv, false)
        wv.webChromeClient = object : WebChromeClient() {
            /**
             * 网页 `<input type="file">`（上传附件、选图等）必须由原生接管，
             * 否则点「上传附件」在 WebView 里完全没有反应——这是实测确认的故障。
             *
             * fileChooserParams 里有 accept / multiple / capture 等约束，
             * 这里原样转成 ACTION_GET_CONTENT 的 type 与 extra 交回上层 Activity，
             * 上层用 ActivityResultLauncher 拿到 Uri 后再回传给回调。
             */
            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams,
            ): Boolean {
                // 上一次选择还没结束就又被触发：先取消上一次，否则页面会永远等不到结果。
                pendingFileCallback?.onReceiveValue(null)
                pendingFileCallback = filePathCallback
                filePickInProgress = true
                // 选择期间屏蔽「切到后台」信号（见 file-pick-guard.js 的说明）：
                // 系统选择器会盖住 App，页面若据此挂起 transport，回来时上传请求会被丢弃。
                setFilePickGuard(webView, true)
                return runCatching {
                    fileChooserHost?.launch(fileChooserParams)
                    true
                }.getOrElse {
                    pendingFileCallback = null
                    filePickInProgress = false
                    setFilePickGuard(webView, false)
                    false
                }
            }

            /**
             * 网页申请的运行时权限（定位、麦克风、摄像头等）。
             * 远控页里只用得到相机与麦克风；这里只放行明确的几类，
             * 其它一律拒绝，避免页面任意索取权限。
             */
            override fun onPermissionRequest(request: PermissionRequest) {
                val wanted = request.resources.filter {
                    it == PermissionRequest.RESOURCE_VIDEO_CAPTURE ||
                        it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
                }
                if (wanted.isEmpty()) {
                    request.deny()
                    return
                }
                val permissions = buildList {
                    if (wanted.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
                        add(android.Manifest.permission.CAMERA)
                    }
                    if (wanted.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                        add(android.Manifest.permission.RECORD_AUDIO)
                    }
                }
                if (permissions.all { app.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }) {
                    request.grant(wanted.toTypedArray())
                } else {
                    pendingPermissionRequest?.deny()
                    pendingPermissionRequest = request
                    fileChooserHost?.requestPermissions(permissions.toTypedArray())
                }
            }

            /**
             * 页面用 `window.open(authorizeUrl, '_blank')` 发起 OAuth（web 模式下
             * openExternal 就退化成 window.open）。这里返回 false，把请求交回
             * shouldOverrideUrlLoading，由主文档自己导航到授权页。
             *
             * 不再新开子 WebView：实测（2026-10-26）子 WebView 从未被挂到任何 ViewGroup，
             * CDP 里是 attached=false / empty=true / never_attached=true，用户完全看不到
             * 授权页，登录必然卡死。而页面自己的 OAuth 状态在 3.16.3 里存在 localStorage
             * （键 `oauth_pending_logins`），同一标签页导航即可保留，不需要另开窗口。
             */
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message,
            ): Boolean = false
        }
        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                loading.value = true
                pageError.value = null
                // 新文档开始加载：页面层级还没成形，返回键此时应走「最外层」路径，
                // 不要去问一个还没有 nav.js 的文档。
                pageLoaded = false
                // 归因：这次加载是我们发起的，还是页面自己发起的？
                //
                // 0.3.5 真机日志里有一类加载找不到任何原生触发源：
                //   18:25:22.870 page.finished → 18:25:27.019 page.start
                // 中间没有 bg.recover、没有 session.recover、没有 nav.inapp。
                // 页面 bundle 里有 `reloadPage:()=>{window.location.reload()}`，
                // 说明这是页面自己在 recoverConnection() 失败后刷的。
                //
                // 这个标记必须在最前面消费掉：谁发起加载谁负责打日志，
                // 否则「页面自刷」会被误记成「原生触发」。
                val origin = pendingNavOrigin
                pendingNavOrigin = null
                DiagLog.i("page.start", "${DiagLog.safeUrl(url)} origin=${origin ?: "PAGE"}")
                if (url != null) trackAuthFlow(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                loading.value = false
                pageLoaded = true
                CookieManager.getInstance().flush()
                DiagLog.i("page.finished", DiagLog.safeUrl(url))
                if (url != null) trackAuthFlow(url)
                // 页面真正加载完成后，自愈计数归零：说明页面是活的。
                selfHealCount = 0
                // 常驻屏蔽重新武装：页面重载会产生新文档，document-start 注入时
                // 已经按当时的设置带入了初值，但用户可能在中途改过开关，
                // 这里按当前设置再对齐一次（幂等，重复调用没有副作用）。
                view?.let { setHoldShield(it, app.settings.backgroundListening) }
                // 无感重连：桌面端持有登录态时，页面会停在「重新连接」页等待手动点击。
                // 只在明确的连接/失败页面上跑，正常会话页不会触发（脚本内自行判定）。
                view?.evaluateJavascript(AutoReconnectScript.build(), null)
                // 通知点击带来的跳转意图：页面每次加载完成都重试一次，直到成功或过期。
                view?.let { retryPendingJump(it) }
                // 页面加载完成后，把「待确认断开」按宽限期复核一次：
                // 重载/桥代次切换引起的 close 不该让用户看到红条。
                view?.postDelayed({ session?.confirmClosedIfStillDown() }, CLOSE_RECHECK_MS)
                // 加载完也可能是「假活」：React 挂上了但 terminal 已经终结。
                // 等宽限期过后再判，避免把正在建连的正常页面误重载。
                view?.postDelayed({ probeAndRecoverIfDead(view, "loaded") }, LIVENESS_AFTER_LOAD_MS)
                // 页面配色同步：首帧可能用的是默认主题，加载完成后才是用户设置的那套。
                syncPageTheme()
                // 层级快照：这一行是「按返回没反应」类问题的第一手证据。
                logNavState("loaded")
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                val url = request.url.toString()
                return when (val verdict = UrlPolicy.resolve(url, allowAuthHosts = authFlowActive)) {
                    is UrlPolicy.Verdict.InApp -> {
                        // 授权页与回调都留在同一个主文档里导航：页面的 OAuth 状态在
                        // localStorage（`oauth_pending_logins`），同标签页导航不会丢；
                        // 回调带 code 回到远控页时由页面自己完成 token 交换。
                        DiagLog.i("nav.inapp", DiagLog.safeUrl(verdict.url))
                        pendingNavOrigin = "nav-inapp"
                        view.loadUrl(verdict.url)
                        true
                    }
                    UrlPolicy.Verdict.External -> {
                        DiagLog.w("nav.external", DiagLog.safeUrl(url))
                        runCatching { view.context.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                        true
                    }
                    UrlPolicy.Verdict.Invalid -> {
                        DiagLog.w("nav.invalid", DiagLog.safeUrl(url))
                        true
                    }
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                // 子资源（脚本/样式/接口）失败以前完全不记：页面可能因此缺脚本、
                // 按钮绑不上事件，用户看到的就是「点了没反应」，而我们手里什么都没有。
                val main = request?.isForMainFrame == true
                DiagLog.w(
                    "page.error",
                    "main=$main code=${error?.errorCode} url=${DiagLog.safeUrl(request?.url?.toString())}" +
                        " desc=${error?.description}",
                )
                if (main) {
                    loading.value = false
                    pageError.value = error?.description?.toString().orEmpty().ifEmpty { "load error" }
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?,
            ) {
                // 4xx/5xx 子资源同样会让页面功能残缺，必须留下状态码与路径。
                DiagLog.w(
                    "page.http",
                    "main=${request?.isForMainFrame == true} status=${errorResponse?.statusCode}" +
                        " url=${DiagLog.safeUrl(request?.url?.toString())}",
                )
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: android.net.http.SslError?) {
                handler?.cancel()
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                renderGoneCount++
                DiagLog.e(
                    "render.gone",
                    "count=$renderGoneCount crashed=${detail.didCrash()} " +
                        "priority=${detail.rendererPriorityAtExit()} target=${DiagLog.safeUrl(lastTargetUrl)}",
                )
                if (renderGoneCount > MAX_RENDER_GONE) {
                    pageError.value = "renderer gone repeatedly"
                    return true
                }
                // 【2026-10-27 白屏根因】这里和 recoverFromBackground 是同一类错误：
                // 销毁旧实例后，新实例没人挂到视图树上（Compose 不会因为控制器换实例
                // 而重新 factory）。现在统一走代次机制——销毁 + 递增 generation，
                // 由 RemoteScreen 的 key(generation) 重新组合并挂载。
                val target = lastTargetUrl
                destroyWebViewOnly()
                if (target != null) {
                    pendingLoadUrl = target
                    pendingRestore = null
                    // 退避后再递增代次，避免渲染进程连续崩溃时把重组打得过密。
                    mainHandler.postDelayed({
                        _generation.value += 1
                    }, RENDER_BACKOFF_MS * renderGoneCount)
                } else {
                    _generation.value += 1
                }
                return true
            }
        }

        // 注入时就把「常驻可见性屏蔽」的默认值带进页面。
        //
        // 【2026-10-30 关键修正】旧实现在 onPause 里才调 keepAliveBegin 装屏蔽，
        // 但 Activity 暂停时 Chromium 已经把 visibilitychange(hidden) 派发完了，
        // 脚本永远晚一拍——这是竞态，所以表现是「有时候还行、有时候刷好几次」。
        // 现在页面脚本跑起来之前就武装好，不存在窗口期。
        InjectionInstaller.install(wv, holdDefault = app.settings.backgroundListening)
        NarrowBridge.attach(wv, bridge)
        webView = wv

        pendingRestore?.let { bundle ->
            pendingRestore = null
            restoreStateInternal(bundle)
        }
        pendingLoadUrl?.let { target ->
            pendingLoadUrl = null
            pendingNavOrigin = "pending"
            wv.loadUrl(target)
        }
        return wv
    }

    fun open(url: String): Boolean {
        val verdict = UrlPolicy.resolve(url)
        if (verdict !is UrlPolicy.Verdict.InApp) return false
        val target = verdict.url
        app.links.record(target)
        // 【2026-10-27 关键修正】同一连接已经有活会话时不要重建。
        //
        // 旧实现无条件 startSession：Activity 因保活被回收后重建，RemoteScreen 的
        // LaunchedEffect 会再走一次 open()，把正在跑的事件管线 dispose 掉重来。
        // 页面 WS 还在，但原生侧的任务订阅被重置，通知会断一段时间——
        // 正是「保活了却收不到提醒」的又一环。
        // 判据用 connectionId（URL 里的连接标识），不是整条 URL：
        // URL 可能带时效参数，但连接身份没变就不该重建。
        if (session?.connectionId != UrlPolicy.connectionId(target)) {
            startSession(target)
        }
        lastTargetUrl = target
        val wv = webView
        if (wv == null) {
            pendingLoadUrl = target
            pendingRestore = null
        } else {
            val current = wv.url?.let { UrlPolicy.resolve(it) as? UrlPolicy.Verdict.InApp }?.url
            if (current != target) {
                renderGoneCount = 0
                pendingNavOrigin = "open"
                wv.loadUrl(target)
            } else {
                // 【2026-10-27 修复】URL 相同就什么都不做，这是「退出再进来还是点不动」的直接原因。
                // 页面进入「假活」状态（React 还在渲染，但内部 transport 已 dispose）后，
                // 重进远控页走的正是这条分支：不重载、不探测，把死页面原样还给用户。
                // 现在同 URL 也要做一次存活探测，判定已死才重载——探测本身很轻（一次 evaluateJavascript），
                // 且判据保守，不会打断正在重连的正常页面。
                probeAndRecoverIfDead(wv, "reenter")
            }
        }
        return true
    }

    /**
     * 存活探测 + 自愈重载。
     *
     * 探测结果只用于两件事：写日志、必要时重载。它不改变页面状态，也不做任何点击。
     * 重载会丢掉页面内的草稿，所以判据必须保守（见 LivenessProbeScript 的说明）。
     */
    private fun probeAndRecoverIfDead(wv: WebView, reason: String) {
        wv.evaluateJavascript(LivenessProbeScript.build()) { raw ->
            // evaluateJavascript 把 JS 字符串再按 JSON 编码一次返回，形如 "{\"dead\":true,...}"。
            // 直接按未转义的子串匹配会永远匹配不上，这里先真正解码成原始文本。
            val verdict = decodeJsStringResult(raw)
            val dead = verdict.contains("\"dead\":true")
            DiagLog.i("page.probe", "reason=$reason dead=$dead $verdict")
            if (!dead) return@evaluateJavascript
            val now = System.currentTimeMillis()
            if (now - lastSelfHealAtMs < SELF_HEAL_MIN_INTERVAL_MS) {
                DiagLog.w("page.heal.skip", "reason=$reason too soon since last heal")
                return@evaluateJavascript
            }
            lastSelfHealAtMs = now
            selfHealCount++
            DiagLog.w("page.heal", "reason=$reason count=$selfHealCount target=${DiagLog.safeUrl(lastTargetUrl)}")
            if (selfHealCount > MAX_SELF_HEAL) {
                // 反复自愈说明页面自己起不来了，交给用户决定（重试按钮）。
                pageError.value = "page stopped responding after $MAX_SELF_HEAL reloads"
                return@evaluateJavascript
            }
            renderGoneCount = 0
            pendingNavOrigin = "selfheal"
            wv.reload()
        }
    }

    /** 解开 evaluateJavascript 返回的 JSON 字符串字面量；失败时原样返回，不影响判据。 */
    private fun decodeJsStringResult(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text == "null") return ""
        return runCatching {
            val v = JsonValue.parse(text)
            (v as? JsonValue.Str)?.value ?: text
        }.getOrDefault(text)
    }

    fun reload() {
        webView?.reload()
    }

    fun saveState(outState: Bundle) {
        webView?.saveState(outState)
        lastTargetUrl?.let { outState.putString(KEY_URL, it) }
    }

    fun restoreState(savedState: Bundle?) {
        savedState ?: return
        val wv = webView
        if (wv == null) {
            pendingRestore = savedState
        } else {
            restoreStateInternal(savedState)
        }
    }

    private fun restoreStateInternal(savedState: Bundle) {
        lastTargetUrl = savedState.getString(KEY_URL)
        val wv = webView ?: return
        val restored = wv.restoreState(savedState)
        if (restored == null || wv.url == null) {
            lastTargetUrl?.let {
                pendingNavOrigin = "restore"
                wv.loadUrl(it)
            }
        }
        lastTargetUrl?.let { startSession(it) }
    }

    private fun startSession(target: String) {
        session?.dispose()
        pageState.value = PageState.IDLE
        session = RemoteSession(
            connectionId = UrlPolicy.connectionId(target),
            connectionUrl = target,
            reducer = reducer,
            dispatcher = app.dispatcher,
            leases = leases,
            onStateChange = { pageState.value = it },
        )
    }

    /**
     * 切后台。
     *
     * 【2026-10-30 重写】保活开启时**不暂停 WebView**，可见性屏蔽由常驻 hold 接管。
     *
     * 历史：旧实现在这里调 keepAliveBegin 装屏蔽。但 Activity 暂停时 Chromium
     * 已经把 `visibilitychange(hidden)` 派发完了，脚本永远晚一拍——这是竞态，
     * 所以表现是「有时候还行、有时候刷好几次」。现在屏蔽在 document-start 就武装
     * （见 InjectionInstaller 的 holdDefault），这里只负责不挂起 WebView。
     *
     * 不调 webView.onPause() 的原因：那会连 JS 定时器一起挂起，
     * 页面即使不被 suspend 也收不到消息，等于没保活。
     *
     * 代价：后台期间页面 JS 照常跑，会多耗一点电。这是「息屏也能收提醒」的必要成本，
     * 且只在用户显式开启保活时发生。
     */
    fun onPause() {
        // 文件选择期间不暂停 WebView。
        //
        // 系统选择器盖住 App 时 Activity 会 onPause，这里如果照常 pause WebView，
        // 页面会同时收到「窗口不可见」与「JS 被挂起」两个信号，transport 必定挂起；
        // 选完回来就要重建 bridge，而上传请求正好落在重建窗口里被丢弃。
        // 选择文件通常只有几秒到几十秒，保持 WebView 活着比让它挂起再恢复更省事也更稳。
        if (filePickInProgress) {
            DiagLog.i("web.pause.skip", "reason=file-pick")
            return
        }
        if (keepAliveRequested) {
            // 保活期：不挂起 WebView，页面 JS 继续跑心跳。
            //
            // 【2026-10-30】不再在这里调 keepAliveBegin。
            //
            // 常驻 hold 屏蔽（setKeepAliveIntent / onPageFinished 时武装）已经覆盖
            // 整个文档生命周期，这里再置一次 keepAlive 标志是冗余的；更糟的是
            // keepAlive 一旦置真就再没人清（onResume 已经不再 keepAliveEnd），
            // 它会变成一个永久为真的旁路开关。
            //
            // 两者现在都受连接健康门控（见 ShieldPolicy），所以即使残留也不会
            // 造成「断了还挡」的死锁；但少一个不该存在的状态总是更稳。
            CookieManager.getInstance().flush()
            DiagLog.i("web.pause.skip", "reason=keepalive")
            return
        }
        webView?.onPause()
        CookieManager.getInstance().flush()
    }

    fun onResume() {
        // 【2026-10-30 关键修正：顺序反了，而且不该无条件撤屏蔽】
        //
        // 旧实现先 setVisibilityShield(active=false) 撤掉屏蔽，再 webView.onResume()。
        // 撤屏蔽那一瞬间，页面读到的是**真实**的 visibilityState（此刻还是 hidden），
        // 于是页面的生命周期助手走 a() → 置「已 suspend」标志 i=true；
        // 紧接着 WebView 变可见 → o('visible') → i 为真 → recoverConnection()
        // → reconnectNow() 干净关掉健康 socket（日志里的 WS_CLOSED_CLEAN）
        // → 重握手 + 重拉工作区（实测约 10 秒）→ 失败再整页 reload。
        //
        // 这就是「每次回来等十几秒然后刷新、输入的字全没了」的完整链路，
        // 而且完全是我们自己把开关拨了一下造成的——连接本来一直是好的
        // （真机日志里 55 分钟后台 bg.health 全 healthy）。
        //
        // 现在：**先让 WebView 真正恢复可见，再动屏蔽**。顺序调过来之后，
        // 页面在撤屏蔽的那一刻读到的已经是 visible，不会置 i 标志。
        // 而且 keepAliveRequested 为真时**根本不撤**——常驻 hold 屏蔽接管一切，
        // 页面在整个生命周期里都不该看到 hidden。
        webView?.onResume()
        if (keepAliveRequested) {
            // 保活开着：屏蔽不撤。hold 屏蔽按连接健康动态生效，
            // 连接真断时它会自动放行，页面照样能恢复。
            DiagLog.i("web.shield", "resume keepalive held")
        }
        // 屏蔽不是万能的（进程被杀重建、渲染进程崩溃、页面自身走了别的分支）。
        // 回前台时探测一次：掉回「选择电脑」页就重新导航回远控 URL。
        // 判据保守，正常会话页绝不会被碰（见 SessionRecoveryGate）。
        probeAndRecoverSession()
    }

    /**
     * 回前台时确认页面还在会话里；掉出去就重新导航。
     *
     * 用户实测的现象：每次从后台切回来都停在「选择电脑」页，还要手动点一次。
     * 那是页面自己 suspend 了 transport、会话被拆掉的结果。
     * 可见性屏蔽能防住大部分，但进程级重建防不住，所以这里补一道兜底。
     */
    private fun probeAndRecoverSession() {
        val wv = webView ?: return
        val target = lastTargetUrl ?: return
        // 后台巡检期间记下的「不健康」原因，此刻一并核对：
        // 如果页面在后台确实掉线过，回前台的这次探测会看出它现在是否恢复了。
        // 这里只消费掉标记，真正的判定仍由页面当前状态决定（不看历史标记）。
        val bgReason = pendingBgRecoverReason
        pendingBgRecoverReason = null
        wv.evaluateJavascript(SessionProbeScript.build()) { raw ->
            val verdict = SessionRecoveryGate.shouldRecover(decodeJsStringResult(raw))
            DiagLog.i(
                "session.probe",
                "recover=${verdict.recover} reason=${verdict.reason}" +
                    if (bgReason != null) " bg=$bgReason" else "",
            )
            if (!verdict.recover) {
                // 页面自报「在会话里且 WS 开着」：这是连接健康的直接证据。
                // auth_ack 在恢复/重连路径上不会重发（见 RemoteSession.markPaired 的说明），
                // 面板状态必须靠这个信号才能回到「已连接」。
                if (verdict.reason == "in-session") session?.markPaired()
                return@evaluateJavascript
            }
            // 与自愈重载共用限流：同一时间段内不要反复导航。
            val now = System.currentTimeMillis()
            if (now - lastSessionRecoverAtMs < SESSION_RECOVER_MIN_INTERVAL_MS) {
                DiagLog.w("session.recover.skip", "too soon")
                return@evaluateJavascript
            }
            lastSessionRecoverAtMs = now
            DiagLog.w("session.recover", "reason=${verdict.reason} target=${DiagLog.safeUrl(target)}")
            // 重新导航到原远控 URL，而不是 reload：reload 可能落回页面自己的
            // 默认入口，而我们要的是「用户之前打开的那个会话」。
            startSession(target)
            pendingNavOrigin = "session-recover"
            wv.loadUrl(target)
        }
    }

    /**
     * 由上层（RemoteScreen）在切后台前声明「接下来是保活态」。
     *
     * 单独一个入口而不是让 onPause 自己去查设置：判断「该不该保活」需要
     * session 是否存在、用户开关是否打开，这些只有上层知道。
     */
    fun setKeepAliveIntent(enabled: Boolean) {
        keepAliveRequested = enabled
        // 常驻屏蔽跟着用户意图走：开了就一直武装，关了就撤。
        // 页面重载后 onPageFinished 会按当前设置再对齐一次。
        webView?.let { setHoldShield(it, enabled) }
    }

    /**
     * 保活期结束（用户关掉开关、或会话已销毁）时调用。
     *
     * 清掉保活标志并撤掉常驻屏蔽：此后 onPause 会正常挂起 WebView，
     * 后台巡检也不再介入。
     */
    fun clearKeepAliveShield() {
        if (!keepAliveRequested) return
        keepAliveRequested = false
        webView?.let { setHoldShield(it, false) }
        DiagLog.i("web.shield", "keepalive cleared")
    }

    /**
     * 原生代发心跳：App 在后台时替页面把心跳发出去。
     *
     * 【2026-10-31】这是「保活没生效」的真正修复点。真机日志证明 App 切后台后
     * Chromium 停掉了页面的 `setTimeout`：`rxAge`/`txAge` 冻在同一个值长达 3.7 小时，
     * 而探针每 30 秒都正常返回（渲染进程活着、`evaluateJavascript` 能执行）。
     *
     * 前两次修复都作用在错误的层：屏蔽 `visibilityState` 改不了 Chromium 调度器，
     * `BackgroundHealthGate` 只发现静默、没有恢复手段。既然原生能在后台执行页面 JS，
     * 就由原生按页面原本的节奏代发心跳，绕开被停掉的定时器。
     *
     * 由 KeepAliveService 按页面心跳间隔（10 秒）周期调用。
     * 页面自己还在正常发送时，脚本内部直接跳过（`page-alive`），
     * 所以前台与未冻结时行为完全不变。
     *
     * 详见 [NativeHeartbeat] 的证据链与三条设计约束。
     */
    fun nativeHeartbeatTick() {
        val wv = webView ?: return
        // 只在保活态代发：用户没开保活时，切后台就该让连接自然断掉。
        if (!keepAliveRequested) return
        wv.evaluateJavascript(NativeHeartbeat.build(System.currentTimeMillis())) { raw ->
            val result = NativeHeartbeat.parseResult(decodeJsStringResult(raw))
            when {
                result.sent -> DiagLog.i(
                    "native.beat",
                    "sent pageAge=${result.pageAgeMs}ms",
                )
                // page-alive 是正常状态（页面自己在发），不该刷成告警；
                // 但每 10 秒一条 info 会把日志刷满，所以只在第一次和异常时记。
                result.reason == "page-alive" -> Unit
                else -> DiagLog.w("native.beat", "skip reason=${result.reason}")
            }
        }
    }

    /**
     * 后台巡检：确认连接是真的还活着，死了就重载重连。
     *
     * 为什么需要它：息屏后有两条「静默死亡」路径，原生侧都拿不到回调——
     * 1. CPU 被冻结 → 页面 JS 停摆、心跳发不出去 → 服务端判死连接。
     *    此时 `__zrWsState.last` 仍然停在 open，页面自己毫无察觉。
     * 2. ROM 在后台杀掉 WebView 渲染进程但保留宿主进程（前台服务把进程保住了，
     *    页面却没了）。此时 evaluateJavascript 不再有回调。
     *
     * 两条都靠这个巡检兜底：一条看页面自报的收发时间戳，一条看探测有没有回调。
     * 由 KeepAliveService 周期调用（它活着才说明我们确实处于保活态）。
     */
    fun checkBackgroundHealth() {
        val wv = webView ?: return
        if (!keepAliveRequested) return
        val target = lastTargetUrl ?: return
        val startedAt = System.currentTimeMillis()
        val answered = java.util.concurrent.atomic.AtomicBoolean(false)

        wv.evaluateJavascript(BackgroundProbeScript.build()) { raw ->
            answered.set(true)
            val text = decodeJsStringResult(raw)
            val verdict = BackgroundHealthGate.evaluate(text)
            // 业务帧统计只用于诊断：它无法区分「任务空闲」与「订阅死亡」，
            // 所以不再参与任何自动决策（见 BackgroundHealthGate 的说明）。
            DiagLog.i(
                "bg.health",
                "recover=${verdict.recover} reason=${verdict.reason} ${bizStats(text)} ${shieldStats(text)}",
            )
            if (verdict.recover) {
                // 【2026-10-31 分两档：urgent 必须在后台重建，其余推迟到前台】
                //
                // 上一版一律推迟到回前台，理由是「后台重载看不到效果、代价是丢会话」。
                // 真机日志证明这对「入站死透」这一类是错的：
                //
                // ```
                // 01:00:00 txAge=30s rxAge=392s     ← 我们在发，收不到任何东西
                // 01:12:30 txAge=30s rxAge=1143s    ← 一直涨，从没恢复过
                // ```
                //
                // 那种状态下 socket 已被服务端关闭，但页面收不到 close 事件
                // （事件不再派发）、定时器也停了，**页面自己永远不可能恢复**。
                // 推迟到前台等于让用户每次亮屏都多等一次冷加载 + 重新握手。
                //
                // 所以：
                // - urgent（入站死透）→ 立刻在后台重建，代价可接受，因为
                //   不重建就一定是死的；
                // - 其余（closed/degraded 等页面自己还能自愈的）→ 仍推迟到前台。
                if (verdict.urgent) {
                    DiagLog.w("bg.recover.urgent", "reason=${verdict.reason}")
                    recoverFromBackground(target, verdict.reason)
                    return@evaluateJavascript
                }
                DiagLog.w("bg.recover.defer", "reason=${verdict.reason} pending until foreground")
                pendingBgRecoverReason = verdict.reason
            }
        }

        // 超时没回调 = 渲染进程很可能已被回收，页面 JS 根本没在跑。
        // 这是原生侧唯一能拿到「渲染进程没了」的证据（onRenderProcessGone 在部分
        // ROM 上不会触发，因为进程本身还活着）。
        //
        // 这一条**仍然要在后台重建**：渲染进程没了，页面不可能自己恢复，
        // 等回前台再建只会让用户多等一次冷加载。
        wv.postDelayed({
            if (answered.get()) return@postDelayed
            DiagLog.w(
                "bg.health.timeout",
                "after ${System.currentTimeMillis() - startedAt}ms renderer likely gone",
            )
            recoverFromBackground(target, "probe-timeout")
        }, PROBE_TIMEOUT_MS)
    }

    /**
     * 从探针 JSON 里摘出业务帧统计，拼成一行日志。
     *
     * 只用于诊断，不参与重载决策——重载判据仍走 BackgroundHealthGate。
     */
    private fun bizStats(rawJson: String): String {
        val obj = runCatching {
            JsonValue.parse(rawJson) as? JsonValue.Obj
        }.getOrNull() ?: return "biz=?"
        val lastBiz = obj.num("lastBizRxAt") ?: 0L
        val count = obj.num("bizRxCount") ?: 0L
        val now = obj.num("now") ?: System.currentTimeMillis()
        val ageSec = if (lastBiz > 0) (now - lastBiz) / 1000 else -1
        // 心跳年龄：比 bizAge 更能说明「页面 JS 是否还在跑」。
        // 心跳在走 = 页面活着；心跳停了 = 被冻结（这才是真故障）。
        val rxAge = obj.num("rxAgeMs") ?: -1L
        val txAge = obj.num("txAgeMs") ?: -1L
        fun sec(ms: Long) = if (ms >= 0) "${ms / 1000}s" else "-"
        return "bizAge=${ageSec}s bizCount=$count rxAge=${sec(rxAge)} txAge=${sec(txAge)}"
    }

    /**
     * 从探针 JSON 里摘出「屏蔽是否真的生效」的证据，拼成一行日志。
     *
     * 这几个字段回答的是 0.3.5 留下的那个问题：原生日志打了一堆
     * `web.hold fn=holdBegin`，页面却照样在回前台时自己 recoverConnection()。
     * 那行日志是无条件打印的，证明不了任何事。
     *
     * 现在看页面视角：
     * - `inst`：shield 对象是否真的存在（false = 注入没生效）
     * - `guard`：此刻是否处于屏蔽态
     * - `blk`/`pass`：拦下/放行的事件数
     * - `sawHidden`：页面**实际读到** hidden 的次数。**应为 0**。
     *   非 0 就是铁证：页面看到了 hidden，它会置「已 suspend」标志，
     *   此后回前台必走 recoverConnection() → 主动 close → 重连 → 可能整页 reload。
     */
    private fun shieldStats(rawJson: String): String {
        val obj = runCatching {
            JsonValue.parse(rawJson) as? JsonValue.Obj
        }.getOrNull() ?: return "shield=?"
        val installed = obj.value("shieldInstalled").let { it is JsonValue.Bool && it.value }
        if (!installed) return "shield=MISSING"
        val guard = obj.value("shieldGuard").let { it is JsonValue.Bool && it.value }
        val blocked = obj.num("shieldBlocked") ?: -1L
        val passed = obj.num("shieldPassed") ?: -1L
        val sawHidden = obj.num("shieldSawHidden") ?: -1L
        val defineFailed = obj.value("shieldDefineFailed").let { it is JsonValue.Bool && it.value }
        // 代发心跳的素材是否就位（2026-10-31，见 BackgroundProbe 的说明）。
        val beatShape = obj.value("beatShape").let { it is JsonValue.Bool && it.value }
        val sockState = obj.num("sockState") ?: -1L
        // 真实 relay URL 形态：匹配规则失效时，这条能直接看出真实形态是什么，
        // 不用再靠猜「是没匹配上还是页面没连」。URL 已由 bootstrap 去掉 query。
        val relayUrl = obj.str("relayUrl")
        return "shield=on guard=$guard blk=$blocked pass=$passed sawHidden=$sawHidden" +
            " beat=$beatShape sock=$sockState" +
            (if (relayUrl != null) " relay=$relayUrl" else "") +
            if (defineFailed) " DEFINE_FAILED" else ""
    }

    /**
     * 从后台状态恢复：重建渲染进程并重新导航。
     *
     * 用「销毁 WebView 再建新的」而不是 reload：渲染进程被回收后，
     * 旧 WebView 对象已经没有可用的渲染进程，reload 只会静默失败。
     *
     * 【2026-10-27 白屏根因修正】旧实现在这里重建后把新 WebView 交给
     * `ensureLoaded(hostView.context)`，而 hostView 是**旧** WebView 的父容器。
     * 新实例被创建了，却没有任何代码把它 addView 回去；Compose 的 AndroidView
     * 也不会因为控制器换了实例而重新 factory。结果是：旧 View 已 destroy 并移出视图树，
     * 新 View 悬空在内存里，屏幕上只剩 Box 的底色——浅色主题下就是用户看到的白茫茫。
     *
     * 现在不再自己 ensureLoaded，而是：
     * 1. 记下待加载 URL 与目标会话；
     * 2. 销毁旧实例并递增代次；
     * 3. 由 RemoteScreen 的 key(generation) 触发重新组合，factory 里创建并挂载新实例。
     * 这样「谁来挂载」这件事只有一个出口，不会再出现悬空实例。
     */
    private fun recoverFromBackground(target: String, reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastBgRecoverAtMs < BG_RECOVER_MIN_INTERVAL_MS) {
            DiagLog.w("bg.recover.skip", "reason=$reason too soon")
            return
        }
        // 没有附着窗口时重建只会得到一块空白：标记待加载，等 Activity 回来再建。
        val hostView = webView?.parent as? ViewGroup
        if (hostView == null) {
            DiagLog.w("bg.recover.defer", "reason=$reason no attached view")
            pendingLoadUrl = target
            return
        }
        lastBgRecoverAtMs = now
        DiagLog.w("bg.recover", "reason=$reason target=${DiagLog.safeUrl(target)}")
        destroyWebViewOnly()
        pendingLoadUrl = target
        pendingRestore = null
        startSession(target)
        // 代次递增：让 Compose 重新组合并挂载新的 WebView（见 generation 的说明）。
        _generation.value += 1
    }

    /**
     * 执行一次「返回」：先问页面处在哪一层，再按页面给的答案行动。
     *
     * 【2026-10-31 交互模型重做】这是三条返回入口（系统返回键、左边缘右滑、
     * 壳子上的断开/返回）共用的唯一实现。
     *
     * 旧实现 `tryBack()` 用 `canGoBack` 当判据：远控页是 SPA，URL 基本不变，
     * 历史栈对它没有意义，所以那条判据在真机上两种结果都错——
     * 用户在设置页里按返回，历史为空 → 直接退出整个会话；
     * 历史里有别的页面（OAuth 授权、404）→ 退回那个页面。
     *
     * 现在判据来自页面 DOM（nav.js）：浮层、侧栏、引导页、设置页、会话页、列表页，
     * 逐层后退。只有页面报告「我已经在最外层」时，才退到壳子。
     *
     * @param onPageHandled 页面自己消化了（原生什么都不做）
     * @param onExitToShell 页面在最外层 → 展开壳子面板
     * @param onUnresponsive 页面没答上来（脚本未注入/超时）→ 提示，不退出
     * @param onConfirmExit 需要「再按一次」确认 → 提示
     * @param onExit 确认通过 → 真的退出到入口页
     */
    fun requestBack(
        onPageHandled: () -> Unit,
        onExitToShell: () -> Unit,
        onUnresponsive: () -> Unit,
        onConfirmExit: () -> Unit,
        onExit: () -> Unit,
    ) {
        val wv = webView
        if (wv == null) {
            onExit()
            return
        }
        // 页面还没加载完：探针拿不到层级，直接按「最外层」处理，
        // 不要让用户对着一个空白页等一整个超时。
        if (!pageLoaded) {
            DiagLog.i("back.route", "not-loaded")
            handleShellBack(onExitToShell, onConfirmExit, onExit)
            return
        }
        // 防重入：连点两次返回时，第二次可能落在第一次的回调之前。
        // 两个都判「页面消化了」会连退两层（例如会话 → 列表 → 设置）。
        if (backInFlight) {
            DiagLog.i("back.route", "skip in-flight")
            return
        }
        backInFlight = true
        val startedAt = System.currentTimeMillis()
        // 先记下「按下返回那一刻页面在哪一层」，再执行后退。
        // 两条 evaluateJavascript 会按序执行，所以这条不会晚于 back()。
        logNavState("back-press")
        wv.evaluateJavascript(PageNavScript.back()) { raw ->
            backInFlight = false
            if (BackRouter.isScriptMissing(raw)) {
                DiagLog.w("back.route", "script-missing raw=${raw?.take(24)}")
                onUnresponsive()
                return@evaluateJavascript
            }
            when (val action = BackRouter.parse(raw)) {
                is BackRouter.Action.PageHandled -> {
                    DiagLog.i(
                        "back.route",
                        "page layer=${action.layer} took=${System.currentTimeMillis() - startedAt}ms",
                    )
                    // 用户自己导航走了：通知跳转意图作废，否则下一次 onPageFinished
                    // 会把他拽回刚才退出的会话（这条坑踩过一次）。
                    cancelPendingJump("back-page")
                    onPageHandled()
                }
                is BackRouter.Action.DismissPopup -> {
                    DiagLog.i("back.route", "dismiss popup layer=${action.layer}")
                    // 浮层必须用受信任的 Escape 关：合成 click 会双触发开关。
                    wv.dispatchKeyEvent(
                        android.view.KeyEvent(
                            android.view.KeyEvent.ACTION_DOWN,
                            android.view.KeyEvent.KEYCODE_ESCAPE,
                        ),
                    )
                    wv.dispatchKeyEvent(
                        android.view.KeyEvent(
                            android.view.KeyEvent.ACTION_UP,
                            android.view.KeyEvent.KEYCODE_ESCAPE,
                        ),
                    )
                    onPageHandled()
                }
                is BackRouter.Action.ToShell -> {
                    DiagLog.i("back.route", "shell layer=${action.layer}")
                    handleShellBack(onExitToShell, onConfirmExit, onExit)
                }
                is BackRouter.Action.Unresponsive -> {
                    DiagLog.w("back.route", "unresponsive layer=${action.layer}")
                    onUnresponsive()
                }
            }
        }
    }

    /**
     * 「页面已在最外层」之后的处理：先展开壳子，再按一次才退出。
     *
     * 为什么不是一按就退出：远控页是用户正在用的工作现场，
     * 一次误触就把会话关掉、还要重新粘贴链接，代价远大于多按一次。
     * 第一次按 → 壳子（用户能看到连接状态与设置入口，也算「确认要离开」）。
     * 壳子已经在眼前时再按 → 提示「再按一次」→ 再按才真的退出。
     */
    private fun handleShellBack(
        onExitToShell: () -> Unit,
        onConfirmExit: () -> Unit,
        onExit: () -> Unit,
    ) {
        val now = System.currentTimeMillis()
        if (BackRouter.shouldExit(now, lastExitPromptAtMs)) {
            lastExitPromptAtMs = 0L
            DiagLog.i("back.exit", "confirmed")
            onExit()
            return
        }
        lastExitPromptAtMs = now
        DiagLog.i("back.shell", "prompt")
        onConfirmExit()
        onExitToShell()
    }

    /**
     * 主动下拉刷新。
     *
     * 用户下拉的意图就是「现在就刷一遍」，所以这里**不判断连接是否健康**，
     * 直接整页重载——那正是「主动刷新要真的刷新」。
     *
     * 唯一被挡掉的情况是「页面正处在浮层/设置页里」：那种时候页面自己的状态
     * 比刷新更重要，重载会把用户正在编辑的东西一起丢掉。
     * 这个判断交给页面（nav.js 的 layer），原生不猜。
     */
    fun requestRefresh(onDone: () -> Unit = {}) {
        val wv = webView ?: run { onDone(); return }
        wv.evaluateJavascript(PageNavScript.state()) { raw ->
            val text = BackRouter.decode(raw)
            val obj = runCatching { JsonValue.parse(text) as? JsonValue.Obj }.getOrNull()
            val layer = obj?.str("layer")
            if (layer == "popup" || layer == "sidepane" || layer == "settings" || layer == "onboarding") {
                // 在浮层/设置页里下拉：不刷。用户此刻是在操作页面内部的东西。
                DiagLog.i("refresh.route", "skip layer=$layer")
                onDone()
                return@evaluateJavascript
            }
            DiagLog.i("refresh.route", "reload layer=$layer")
            reload()
            onDone()
        }
    }

    /** 壳子面板上显示「连的是谁」。取最近连接的标题，拿不到就退回 host。 */
    fun connectionTitle(): String {
        // 必须按**当前连接的 connectionId** 匹配，不能取全局最近一条：
        // 用户可能连着 A 设备、同时又点开过 B 的链接，取 latest() 会显示成 B，
        // 于是面板上写着「B 已连接」而实际连的是 A——这种错认比不显示更糟。
        val target = lastTargetUrl
        if (target != null) {
            val id = UrlPolicy.connectionId(target)
            val match = app.links.list().firstOrNull { UrlPolicy.connectionId(it.url) == id }
            if (match != null && match.title.isNotBlank()) return match.title
        }
        val host = target?.let { runCatching { java.net.URI(it).host }.getOrNull() }
        return host ?: "zcode.z.ai"
    }

    fun jumpToTask(taskId: String) {
        // 记住待跳转目标：通知点击时页面可能尚未加载完，甚至还没开始加载。
        // 页面每次 onPageFinished 都会重试，直到成功或过期，避免「进了 App 但没跳到位」。
        pendingJumpTaskId = taskId
        pendingJumpAtMs = System.currentTimeMillis()
        webView?.evaluateJavascript(SessionJumpScript.build(taskId), null)
    }

    /**
     * onPageFinished 时调用：若有未完成的跳转意图且未过期，重新注入脚本。
     *
     * 【2026-10-27 弹回循环根因】旧实现无条件重新注入，从不检查「上一次是不是已经跳到了」。
     * 跳转脚本本身是个 20 秒的 setInterval：只要它发现当前不在目标会话，就会去点任务条目。
     * 用户点返回 → 脚本下一拍点回会话 → 导航触发 onPageFinished → 这里又注入一份新脚本
     * → 再点回。死循环就此形成，用户被永久锁在会话页里出不来。
     *
     * 现在两件事：
     * 1. 先问页面「这个 taskId 是不是已经跳过了」（__zrJumpDone）。是就清掉意图、不再注入。
     * 2. 注入前不重复：脚本内部也会读 __zrJumpDone 做第一道闸（双保险，
     *    因为页面可能刚重建、evaluateJavascript 的时机不可控）。
     */
    private fun retryPendingJump(view: WebView) {
        val taskId = pendingJumpTaskId ?: return
        if (System.currentTimeMillis() - pendingJumpAtMs > JUMP_TTL_MS) {
            pendingJumpTaskId = null
            return
        }
        view.evaluateJavascript(
            "(window.__zrJumpDone === ${org.json.JSONObject.quote(taskId)})",
        ) { raw ->
            if (raw?.trim() == "true") {
                // 已经跳到过：意图达成，清掉，后续页面加载不再注入。
                pendingJumpTaskId = null
                DiagLog.i("jump.done", "already at ${taskId.take(12)}")
                return@evaluateJavascript
            }
            // 还没到：注入一次。脚本内部会用 __zrJumpGen 让更早的实例自行退出。
            view.evaluateJavascript(SessionJumpScript.build(taskId), null)
        }
    }

    /**
     * 用户主动返回（返回键退到任务列表 / 点页面里的返回入口）时调用。
     *
     * 通知带来的「跳到某个任务」是一次性意图，用户自己导航走了就该作废——
     * 否则待跳转意图会一直挂着，下一次 onPageFinished 又把人拽回那个会话。
     */
    fun cancelPendingJump(reason: String) {
        if (pendingJumpTaskId == null) return
        DiagLog.i("jump.cancel", "reason=$reason")
        pendingJumpTaskId = null
    }

    /** 跳转脚本成功落到目标会话时由页面回调（window.__zrJumpDone）。 */
    fun clearPendingJump() {
        pendingJumpTaskId = null
    }

    fun destroy() {
        // 会话真的销毁了：清保活标志，别让后台巡检对着一个空会话反复探测。
        clearKeepAliveShield()
        session?.dispose()
        session = null
        destroyWebViewOnly()
    }

    private fun destroyWebViewOnly() {
        webView?.apply {
            loadUrl("about:blank")
            stopLoading()
            onPause()
            clearHistory()
            (parent as? ViewGroup)?.removeView(this)
            destroy()
        }
        webView = null
    }

    private companion object {
        const val KEY_URL = "zr_target_url"
        const val MAX_RENDER_GONE = 3
        const val RENDER_BACKOFF_MS = 1500L
        const val DIAG_TAG = "ZcodeRemoteDiag"
        /** 待跳转意图的有效期：超过后不再重试（避免旧通知点击长期残留）。 */
        const val JUMP_TTL_MS = 90_000L
        /** 页面加载完成后多久复核一次「待确认断开」。 */
        const val CLOSE_RECHECK_MS = 4_500L
        /** 两次自愈重载之间的最小间隔：太密会把正常的重连过程打断。 */
        const val SELF_HEAL_MIN_INTERVAL_MS = 8_000L
        /**
         * 两次「掉出会话后重新导航」的最小间隔。
         *
         * 比自愈更长：重新导航会整页重载、丢草稿，代价更大，
         * 所以宁可让用户多等一会儿，也不要一进一出就刷一次。
         */
        const val SESSION_RECOVER_MIN_INTERVAL_MS = 20_000L
        /** 后台健康探测的超时：超过就认为渲染进程已被回收。 */
        const val PROBE_TIMEOUT_MS = 6_000L
        /** 两次后台恢复之间的最小间隔：重建 WebView 代价大，不能反复来。 */
        const val BG_RECOVER_MIN_INTERVAL_MS = 60_000L
        /** 同一会话最多自愈几次；超过就交给用户按「重试」。 */
        const val MAX_SELF_HEAL = 2
        /** 页面加载完成后等多久再判存活：要晚于页面自身的首次重连（实测 1-2 秒）。 */
        const val LIVENESS_AFTER_LOAD_MS = 5_000L
        /**
         * 文件选择守卫的最长持续时间。
         *
         * 正常选文件是几秒到几十秒；超过 2 分钟基本可以判定选择器没回来
         * （进程被杀、系统弹窗卡住）。到点强制解除，否则用户真按 Home 时页面不会挂起。
         */
        const val FILE_PICK_GUARD_TIMEOUT_MS = 120_000L
        /** 交付前轮询链路状态的间隔：200ms 足够细，又不会把 WebView 打满。 */
        const val FILE_DELIVER_POLL_MS = 200L
        /**
         * 等链路就绪的上限。
         *
         * 页面从后台回来要重建 bridge 并重新配对，实测 1-3 秒；留到 8 秒覆盖慢网。
         * 到点仍交付：用户选的文件绝不能丢，宁可让它以「可能失败」的状态交出去，
         * 也不能让界面永远显示「选择中」。
         */
        const val FILE_DELIVER_TIMEOUT_MS = 8_000L
    }
}

/**
 * 保活定制的 WebView：App 切后台时把「窗口可见性」钉在 VISIBLE。
 *
 * 【2026-10-31 保活第四次修复：这次修在正确的层】
 *
 * 真机日志（0.4.1，10:55-11:07）里后台三轮完全相同的节奏：
 *
 * ```
 * 10:55:24 rxAge=0s   ← 重建后业务帧正常流入
 * 10:56:24 rxAge=30s  ← 页面自己的定时器从这一刻起停摆
 * 10:57:54 rxAge=120s → bg.recover 整页销毁重建
 * ```
 *
 * 而 `native.beat` 每次都拿到回调、探针 30 秒一次从不缺席——渲染进程活着，
 * `evaluateJavascript` 能执行，唯独页面自己的 `setTimeout` 不再被调度。
 * 这是 WebView 的标准行为：**宿主窗口不可见时暂停 JS 定时器**，
 * 与进程回收无关（前台服务 + CPU 锁都挡不住它）。
 *
 * 前三次修复都没碰到这一层：屏蔽 `document.visibilityState` 骗的是页面 JS；
 * `setRendererPriorityPolicy` 管的是渲染进程回收；原生代发心跳只是补偿。
 * Chromium 决定「要不要继续调度这个页面的定时器」的真正开关，
 * 是 View 层的窗口可见性回调。
 *
 * 所以这里覆写它：保活开着（用户显式选择了后台监听）时恒报 VISIBLE，
 * 页面定时器在后台照常跑，WS 收发、心跳、通知链路全部自然存活，
 * 后台巡检退回「真正的兜底」而不再是每 3 分钟一次的常态重建。
 * 保活关闭时行为与系统完全一致。
 */
private class KeepAliveWebView(
    context: Context,
    private val holdVisible: () -> Boolean,
) : WebView(context) {

    private var lastVisLogAtMs = 0L

    override fun onWindowVisibilityChanged(visibility: Int) {
        val holding = holdVisible()
        // 【0.4.3 诊断】保活按住期间系统仍发来不可见回调时记一条。
        // 模拟器上这条永远不出现（回调本身就被吞掉），真机上它回答一个关键问题：
        // 若定时器仍停摆，是「回调没来/没按住」（冻结另有入口）还是
        // 「按住了仍冻结」（冻结在更深的进程层，窗口可见性够不着，
        // 只能靠 ROM 电池设置白名单 + 后台巡检兜底）。
        if (holding && visibility != View.VISIBLE) {
            val now = System.currentTimeMillis()
            if (now - lastVisLogAtMs > VIS_LOG_MIN_INTERVAL_MS) {
                lastVisLogAtMs = now
                DiagLog.i("wv.vis", "system=$visibility hold=visible")
            }
        }
        super.onWindowVisibilityChanged(if (holding) View.VISIBLE else visibility)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, if (holdVisible()) View.VISIBLE else visibility)
    }

    private companion object {
        const val VIS_LOG_MIN_INTERVAL_MS = 60_000L
    }
}
