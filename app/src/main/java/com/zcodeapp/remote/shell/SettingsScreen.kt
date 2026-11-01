package com.zcodeapp.remote.shell

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zcodeapp.remote.BuildConfig
import com.zcodeapp.remote.R
import com.zcodeapp.remote.ZcodeRemoteApp
import com.zcodeapp.remote.background.KeepAliveService
import com.zcodeapp.remote.background.KeepAliveState
import com.zcodeapp.remote.diag.DiagLog
import com.zcodeapp.remote.diag.LogUploader
import com.zcodeapp.remote.notify.AttentionActivity
import com.zcodeapp.remote.ui.theme.Chevron
import com.zcodeapp.remote.ui.theme.Gap
import com.zcodeapp.remote.ui.theme.Radius
import com.zcodeapp.remote.ui.theme.RowDivider
import com.zcodeapp.remote.ui.theme.ScreenHeader
import com.zcodeapp.remote.ui.theme.SectionLabel
import com.zcodeapp.remote.ui.theme.SettingRow
import com.zcodeapp.remote.ui.theme.StatusDot
import com.zcodeapp.remote.ui.theme.SurfaceGroup
import com.zcodeapp.remote.ui.theme.TextAction
import com.zcodeapp.remote.ui.theme.signalAmber
import com.zcodeapp.remote.ui.theme.signalGreen
import com.zcodeapp.remote.update.Updater

