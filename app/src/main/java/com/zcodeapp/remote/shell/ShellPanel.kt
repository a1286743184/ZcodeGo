package com.zcodeapp.remote.shell

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zcodeapp.remote.R
import com.zcodeapp.remote.ui.theme.Chevron
import com.zcodeapp.remote.ui.theme.Gap
import com.zcodeapp.remote.ui.theme.MotionSpec
import com.zcodeapp.remote.ui.theme.Radius
import com.zcodeapp.remote.ui.theme.StatusDot
import com.zcodeapp.remote.ui.theme.signalGreen
import com.zcodeapp.remote.ui.theme.signalRed

/**
 * 壳子面板（会话面板）。
 *
 * 定位：**页面回到最外层时，用户退到的地方**。
 * 在此之前，「退到壳子」等于退回入口页（Launcher），会话在视觉上直接断掉，
 * 用户再想回到任务列表得重新粘贴链接。这个面板把「壳子」做成一个可从右侧
 * 滑入的抽屉：连接状态、设置、日志、断开，都在这里，页面本身不离开视口。
 *
 * 为什么是抽屉而不是整页替换：远控页的保活、草稿、滚动位置全都挂在同一个
 * WebView 上。整页替换会让 WebView 失去可见性（保活链路对可见性极度敏感，
 * 见 file-pick-guard 的说明），所以壳子必须**叠在页面之上**，页面始终在下面活着。
 *
 * 关闭方式：点遮罩、按返回键、点面板内的关闭。三者都只关面板，不退出应用。
 */
@Composable
fun ShellPanel(
    visible: Boolean,
    connectionTitle: String,
    connectionSubtitle: String,
    healthy: Boolean,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLogFiles: () -> Unit,
    onReconnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    // 面板开着时返回键先关面板。必须用 BackHandler 而不是让外层统一处理：
    // 外层那份判定是「页面层级」，面板是原生层级，页面探针看不到它。
    if (visible) BackHandler(onBack = onClose)

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(MotionSpec.BASE)),
        exit = fadeOut(tween(MotionSpec.INSTANT)),
    ) {
        // 遮罩：整屏可点关闭。颜色用背景色带透明度，深浅两套主题都能压暗页面。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.55f))
                .clickable(onClick = onClose),
        )
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(tween(MotionSpec.SLOW, easing = MotionSpec.Decelerate)) { it } + fadeIn(tween(MotionSpec.BASE)),
        exit = slideOutHorizontally(tween(MotionSpec.BASE, easing = MotionSpec.Standard)) { it } + fadeOut(tween(MotionSpec.INSTANT)),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    // 88vw 与页面自己的侧栏宽度一致，用户在页面内外看到的比例是同一个。
                    .widthIn(max = (LocalConfiguration.current.screenWidthDp * 0.88f).dp)
                    .fillMaxWidth(0.88f)
                    .clip(RoundedCornerShape(topStart = Radius.card, bottomStart = Radius.card))
                    .background(MaterialTheme.colorScheme.surface)
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = Gap.md),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Gap.md, bottom = Gap.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.shell_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    ShellCloseButton(onClose)
                }

                // 连接状态：一行，不占主视觉。它回答的是「我现在连的是谁、还活着吗」。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.control))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = Gap.sm, vertical = Gap.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusDot(if (healthy) signalGreen() else signalRed())
                    Spacer(Modifier.width(Gap.sm))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = connectionTitle,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = connectionSubtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Spacer(Modifier.height(Gap.md))

                ShellAction(stringResource(R.string.shell_reconnect), onReconnect)
                ShellAction(stringResource(R.string.shell_settings), onOpenSettings)
                ShellAction(stringResource(R.string.shell_logs), onOpenLogFiles)
                ShellAction(
                    label = stringResource(R.string.shell_disconnect),
                    onClick = onDisconnect,
                    danger = true,
                )

                Spacer(Modifier.weight(1f))

                // 底部只放一句提示，不放第二个「关闭」按钮：遮罩与顶部 X 已经够用。
                Text(
                    text = stringResource(R.string.shell_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(bottom = Gap.md),
                )
            }
        }
    }
}

/**
 * 设置页的嵌入形态。
 *
 * 「设置能不能嵌进网页里」的答案是：**嵌进页面之上的壳层，而不是网页内部**。
 * 理由有两条，都与正确性有关而非偏好：
 * 1. 设置里有「后台保活」「检查并更新」「安装未知应用」这些必须走原生 API 的动作，
 *    网页内部做不到（安装器、前台服务、电池白名单都是系统能力）。
 * 2. 设置页需要跟随页面配色，而配色来自页面本身（PageThemeStore）。
 *    做成叠加层就能直接复用这套主题，不需要在网页里再维护一套色板。
 *
 * 视觉上它盖住页面但保留页面的状态：WebView 不销毁、不失去可见性，
 * 关掉设置回来时草稿与滚动位置都在。
 */
@Composable
fun ShellOverlayHost(
    visible: Boolean,
    onClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (visible) BackHandler(onBack = onClose)
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(tween(MotionSpec.SLOW, easing = MotionSpec.Decelerate)) { it / 6 } + fadeIn(tween(MotionSpec.BASE)),
        exit = slideOutVertically(tween(MotionSpec.BASE, easing = MotionSpec.Standard)) { it / 8 } + fadeOut(tween(MotionSpec.INSTANT)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            content()
        }
    }
}

@Composable
private fun ShellAction(label: String, onClick: () -> Unit, danger: Boolean = false) {
    val color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.pill))
            .clickable(onClick = onClick)
            .padding(horizontal = Gap.sm, vertical = Gap.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = color,
            modifier = Modifier.weight(1f),
        )
        Chevron(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
    }
}

/** 关闭按钮：两条线画一个 X，不依赖图标库（与本项目其它图标一致）。 */
@Composable
private fun ShellCloseButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(Radius.pill))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val ink = MaterialTheme.colorScheme.onSurfaceVariant
        androidx.compose.foundation.Canvas(modifier = Modifier.size(11.dp)) {
            val w = size.width
            val h = size.height
            drawLine(ink, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(w, h), strokeWidth = 1.6f)
            drawLine(ink, androidx.compose.ui.geometry.Offset(w, 0f), androidx.compose.ui.geometry.Offset(0f, h), strokeWidth = 1.6f)
        }
    }
}

