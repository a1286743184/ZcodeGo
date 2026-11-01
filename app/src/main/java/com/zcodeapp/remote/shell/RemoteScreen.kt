package com.zcodeapp.remote.shell

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.zcodeapp.remote.BuildConfig
import com.zcodeapp.remote.R
import com.zcodeapp.remote.ZcodeRemoteApp
import com.zcodeapp.remote.background.KeepAliveService
import com.zcodeapp.remote.ui.theme.PageThemeStore
import com.zcodeapp.remote.web.PageState
import com.zcodeapp.remote.web.WebViewSessionController

/**
 * 远控页底色。
 *
 * 【2026-10-27 修正】原来写死 #161616。页面主题可切换（深/浅由用户设置决定），
 * 写死深色会让浅色页面下的状态栏区域露出一条深色边。
 * 现在取页面实际底色，页面还没探测到时退回 #161616（官方默认主题的底色）。
 */
private val RemoteCanvasFallback = Color(0xFF161616)

/**
 * 远控页。
 *
 * 【2026-10-31 交互模型重做】这一屏现在承载四层结构，从下往上：
 *
 * 1. **页面**（WebView）。始终活着，不因为壳层打开而失去可见性——
 *    保活链路对可见性极度敏感（见 file-pick-guard 的说明），
 *    把 WebView 从视图树上摘下来会让页面自己 suspend transport。
 * 2. **下拉刷新层**（[PullRefreshHost]）。只在页面顶部窄区域起手才认，其余放行。
 *    边缘滑动返回不做自绘：系统手势与返回键已由 BackHandler 统一接住。
 *    它包在 WebView 外面，因为只有父容器能在 dispatch 链上抢事件。
 * 3. **壳子面板**（[ShellPanel]）。页面回到最外层时用户退到的地方。
 * 4. **原生覆盖页**（设置 / 日志）。盖住页面，关掉回来草稿还在。
 *
 * 返回键的判定顺序也是这四层：覆盖页 → 壳子面板 → 页面层级 → 再按一次退出。
 * 前三层都由各自的 BackHandler 拦截，只有最后一层会落到这里。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun RemoteScreen(
    controller: WebViewSessionController,
    url: String,
    pendingTaskId: String?,
    onJumpConsumed: () -> Unit = {},
    onExit: () -> Unit,
    overlay: OverlayTarget? = null,
    /** 打开 / 关闭叠加层。传 null 表示关闭。 */
    onOverlayChange: (OverlayTarget?) -> Unit = {},
) {
    val context = LocalContext.current
    val app = context.applicationContext as ZcodeRemoteApp
    val loading by controller.loading.collectAsState()
    val pageError by controller.pageError.collectAsState()
    val pageState by controller.pageState.collectAsState()
    val failedTitle = stringResource(R.string.remote_load_failed_title)
    val retryLabel = stringResource(R.string.remote_retry)
    val backAgainHint = stringResource(R.string.back_again_to_exit)
    val pageNotReadyHint = stringResource(R.string.back_page_not_ready)

    /** 壳子面板是否展开。 */
    var shellOpen by remember { mutableStateOf(false) }

    /** 下拉刷新的进度（0..1），只在手势期间非零。 */
    var pullProgress by remember { mutableStateOf(0f) }

    /** 当前挂载的下拉宿主。IME 状态变化时要能同步给它。 */
    var pullHost by remember { mutableStateOf<PullRefreshHost?>(null) }

    // 输入法弹起时禁用下拉刷新。
    //
    // 这是最容易踩到的误触：IME 把页面压到上半屏后，内容被压缩、scrollY 变成 0，
    // 用户想回看前面的对话而向下拖——此时「页面在顶部」与「起手在顶部区域」
    // 两条前提都成立，只有这条能挡住它。输入法在场时用户的意图是「看内容」，
    // 不是「重置页面」。
    // 用 rememberUpdatedState 包一层：factory 是一次性的闭包，
    // 直接捕获 imeVisible 会永远拿到创建那一刻的值。
    val imeVisible = WindowInsets.isImeVisible
    val imeVisibleState = rememberUpdatedState(imeVisible)
    LaunchedEffect(imeVisible) {
        pullHost?.setImeVisible(imeVisible)
    }

    /** 叠加层内部当前停在哪一页。设置 → 日志文件 → 单份日志，都留在同一个叠加层里。 */
    var overlayPage: OverlayPage by remember { mutableStateOf(OverlayPage.Settings) }
    // 打开叠加层时按入口决定第一页；关闭后重置回设置页，
    // 下次从「设置」再进来不该停在上一轮的日志详情上。
    LaunchedEffect(overlay) {
        overlayPage = when (overlay) {
            OverlayTarget.LogFiles -> OverlayPage.LogFiles
            OverlayTarget.Settings, null -> OverlayPage.Settings
        }
    }

    LaunchedEffect(url, pendingTaskId) {
        controller.open(url)
        // 跳转不在这里直接执行：此刻 WebView 可能尚未创建或页面还没渲染。
        // jumpToTask 只登记「待跳转意图」，由 onPageFinished 在每次页面加载完成后重试，
        // 直到落到目标会话或意图过期。这解决了「点通知进了 App 但没跳到位」。
        //
        // 【2026-10-27 弹回循环根因】pendingTaskId 会从 savedInstanceState 恢复，
        // Activity 因息屏/内存紧张被回收重建后 LaunchedEffect 会重跑，于是同一个
        // 跳转意图被重新武装一遍——哪怕用户早就自己退出了那个会话。
        // 所以意图必须是一次性的：执行过就作废，见 MainActivity 的 onJumpConsumed。
        pendingTaskId?.let {
            controller.jumpToTask(it)
            onJumpConsumed()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(url, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    // 先声明本次后台是否属于保活场景，再走 onPause：
                    // 保活时控制器会屏蔽页面可见性、不挂起 WebView，
                    // 否则页面自己 suspend transport，回来就退回设备列表了。
                    val keepAlive = app.settings.backgroundListening && controller.session != null
                    controller.setKeepAliveIntent(keepAlive)
                    controller.onPause()
                    if (keepAlive) {
                        KeepAliveService.start(context)
                    }
                }
                Lifecycle.Event.ON_RESUME -> {
                    controller.onResume()
                    // 【2026-10-27】不再在回前台时停服务。
                    // 旧逻辑 ON_RESUME 就 stop，导致「息屏期间保活、亮屏立刻停」——
                    // 用户锁屏再解锁后，下次息屏又得重新走一遍 onPause 才恢复，
                    // 中间那段时间通知就断了。保活是用户显式打开的常驻能力，
                    // 只由设置页开关控制起停，不跟页面生命周期联动。
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        // 进页面就声明保活意图：用户开着保活时，即使还没切过后台，
        // 后台巡检也应该在息屏后立刻生效（不然第一次息屏要等一轮 onPause）。
        if (app.settings.backgroundListening && controller.session != null) {
            controller.setKeepAliveIntent(true)
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // 【不要在这里清保活标志】
            //
            // onDispose 在 Activity 被销毁时也会执行——息屏、内存紧张都会触发。
            // 如果在这里 clearKeepAliveShield，保活标志会被系统悄悄清掉，
            // 后台巡检随即失效，正是「息屏后什么都没发生」的那条路径。
            //
            // 保活标志只由用户显式关开关（SettingsScreen）或会话销毁时清除。
        }
    }

    // 【返回键】这里只处理「页面层级」这一层，壳子面板与覆盖页各由自己的 BackHandler 拦截。
    //
    // 旧实现是 `if (!controller.tryBack()) onExit()`：拿 canGoBack 当唯一判据，
    // 而远控页是 SPA，历史栈对它没有意义——于是要么原地不动、要么直接退出。
    // 现在交给页面自己判定层级（见 nav.js），原生只执行它给出的动作。
    BackHandler {
        controller.requestBack(
            onPageHandled = {},
            onExitToShell = { shellOpen = true },
            onUnresponsive = { Toast.makeText(context, pageNotReadyHint, Toast.LENGTH_SHORT).show() },
            onConfirmExit = { Toast.makeText(context, backAgainHint, Toast.LENGTH_SHORT).show() },
            onExit = onExit,
        )
    }

    // 远控页整屏铺页面自己的底色。
    // 状态栏区域必须由这一层填上：窗口背景是主题色，状态栏留透明时会透出它，
    // 而状态栏图标颜色是按页面明暗设定的，底色不对就会看不清。
    val pageTheme by PageThemeStore.current.collectAsState()
    val canvas = pageTheme?.background ?: RemoteCanvasFallback
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(canvas),
    ) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            // 不再自己画「正在重连」状态条。
            // 页面自身已经有完整的连接/重连提示（「正在连接…」「已配对，正在加载工作区…」等），
            // 原生这一条既重复又经常误报：页面重载、桥代次切换都会短暂断开，
            // 用户看到的就是「明明能用却一直闪红条」。原生的连接状态只用于内部诊断与日志，
            // 不再占用用户视口。
            // G1 诊断：只在 debug 构建显示，且默认收起——它是开发期的工具，不该占用户视口。
            // release 构建里整块不存在（BuildConfig.DEBUG 为 false 时被编译掉）。
            if (BuildConfig.DEBUG) {
                var diagOpen by remember { mutableStateOf(false) }
                var diag by remember { mutableStateOf<String?>(null) }
                if (diagOpen) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { diag = controller.diagnostics() }) { Text("Diag") }
                        diag?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        TextButton(onClick = { diagOpen = false }) { Text(stringResource(R.string.close)) }
                    }
                } else {
                    // 收起时只留一个极小的触达点，不占高度、不挡页面。
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { diagOpen = true }) { Text("Diag") }
                    }
                }
            }
            if (loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            // 必须用 weight 占满剩余高度。用 fillMaxSize 会让 Box 在 Column 里声明「和父容器一样高」，
            // 与上方状态条争用高度，WebView 拿不到稳定尺寸，启动画面层（Splash Screen）会一直不消失。
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                // 【2026-10-27 白屏根因】用 generation 做 key。
                //
                // 后台巡检发现渲染进程被回收时，控制器会销毁旧 WebView 并新建一个。
                // 但 Compose 的 AndroidView 只在首次组合时调 factory —— 控制器换了实例，
                // 它完全不知道，于是旧 View 已 destroy 并移出视图树，新 View 悬空没人挂载，
                // 屏幕上只剩 Box 底色（浅色主题下就是那片白茫茫），必须退回入口页再进一次才好。
                //
                // key 变化会让 AndroidView 重新组合，factory 重新执行并挂载新实例。
                val generation by controller.generation.collectAsState()
                key(generation) {
                    // 下拉刷新宿主包住 WebView。
                    //
                    // 只做下拉刷新，不做边缘滑动返回：安卓系统手势导航与返回键都会
                    // 产生 back 事件，已经由上面的 BackHandler 接住并走分层判定。
                    // 自绘边缘手势只会和系统返回竞争，两边行为不一致时用户无法预判。
                    //
                    // 必须是父容器：WebView 自己会 requestDisallowInterceptTouchEvent，
                    // Compose 层拿不到这个事实。
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        // 【2026-10-31 闪退根因】factory 必须返回 host（WebView 的父容器）。
                        //
                        // 旧写法是 `getOrCreate(ctx).also { wv -> ...; host }`：`also` 永远返回
                        // receiver，块内末尾的 `host` 是死代码，factory 实际交回的是 WebView。
                        // 而块内刚执行过 host.addView(wv)，WebView 已有 parent，Compose 把它
                        // 再 addView 进自己的 holder 时被 ViewGroup.addViewInner 拒绝——
                        // IllegalStateException: The specified child already has a parent。
                        // 表现即「进入远控页必闪退」（0.4.0 回归，复现于模拟器 logcat）。
                        factory = { ctx ->
                            val wv = controller.getOrCreate(ctx)
                            (wv.parent as? android.view.ViewGroup)?.removeView(wv)
                            val host = PullRefreshHost(ctx) {
                                // 主动下拉就是真的要刷新：这里不做「条件判断」，
                                // 唯一要防的是误触，而误触由起手区域 + 页面在顶部两条挡掉。
                                controller.reload()
                            }
                            host.addView(
                                wv,
                                android.view.ViewGroup.LayoutParams(
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                ),
                            )
                            // 进度回传：宿主在主线程，Compose 状态写回同样在主线程。
                            host.setPullProgressListener { p -> pullProgress = p }
                            host.setImeVisible(imeVisibleState.value)
                            pullHost = host
                            host
                        },
                        update = { },
                    )
                }

                // 下拉刷新进度：一条从顶部向下走的细线，宽度随进度增长。
                // 不用 Spinner：刷新判定经常是「不需要刷新」，Spinner 转一圈什么都不发生
                // 会让人以为功能坏了；进度线只在手势期间出现，松手即消失。
                if (pullProgress > 0f) {
                    val alpha by animateFloatAsState(
                        targetValue = pullProgress,
                        animationSpec = tween(120),
                        label = "pull-alpha",
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .padding(top = 0.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(alpha.coerceIn(0f, 1f))
                                .height(2.dp)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                }

                if (pageError != null) {
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 2.dp,
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            CircularProgressIndicator()
                            Text(
                                text = failedTitle,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(top = 16.dp),
                            )
                            Text(
                                text = pageError.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(
                                onClick = {
                                    controller.pageError.value = null
                                    controller.reload()
                                },
                                modifier = Modifier.padding(top = 16.dp),
                            ) { Text(retryLabel) }
                        }
                    }
                }
            }
        }

        // 壳子面板：叠在页面之上，页面始终在下面活着。
        ShellPanel(
            visible = shellOpen && overlay == null,
            connectionTitle = controller.connectionTitle(),
            connectionSubtitle = if (pageState == PageState.PAIRED) {
                stringResource(R.string.shell_status_connected)
            } else {
                stringResource(R.string.shell_status_offline)
            },
            healthy = pageState == PageState.PAIRED,
            onClose = { shellOpen = false },
            onOpenSettings = {
                shellOpen = false
                onOverlayChange(OverlayTarget.Settings)
            },
            onOpenLogFiles = {
                // 壳子里的「日志文件」直接落到叠加层，不经过设置页——
                // 用户在会话里想看日志，不该先被送到设置页再往下找一层。
                shellOpen = false
                onOverlayChange(OverlayTarget.LogFiles)
            },
            onReconnect = {
                shellOpen = false
                controller.reload()
            },
            onDisconnect = {
                shellOpen = false
                onExit()
            },
        )

        // 原生覆盖页（设置 / 日志）：盖住页面但保留其状态。
        ShellOverlayHost(
            visible = overlay != null,
            onClose = { onOverlayChange(null) },
        ) {
            when (val page = overlayPage) {
                OverlayPage.Settings -> SettingsScreen(
                    onBack = { onOverlayChange(null) },
                    onOpenLog = { overlayPage = OverlayPage.Log },
                    onOpenLogFiles = { overlayPage = OverlayPage.LogFiles },
                )
                OverlayPage.Log -> LogScreen(
                    onBack = { overlayPage = OverlayPage.Settings },
                )
                OverlayPage.LogFiles -> LogFilesScreen(
                    onBack = { overlayPage = OverlayPage.Settings },
                    onOpenFile = { f -> overlayPage = OverlayPage.LogFile(f.absolutePath) },
                )
                is OverlayPage.LogFile -> LogScreen(
                    onBack = { overlayPage = OverlayPage.LogFiles },
                    file = java.io.File(page.path).takeIf { it.exists() },
                )
            }
        }
    }
}

/** 盖在远控页之上的原生页面。 */
enum class OverlayTarget { Settings, LogFiles }

/**
 * 叠加层内部的页面栈。
 *
 * 为什么叠加层要自己维护一个小栈：设置页里的「日志文件」入口如果跳整个 screen，
 * WebView 会被组合掉、会话断掉。它必须留在同一块叠加层里，关掉整块就回到页面。
 */
private sealed class OverlayPage {
    data object Settings : OverlayPage()
    data object Log : OverlayPage()
    data object LogFiles : OverlayPage()
    data class LogFile(val path: String) : OverlayPage()
}
