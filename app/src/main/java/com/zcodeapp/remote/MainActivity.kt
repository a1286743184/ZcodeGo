package com.zcodeapp.remote

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.WebChromeClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import com.zcodeapp.remote.background.KeepAliveState
import com.zcodeapp.remote.shell.LauncherScreen
import com.zcodeapp.remote.shell.LogFilesScreen
import com.zcodeapp.remote.shell.LogScreen
import com.zcodeapp.remote.shell.OverlayTarget
import com.zcodeapp.remote.shell.RemoteScreen
import com.zcodeapp.remote.shell.ScanScreen
import com.zcodeapp.remote.shell.SettingsScreen
import com.zcodeapp.remote.ui.theme.ZcodemobileTheme
import com.zcodeapp.remote.ui.theme.useDarkBackground
import com.zcodeapp.remote.web.UrlPolicy
import com.zcodeapp.remote.web.WebViewSessionController

class MainActivity : ComponentActivity() {
    private lateinit var controller: WebViewSessionController

    /** 网页 `<input type=file>` 的系统选择器。必须在 Activity 里注册才能拿到结果。 */
    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>

    /** 网页申请的运行时权限（相机/麦克风）。 */
    private lateinit var webPermissionLauncher: ActivityResultLauncher<Array<String>>

    sealed class Screen {
        data object Launcher : Screen()
        data object Scan : Screen()
        data object Settings : Screen()
        data object Log : Screen()
        data object LogFiles : Screen()
        data class LogFile(val path: String) : Screen()
        data class Remote(val url: String) : Screen()
    }

    private var screen by mutableStateOf<Screen>(Screen.Launcher)
    private var pendingTaskId by mutableStateOf<String?>(null)
    private var notificationsAsked = false

    /**
     * 盖在远控页之上的原生页面（设置 / 日志）。
     *
     * 与 [screen] 分开：它是**叠加层**，不是新的一屏。远控页始终在它下面活着，
     * 关掉叠加层就回到原来的位置（含 WebView 的草稿与滚动）。
     */
    private var overlay by mutableStateOf<OverlayTarget?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as ZcodeRemoteApp
        // 【2026-10-27】控制器改由 Application 持有。
        //
        // 旧实现在这里 new 一个新的：Activity 因配置变化/后台被回收重建时，
        // 新的控制器没有 WebView，旧的 WebView 被孤立在已销毁的 Activity 上，
        // 会话随之断掉——保活期间这条路径特别容易踩到（息屏后 Activity 常被回收）。
        // 现在控制器跟着进程走，Activity 重建后拿到的是同一个实例与同一个 WebView。
        controller = app.sessionController

        fileChooserLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            val data = result.data
            val uris = when {
                result.resultCode != RESULT_OK || data == null -> null
                data.clipData != null -> {
                    val clip = data.clipData!!
                    Array(clip.itemCount) { clip.getItemAt(it).uri }
                }
                data.data != null -> arrayOf(data.data!!)
                else -> null
            }
            controller.onFileChooserResult(uris)
        }

        webPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { grants ->
            controller.onPermissionResult(
                grants.keys.toTypedArray(),
                grants.values.map {
                    if (it) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
                }.toIntArray(),
            )
        }

        controller.fileChooserHost = object : WebViewSessionController.FileChooserHost {
            override fun launch(params: WebChromeClient.FileChooserParams) {
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = params.acceptTypes.firstOrNull { it.isNotBlank() && it != "*/*" } ?: "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE)
                }
                fileChooserLauncher.launch(Intent.createChooser(intent, null))
            }

            override fun requestPermissions(permissions: Array<String>) {
                webPermissionLauncher.launch(permissions)
            }
        }

        if (savedInstanceState != null) {
            // 【2026-10-27 关键修正】不再无条件回到 Launcher。
            //
            // 旧实现在这里硬写 `screen = Screen.Launcher`：只要 Activity 被回收重建
            // （息屏、后台内存紧张都会触发），界面就回到入口页，用户必须再手动点一次
            // 才能回到远控会话——正是「切回来停在连接页」的直接原因。
            //
            // 现在优先恢复上次所在的远控页；只有确实没在远控页时才回入口。
            // 待跳转任务不再随重建恢复（见 onSaveInstanceState 的说明）：
            // 用户可能早已自己退出那个会话，重新武装只会把他拽回去。
            val restoredUrl = savedInstanceState.getString(KEY_SCREEN_URL)
            screen = if (restoredUrl != null) Screen.Remote(restoredUrl) else Screen.Launcher
        } else {
            resolveIntent(intent)
        }

        setContent {
            ZcodemobileTheme {
                // 状态栏图标颜色必须跟着当前底色走。
                // 数据源统一用 PageThemeStore：页面探测到配色后整个应用跟着页面走，
                // 没探测到时退回系统深浅色。写死「远控页用浅图标」会在页面切成浅色时失效。
                val useDarkIcons = !useDarkBackground()
                LaunchedEffect(useDarkIcons) {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = useDarkIcons
                        isAppearanceLightNavigationBars = useDarkIcons
                    }
                }
                when (val s = screen) {
                    is Screen.Launcher -> LauncherScreen(
                        onOpenUrl = { raw -> tryOpen(raw) },
                        onScan = { screen = Screen.Scan },
                        onSettings = { screen = Screen.Settings },
                    )
                    is Screen.Scan -> ScanScreen(
                        onClose = { screen = Screen.Launcher },
                        onScanned = { raw -> tryOpen(raw) },
                    )
                    is Screen.Settings -> SettingsScreen(
                        onBack = { screen = Screen.Launcher },
                        onOpenLog = { screen = Screen.Log },
                        onOpenLogFiles = { screen = Screen.LogFiles },
                    )
                    is Screen.Log -> LogScreen(
                        onBack = { screen = Screen.Settings },
                    )
                    is Screen.LogFiles -> LogFilesScreen(
                        onBack = { screen = Screen.Settings },
                        onOpenFile = { f -> screen = Screen.LogFile(f.absolutePath) },
                    )
                    is Screen.LogFile -> LogScreen(
                        onBack = { screen = Screen.LogFiles },
                        file = java.io.File(s.path).takeIf { it.exists() },
                    )
                    is Screen.Remote -> RemoteScreen(
                        controller = controller,
                        url = s.url,
                        pendingTaskId = pendingTaskId,
                        // 跳转意图是一次性的：交给控制器后就作废，
                        // 避免 Activity 重建时被重新武装、把用户拽回他刚退出的会话。
                        onJumpConsumed = { pendingTaskId = null },
                        onExit = {
                            pendingTaskId = null
                            overlay = null
                            screen = Screen.Launcher
                        },
                        // 【2026-10-31 交互模型】设置与日志不再是「先退回入口页再进去」，
                        // 而是**盖在远控页之上**。旧路径会让 WebView 失去可见性、
                        // 会话在视觉上断掉，回来还要重新等页面恢复。
                        // 现在它们只是叠加层：WebView 始终活着，关掉就回到原来的位置。
                        overlay = overlay,
                        onOverlayChange = { overlay = it },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        resolveIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val target = (screen as? Screen.Remote)?.url
        if (target != null) {
            controller.saveState(outState)
            outState.putString(KEY_SCREEN_URL, target)
            // 【2026-10-27 弹回循环根因】不再持久化 pendingTaskId。
            //
            // 它已经被 RemoteScreen 交给控制器（onJumpConsumed 置空），能留到这里的
            // 只可能是「刚收到通知还没渲染」。而存进去之后，Activity 一旦因息屏/内存
            // 被回收重建就会把它恢复出来、重新武装跳转——用户明明已经自己退出了会话，
            // 却又被拽回去，正是「点返回不断被弹回」的另一条路径。
            //
            // 跳转意图本来就有时效（JUMP_TTL_MS），跨 Activity 重建没有保留价值。
        }
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        val url = savedInstanceState.getString(KEY_SCREEN_URL)
        if (url != null) {
            // 只在 WebView 真的没了（进程级重建）时才 restoreState。
            // 保活期间 Activity 重建但进程还在，控制器与 WebView 都是活的，
            // 这时 restoreState 会重放一份旧快照、把正在跑的会话打断——
            // 那等于把保活成果自己毁掉。
            if (!controller.hasLiveWebView) {
                controller.restoreState(savedInstanceState)
            }
            screen = Screen.Remote(url)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 三种情况要分开处理，混在一起会误杀会话：
        // 1. 配置变化（旋转等）：控制器由 Application 持有，这里什么都不做，
        //    重建后的 Activity 拿到同一个实例与同一个 WebView。
        // 2. 保活开启：也不销毁——会话要跨 Activity 生命周期存活，
        //    页面一销毁，WS 与任务订阅全没了，后台保活就白做。
        // 3. 保活关闭且真的退出：销毁，释放 WebView 与渲染进程。
        if (isChangingConfigurations) return
        if (!KeepAliveState.isEnabled(this)) {
            controller.destroy()
        }
    }

    private fun tryOpen(raw: String): Boolean {
        val verdict = UrlPolicy.resolve(raw)
        if (verdict !is UrlPolicy.Verdict.InApp) return false
        requestNotificationsIfNeeded()
        screen = Screen.Remote(verdict.url)
        return true
    }

    private fun resolveIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                val uri = intent.data ?: return
                tryOpen(uri.toString())
            }
            ACTION_OPEN_ROUTE -> {
                val url = intent.getStringExtra(EXTRA_ROUTE_URL) ?: return
                pendingTaskId = intent.getStringExtra(EXTRA_ROUTE_TASK_ID)
                if (UrlPolicy.resolve(url) is UrlPolicy.Verdict.InApp) {
                    requestNotificationsIfNeeded()
                    screen = Screen.Remote((UrlPolicy.resolve(url) as UrlPolicy.Verdict.InApp).url)
                }
            }
        }
    }

    private fun requestNotificationsIfNeeded() {
        if (notificationsAsked || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        notificationsAsked = true
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
    }

    companion object {
        const val ACTION_OPEN_ROUTE = "com.zcodeapp.remote.action.OPEN_ROUTE"
        const val EXTRA_ROUTE_URL = "route_url"
        const val EXTRA_ROUTE_TASK_ID = "route_task_id"
        private const val KEY_SCREEN_URL = "screen_url"
        private const val REQUEST_NOTIFICATIONS = 4001
    }
}
