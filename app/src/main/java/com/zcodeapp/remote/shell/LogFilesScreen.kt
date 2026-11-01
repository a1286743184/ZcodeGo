package com.zcodeapp.remote.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zcodeapp.remote.R
import com.zcodeapp.remote.diag.DiagLog
import com.zcodeapp.remote.diag.LogUploader
import com.zcodeapp.remote.ui.theme.Gap
import com.zcodeapp.remote.ui.theme.Radius
import com.zcodeapp.remote.ui.theme.ScreenHeader
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志文件管理。
 *
 * 三件事在同一屏完成，因为它们在排查时是同一个动作的三步：
 * 1. 按日期看有哪些日志（跨天问题时需要挑）
 * 2. 勾选要上传的那几个（默认已勾选「最近 2 小时」窗口）
 * 3. 上传 / 查看
 *
 * 为什么默认勾选时间窗而不是全选：全选会把几天的日志一起推上去，
 * 上传慢、云端杂乱，而排查通常只看最近那段。需要跨天时手动加选即可。
 */
@Composable
fun LogFilesScreen(onBack: () -> Unit, onOpenFile: (File) -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    var allFiles by remember { mutableStateOf(DiagLog.listFiles(context)) }
    // 默认勾选「当前时间往前 2 小时」覆盖到的文件——与「一键上传」的窗口一致。
    var selected by remember {
        mutableStateOf(
            LogUploader.defaultWindowFiles(context).map { it.name }.toSet(),
        )
    }
    var uploading by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    // 按日期分组：文件名 diag-YYYYMMDD-HH.log，取中间 8 位。
    val grouped = remember(allFiles) {
        allFiles.groupBy { f ->
            f.name.removePrefix("diag-").take(8)
        }.toSortedMap(reverseOrder())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = Gap.lg),
    ) {
        ScreenHeader(
            title = stringResource(R.string.log_files_title),
            subtitle = stringResource(R.string.log_files_summary, allFiles.size),
            action = stringResource(R.string.close),
            onAction = onBack,
        )

        // 选中计数与动作：始终显示，避免用户不知道「现在选了哪些」。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Gap.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Gap.lg),
        ) {
            Text(
                text = stringResource(R.string.log_files_selected, selected.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (selected.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.log_files_upload_selected),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (uploading) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.pill))
                        .clickable(enabled = !uploading) {
                            val files = allFiles.filter { it.name in selected }
                            uploading = true
                            note = null
                            LogUploader.uploadFiles(context, files) { result ->
                                uploading = false
                                note = when (result) {
                                    is LogUploader.Result.Ok ->
                                        "已上传 ${result.files.size} 个文件（${result.bytes / 1024} KB）"
                                    is LogUploader.Result.Failed -> "上传失败：${result.reason}"
                                    LogUploader.Result.NoToken -> "未配置上传凭据"
                                    LogUploader.Result.NoLogs -> "没有可上传的日志"
                                }
                            }
                        }
                        .padding(horizontal = Gap.xs, vertical = Gap.xs),
                )
            }
        }
        note?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = if (it.startsWith("已上传")) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.padding(bottom = Gap.sm),
            )
        }

        if (allFiles.isEmpty()) {
            Text(
                text = stringResource(R.string.log_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            for ((day, files) in grouped) {
                item(key = "h-$day") {
                    DayHeader(
                        day = day,
                        files = files,
                        allSelected = files.all { it.name in selected },
                        onToggleAll = { want ->
                            selected = if (want) {
                                selected + files.map { it.name }
                            } else {
                                selected - files.map { it.name }.toSet()
                            }
                        },
                    )
                }
                items(files, key = { it.name }) { f ->
                    FileRow(
                        file = f,
                        checked = f.name in selected,
                        onToggle = {
                            selected = if (f.name in selected) {
                                selected - f.name
                            } else {
                                selected + f.name
                            }
                        },
                        onOpen = { onOpenFile(f) },
                    )
                }
            }
            item { Spacer(Modifier.height(Gap.xl)) }
        }
    }
}

/** 日期分组头：显示日期与当天文件数，右侧可全选/取消当天。 */
@Composable
private fun DayHeader(day: String, files: List<File>, allSelected: Boolean, onToggleAll: (Boolean) -> Unit) {
    val readable = remember(day) {
        runCatching {
            SimpleDateFormat("yyyyMMdd", Locale.US).parse(day)?.let {
                SimpleDateFormat("MM 月 dd 日", Locale.SIMPLIFIED_CHINESE).format(it)
            }
        }.getOrNull() ?: day
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Gap.md, bottom = Gap.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = readable,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.log_files_count, files.size),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(Gap.sm))
        Text(
            text = if (allSelected) {
                stringResource(R.string.log_files_clear_day)
            } else {
                stringResource(R.string.log_files_select_day)
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(Radius.pill))
                .clickable { onToggleAll(!allSelected) }
                .padding(horizontal = Gap.xs, vertical = Gap.hair),
        )
    }
}

/** 单个日志文件：勾选框 + 文件名 + 大小，点名字可直接查看。 */
@Composable
private fun FileRow(file: File, checked: Boolean, onToggle: () -> Unit, onOpen: () -> Unit) {
    val hour = remember(file.name) {
        file.name.removePrefix("diag-").removeSuffix(".log")
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.card))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = Gap.sm, vertical = Gap.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CheckBox(checked = checked, onClick = onToggle)
        Spacer(Modifier.width(Gap.sm))
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onOpen),
        ) {
            Text(
                text = hour,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "%.1f KB".format(file.length() / 1024.0),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(R.string.settings_log_view),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(Radius.pill))
                .clickable(onClick = onOpen)
                .padding(horizontal = Gap.xs, vertical = Gap.hair),
        )
    }
    Spacer(Modifier.height(Gap.hair))
}

/**
 * 自绘勾选框。
 *
 * 不用 Material Checkbox：它的默认尺寸与四周留白会把行撑得很高，
 * 而这里一行要放三样信息。自绘一个 16dp 的方块，视觉重量刚好。
 */
@Composable
private fun CheckBox(checked: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(RoundedCornerShape(Radius.pill))
            .background(
                if (checked) MaterialTheme.colorScheme.primary
                else Color.Transparent
            )
            .border(
                1.5.dp,
                if (checked) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(Radius.pill),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            // 对勾：两条短线拼成，不引图标库。
            // 颜色必须在 Composable 上下文里取好再进 DrawScope——
            // DrawScope 不是 @Composable，里面读 MaterialTheme 编译不过。
            val checkColor = MaterialTheme.colorScheme.onPrimary
            androidx.compose.foundation.Canvas(modifier = Modifier.size(10.dp)) {
                val w = size.width
                val h = size.height
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(w * 0.15f, h * 0.55f)
                    lineTo(w * 0.42f, h * 0.82f)
                    lineTo(w * 0.88f, h * 0.2f)
                }
                drawPath(
                    path = path,
                    color = checkColor,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 1.6.dp.toPx(),
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                        join = androidx.compose.ui.graphics.StrokeJoin.Round,
                    ),
                )
            }
        }
    }
}