/**
 * 设置页。
 *
 * 设计取向（2026-10-27 重做，对齐官方远控页）：
 * - **分组列表，不是一堆描边卡片**：小标签 + 一整块表面 + 发丝分隔线，
 *   分组之间的关系靠间距与标签表达，容器只出现一次。
 * - **标题与正文的落差**：页标题 26sp 半粗，区块标签 11sp 带字距，正文 13sp。
 * - **后台保活排在诊断之上**：它是用户真正会开的功能，诊断是出问题时才用的。
 * - 强调色只用在「可点的动作」上，说明文字一律中性色。
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenLog: () -> Unit,
    onOpenLogFiles: () -> Unit = {},
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val app = context.applicationContext as ZcodeRemoteApp

    // 后台保活：开关 + 两个权限状态都要能实时反映，所以用可刷新状态。
    var keepAlive by remember { mutableStateOf(KeepAliveState.isEnabled(context)) }
    var batteryOk by remember { mutableStateOf(KeepAliveState.isIgnoringBatteryOptimizations(context)) }
    var notifOk by remember { mutableStateOf(KeepAliveState.notificationsEnabled(context)) }
    var fullScreenOk by remember {
        mutableStateOf(com.zcodeapp.remote.notify.AttentionActivity.canUseFullScreenIntent(context))
    }
    var updateState by remember { mutableStateOf<Updater.State>(Updater.State.Idle) }
    var uploading by remember { mutableStateOf(false) }
    var uploadNote by remember { mutableStateOf<String?>(null) }
    var showTokenInput by remember { mutableStateOf(!LogUploader.isConfigured(context)) }
    var tokenInput by remember { mutableStateOf("") }
    var privacyMode by remember { mutableStateOf(app.settings.privacyMode) }

    // 从系统设置返回时刷新权限状态：用户可能刚在系统里开了电池白名单/通知。
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        batteryOk = KeepAliveState.isIgnoringBatteryOptimizations(context)
        notifOk = KeepAliveState.notificationsEnabled(context)
        fullScreenOk = com.zcodeapp.remote.notify.AttentionActivity.canUseFullScreenIntent(context)
        onPauseOrDispose { }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Gap.lg),
    ) {
        ScreenHeader(
            title = stringResource(R.string.settings_title),
            subtitle = stringResource(R.string.settings_subtitle),
            action = stringResource(R.string.close),
            onAction = onBack,
        )

        // ---- 后台保活（这一屏的主线） ----
        SectionLabel(stringResource(R.string.settings_section_ability))
        SurfaceGroup {
            SettingRow(
                title = stringResource(R.string.settings_background_title),
                subtitle = stringResource(R.string.settings_background_summary),
            ) {
                Switch(
                    checked = keepAlive,
                    onCheckedChange = { enabled ->
                        keepAlive = enabled
                        app.settings.backgroundListening = enabled
                        KeepAliveState.setEnabled(context, enabled)
                        KeepAliveService.apply(context, enabled)
                        if (enabled) {
                            // 开保活时顺手把两个关键权限的缺口指出来，
                            // 否则用户以为开了就一定收得到。
                            batteryOk = KeepAliveState.isIgnoringBatteryOptimizations(context)
                            notifOk = KeepAliveState.notificationsEnabled(context)
                            app.sessionController.setKeepAliveIntent(true)
                        } else {
                            // 关掉时立刻解除页面侧屏蔽与后台巡检，
                            // 别让「已经关了还在后台跑」的状态残留。
                            app.sessionController.clearKeepAliveShield()
                        }
                    },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        checkedThumbColor = MaterialTheme.colorScheme.background,
                    ),
                )
            }
            // 权限状态只在实际开启时展示：关着的时候说「电池没优化」没有意义。
            if (keepAlive) {
                RowDivider()
                PermissionLine(
                    label = stringResource(R.string.settings_perm_notifications),
                    granted = notifOk,
                    actionLabel = stringResource(R.string.settings_perm_open),
                    onAction = {
                        // 通知权限被拒后系统不再弹窗，只能引导到应用设置页。
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS,
                                ).apply {
                                    putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                },
                            )
                        }
                    },
                )
                RowDivider()
                PermissionLine(
                    label = stringResource(R.string.settings_perm_battery),
                    granted = batteryOk,
                    actionLabel = stringResource(R.string.settings_perm_allow),
                    onAction = {
                        KeepAliveState.requestIgnoreBatteryOptimizations(context)
                    },
                )
                // 息屏弹窗需要的第三件事：全屏通知权限（Android 14+ 需用户手动授予）。
                // 缺它时系统会把提醒降级成普通通知，锁屏上只闪一下、看不到内容——
                // 用户会以为「根本没提醒」，实际是权限不够。
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    RowDivider()
                    PermissionLine(
                        label = stringResource(R.string.settings_perm_fullscreen),
                        granted = fullScreenOk,
                        actionLabel = stringResource(R.string.settings_perm_allow),
                        onAction = {
                            AttentionActivity.openFullScreenIntentSettings(context)
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(Gap.lg))

        // ---- 诊断日志 ----
        SectionLabel(stringResource(R.string.settings_section_log))
        SurfaceGroup {
            // 隐私模式提醒：开源渠道（PRIVACY_NOTICE=true）在未开启时醒目提示；
            // 维护者构建 zr.privacyNotice=false，不出现这一行。
            if (BuildConfig.PRIVACY_NOTICE && !privacyMode) {
                SettingRow(
                    title = stringResource(R.string.settings_privacy_notice_title),
                    subtitle = stringResource(R.string.settings_privacy_notice_body),
                    titleColor = MaterialTheme.colorScheme.error,
                ) {
                    TextAction(
                        label = stringResource(R.string.settings_privacy_enable),
                        onClick = {
                            app.settings.privacyMode = true
                            privacyMode = true
                        },
                        emphasize = true,
                    )
                }
                RowDivider()
            }
            SettingRow(
                title = stringResource(R.string.settings_privacy_title),
                subtitle = stringResource(R.string.settings_privacy_summary),
            ) {
                Switch(
                    checked = privacyMode,
                    onCheckedChange = { enabled ->
                        privacyMode = enabled
                        app.settings.privacyMode = enabled
                    },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        checkedThumbColor = MaterialTheme.colorScheme.background,
                    ),
                )
            }
            RowDivider()
            SettingRow(
                title = if (uploading) {
                    stringResource(R.string.settings_log_uploading)
                } else {
                    stringResource(R.string.settings_log_upload)
                },
                subtitle = stringResource(
                    R.string.settings_log_upload_desc,
                    LogUploader.describeWindow(context),
                ),
                titleColor = if (uploading) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                onClick = {
                    if (uploading) return@SettingRow
                    if (!LogUploader.isConfigured(context)) {
                        showTokenInput = true
                        uploadNote = null
                    } else {
                        uploading = true
                        uploadNote = null
                        LogUploader.upload(context) { result ->
                            uploading = false
                            uploadNote = when (result) {
                                is LogUploader.Result.Ok ->
                                    "已上传 ${result.files.size} 个文件（${result.bytes / 1024} KB），开发者可直接拉取"
                                is LogUploader.Result.Failed -> "上传失败：${result.reason}"
                                LogUploader.Result.NoToken -> "未配置上传凭据"
                                LogUploader.Result.NoLogs -> "没有可上传的日志"
                            }
                        }
                    }
                },
            ) {
                Chevron(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
            }
            uploadNote?.let { note ->
                RowDivider()
                Text(
                    text = note,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (note.startsWith("已上传")) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.padding(horizontal = Gap.md, vertical = Gap.sm),
                )
            }
            if (showTokenInput) {
                RowDivider()
                TokenInput(
                    value = tokenInput,
                    onValueChange = { tokenInput = it },
                    onSave = {
                        LogUploader.saveToken(context, tokenInput)
                        showTokenInput = false
                        tokenInput = ""
                        uploadNote = "凭据已保存到本机"
                    },
                    onCancel = {
                        showTokenInput = false
                        tokenInput = ""
                    },
                )
            }
            RowDivider()
            // 排查工具排在一起，降权显示：它们是次要动作，不该与上传争注意力。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Gap.md, vertical = Gap.sm),
                horizontalArrangement = Arrangement.spacedBy(Gap.lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextAction(stringResource(R.string.log_files_title), onOpenLogFiles)
                TextAction(stringResource(R.string.settings_log_view), onOpenLog)
                TextAction(
                    label = stringResource(R.string.settings_log_export),
                    onClick = { shareLog(context) },
                )
            }
        }

        Spacer(Modifier.height(Gap.lg))

        // ---- 应用更新 ----
        SectionLabel(stringResource(R.string.settings_section_update))
        SurfaceGroup {
            UpdateBlock(
                state = updateState,
                currentVersion = BuildConfig.VERSION_NAME,
                onUpdate = {
                    updateState = Updater.State.Checking
                    Updater.updateNow(context) { updateState = it }
                },
            )
        }

        Spacer(Modifier.height(Gap.xl))

        Text(
            text = stringResource(R.string.settings_about, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.alpha(0.7f),
        )
        Spacer(Modifier.height(Gap.xxl))
    }
}

/**
 * 权限状态行：一个状态点 + 名称 + 右侧动作。
 *
 * 用状态点而不是开关：这些权限只能由用户在系统界面里改，
 * 我们这里放一个假开关只会让人以为点一下就能生效。
 */
