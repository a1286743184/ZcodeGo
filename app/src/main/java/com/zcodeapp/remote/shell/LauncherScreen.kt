package com.zcodeapp.remote.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zcodeapp.remote.R
import com.zcodeapp.remote.ZcodeRemoteApp
import com.zcodeapp.remote.ui.theme.BrandMark
import com.zcodeapp.remote.ui.theme.Chevron
import com.zcodeapp.remote.ui.theme.Gap
import com.zcodeapp.remote.ui.theme.PrimaryButton
import com.zcodeapp.remote.ui.theme.Radius
import com.zcodeapp.remote.ui.theme.SurfaceGroup
import com.zcodeapp.remote.ui.theme.TextAction

/**
 * 入口页。
 *
 * 设计取向（2026-10-27 重做，对齐官方远控页）：
 * - 顶部一行品牌标记 + 设置文字动作，不占主视觉。
 * - 主标题 26sp 半粗，正文 13sp，形成明显字号落差。
 * - 主操作是**一块**面板：输入框 + 实心主按钮。输入框自己不带边框，
 *   靠面板底色的微妙差异与光标表达「这里可以输入」——官方页面的输入框就是这样。
 * - 扫码是次要动作，做成一行图标+文字，不与主按钮争权重。
 * - 最近连接用缩进行列表（左侧一条竖线做锚点），形态与上方面板区分开。
 */
@Composable
fun LauncherScreen(
    onOpenUrl: (String) -> Boolean,
    onScan: () -> Unit,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    val links = remember { (context.applicationContext as ZcodeRemoteApp).links }
    var input by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var recent by remember { mutableStateOf(links.list()) }
    val invalidMessage = stringResource(R.string.launcher_url_invalid)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Gap.lg),
    ) {
        // 顶部：品牌标记 + 设置。只用一行，不占主视觉。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Gap.md, bottom = Gap.xxl),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BrandMark()
            Spacer(Modifier.width(Gap.sm))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.weight(1f))
            TextAction(stringResource(R.string.launcher_settings), onSettings)
        }

        // 主标题：整页唯一的大字，撑住版面。
        Text(
            text = stringResource(R.string.launcher_title),
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(Gap.sm))
        Text(
            text = stringResource(R.string.launcher_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(0.94f),
        )

        Spacer(Modifier.height(Gap.xl))

        // 主操作区：输入 + 打开。用一块面板包住，与下方最近连接区分开。
        UrlEntry(
            input = input,
            error = error,
            onInputChange = {
                input = it
                error = null
            },
            onOpen = {
                if (!onOpenUrl(input)) error = invalidMessage
            },
        )

        Spacer(Modifier.height(Gap.sm))

        // 次要入口：扫码。不做成与「打开」等大的按钮，避免两个主按钮打架。
        ScanEntry(onScan = onScan)

        // 最近连接：只在有记录时出现，形态与上方完全不同（缩进行列表）。
        AnimatedVisibility(
            visible = recent.isNotEmpty(),
            enter = fadeIn() + slideInVertically { it / 3 },
            exit = fadeOut(),
        ) {
            Column {
                Spacer(Modifier.height(Gap.xl))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.launcher_recent),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextAction(
                        label = stringResource(R.string.launcher_recent_clear),
                        onClick = {
                            links.clear()
                            recent = emptyList()
                        },
                    )
                }
                Spacer(Modifier.height(Gap.xs))
                for (link in recent) {
                    RecentRow(
                        title = link.title,
                        subtitle = relativeTime(link.ts),
                        onClick = {
                            if (!onOpenUrl(link.url)) error = invalidMessage
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(Gap.xxl))
    }
}

/**
 * 输入面板：整块 surface，内部是「无边框输入区 + 主按钮」。
 *
 * 输入框不用 OutlinedTextField：它的描边在浅色/深色两套主题下都要额外调色，
 * 而且会让面板里出现「框里套框」。这里用 BasicTextField + 占位文字，
 * 靠 surfaceVariant 的底色差表达可输入区域。
 */
@Composable
private fun UrlEntry(
    input: String,
    error: String?,
    onInputChange: (String) -> Unit,
    onOpen: () -> Unit,
) {
    SurfaceGroup(modifier = Modifier.padding(Gap.hair)) {
        Column(modifier = Modifier.padding(Gap.md)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.control))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = Gap.sm, vertical = Gap.sm),
            ) {
                if (input.isEmpty()) {
                    Text(
                        text = stringResource(R.string.launcher_url_placeholder),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
                BasicTextField(
                    value = input,
                    onValueChange = onInputChange,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = MaterialTheme.typography.bodySmall.fontSize,
                        lineHeight = MaterialTheme.typography.bodySmall.lineHeight,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    maxLines = 3,
                )
            }
            error?.let {
                Spacer(Modifier.height(Gap.xs))
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(Gap.sm))
            PrimaryButton(
                label = stringResource(R.string.launcher_open),
                enabled = input.isNotBlank(),
                onClick = onOpen,
            )
        }
    }
}

/**
 * 次级入口：扫码。
 * 刻意做成「图标 + 文字」的行，而不是与主按钮等大的描边按钮——
 * 两个同权重按钮会让用户不知道该按哪个。
 */
@Composable
private fun ScanEntry(onScan: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.control))
            .clickable(onClick = onScan)
            .padding(horizontal = Gap.md, vertical = Gap.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 扫码图标用几何形状画：一个取景框，不依赖图标库。
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant),
            )
        }
        Spacer(Modifier.width(Gap.sm))
        Text(
            text = stringResource(R.string.launcher_scan),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        Chevron(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
    }
}

/** 最近连接的一行。左侧一条竖线做视觉锚点，与上方卡片形态区分。 */
@Composable
private fun RecentRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.pill))
            .clickable(onClick = onClick)
            .padding(vertical = Gap.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(26.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        Spacer(Modifier.width(Gap.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun relativeTime(ts: Long): String {
    val diff = System.currentTimeMillis() - ts
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3600_000 -> "${diff / 60_000} 分钟前"
        diff < 86_400_000 -> "${diff / 3600_000} 小时前"
        else -> "${diff / 86_400_000} 天前"
    }
}
