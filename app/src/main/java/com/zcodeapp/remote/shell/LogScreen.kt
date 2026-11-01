package com.zcodeapp.remote.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zcodeapp.remote.R
import com.zcodeapp.remote.diag.DiagLog
import com.zcodeapp.remote.ui.theme.EmptyHint
import com.zcodeapp.remote.ui.theme.Gap
import com.zcodeapp.remote.ui.theme.Radius
import com.zcodeapp.remote.ui.theme.ScreenHeader
import com.zcodeapp.remote.ui.theme.TextAction
import java.io.File

/**
 * 诊断日志查看器。
 *
 * 为什么要在应用内看，而不是只做「导出」：出问题的设备通常没有 adb，
 * 而分享面板要求用户先装邮件/微信，故障当场根本走不完这条链路。
 * 这里把日志直接铺在屏幕上，用户截个图就能把现场带出来。
 *
 * 倒序显示（最新在上）：故障现场最关心的是「刚刚发生了什么」，
 * 而日志文件是追加写的，正序看要先滑到底。
 */
@Composable
fun LogScreen(onBack: () -> Unit, file: File? = null) {
    val context = LocalContext.current
    // 传了 file 就只看那一份（从日志管理器点进来的场景）；
    // 没传则看全部——设置页的「查看」是「我现在就想看整体」的意思。
    var text by remember(file) {
        mutableStateOf(file?.let { DiagLog.readFile(it) } ?: DiagLog.readAll(context))
    }
    val lines = remember(text) {
        text.lineSequence().filter { it.isNotBlank() }.toList().asReversed()
    }

    // 系统返回必须回到设置页。没有这个处理时，按返回会直接结束 Activity，
    // 用户从日志页退出就等于退出了整个应用——而日志页正是出问题时要反复进出的地方。
    BackHandler(onBack = onBack)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = Gap.lg),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                ScreenHeader(
                    title = stringResource(R.string.settings_log_title),
                    subtitle = stringResource(R.string.log_lines, lines.size),
                )
            }
            TextAction(stringResource(R.string.log_refresh), {
                text = DiagLog.readAll(context)
            })
            TextAction(stringResource(R.string.close), onBack)
        }

        if (lines.isEmpty()) {
            EmptyHint(stringResource(R.string.log_empty))
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(Radius.card))
                    .background(MaterialTheme.colorScheme.surface),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                itemsIndexed(lines) { _, line ->
                    // 错误行着色：日志里 90% 是正常时序，把 E/W 挑出来才看得见问题。
                    val isError = line.contains(" E #")
                    val isWarn = line.contains(" W #")
                    Text(
                        text = line,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        color = when {
                            isError -> MaterialTheme.colorScheme.error
                            isWarn -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(horizontal = Gap.sm, vertical = 3.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(Gap.md))
    }
}