@Composable
private fun PermissionLine(
    label: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
) {
    val dotColor = if (granted) signalGreen() else signalAmber()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Gap.md, vertical = Gap.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(dotColor)
        Spacer(Modifier.width(Gap.sm))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (granted) {
            Text(
                text = stringResource(R.string.settings_perm_granted),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            TextAction(actionLabel, onAction, emphasize = true)
        }
    }
}

/** 令牌输入：一个无边框输入区 + 保存/取消两个文字动作。 */
@Composable
private fun TokenInput(
    value: String,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(modifier = Modifier.padding(Gap.md)) {
        Text(
            text = stringResource(R.string.settings_log_token_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Gap.sm))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.control))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = Gap.sm, vertical = Gap.sm),
        ) {
            if (value.isEmpty()) {
                Text(
                    text = stringResource(R.string.settings_log_token_placeholder),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = TextStyle(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = MaterialTheme.typography.labelSmall.fontSize,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
            )
        }
        Spacer(Modifier.height(Gap.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(Gap.lg)) {
            TextAction(
                label = stringResource(R.string.settings_log_token_save),
                onClick = onSave,
                enabled = value.isNotBlank(),
                emphasize = true,
            )
            TextAction(stringResource(R.string.close), onCancel)
        }
    }
}

/**
 * 更新区块。
 *
 * 【一个按钮走完全程】旧版按状态换三个不同按钮（检查/下载/安装），
 * 用户每步都要重新找按钮，中间走开就断了。现在只有一个动作「检查并更新」，
 * 点了之后自动下载、校验、拉起安装器，状态只在说明文字里体现。
 */
@Composable
private fun UpdateBlock(
    state: Updater.State,
    currentVersion: String,
    onUpdate: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val busy = state is Updater.State.Checking ||
        state is Updater.State.Downloading ||
        state is Updater.State.Verifying ||
        state is Updater.State.Installing

    val detail = when (state) {
        is Updater.State.Idle -> null
        is Updater.State.Checking -> "正在检查…"
        is Updater.State.UpToDate -> "已是最新版本"
        is Updater.State.Available -> "发现 ${state.release.versionName}，正在下载…"
        is Updater.State.Downloading -> "正在下载 ${state.progress}%"
        is Updater.State.Verifying -> "下载完成，正在校验…"
        is Updater.State.Ready -> "校验通过，准备安装"
        is Updater.State.Installing -> "已拉起安装器，请在系统弹窗中确认"
        is Updater.State.NeedsInstallPermission -> "需要在系统设置里允许安装未知应用"
        is Updater.State.Failed -> state.message
    }

    Column(modifier = Modifier.padding(horizontal = Gap.md, vertical = Gap.md)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.settings_update_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "v$currentVersion",
                style = MaterialTheme.typography.labelSmall,
                color = muted,
            )
        }
        detail?.let {
            Spacer(Modifier.height(Gap.xs))
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = if (state is Updater.State.Failed) {
                    MaterialTheme.colorScheme.error
                } else {
                    muted
                },
            )
        }
        if (state is Updater.State.Downloading) {
            Spacer(Modifier.height(Gap.sm))
            LinearProgressIndicator(
                progress = { state.progress / 100f },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
        Spacer(Modifier.height(Gap.sm))
        TextAction(
            label = if (busy) {
                stringResource(R.string.settings_update_busy)
            } else {
                stringResource(R.string.settings_update_action)
            },
            onClick = onUpdate,
            enabled = !busy,
            emphasize = !busy,
            color = if (busy) muted else null,
        )
    }
}

/** 把日志文本作为分享内容发出去（微信/邮件/文件管理器都可接收）。 */
private fun shareLog(context: android.content.Context) {
    val text = DiagLog.readAll(context)
    if (text.isBlank()) return
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_SUBJECT, "ZcodeRemote diag log")
        putExtra(android.content.Intent.EXTRA_TEXT, text)
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching {
        context.startActivity(
            android.content.Intent.createChooser(
                intent,
                context.getString(R.string.settings_log_export),
            ),
        )
    }
}
