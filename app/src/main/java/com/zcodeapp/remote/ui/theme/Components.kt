package com.zcodeapp.remote.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animateFloatAsState

/**
 * 共享 UI 组件。
 *
 * 为什么要抽出来：五个自建页面原先各写各的按钮、卡片、分隔线，
 * 圆角从 6dp 到 16dp 不等，字号组合也各不相同——放在一起就是「五个不同的应用」。
 * 这里定义唯一的一套，页面只组合、不重画。
 *
 * 形态参照官方远控页：
 * - 卡片是**整块**浅色/深色面板，圆角 12dp，没有边框（靠底色差区分）。
 * - 分组之间靠**间距**，不靠逐块描边。
 * - 强调动作是实心前景色块（浅色模式黑底白字），次要动作只是文字。
 */

/** 圆角：全应用只有三档，避免「每个组件一个圆角」。 */
object Radius {
    val card = 12.dp
    val control = 10.dp
    val pill = 6.dp
}

/** 页面容器：统一底色 + 系统栏内边距 + 水平留白。 */
@Composable
fun ScreenColumn(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = Gap.lg),
        content = content,
    )
}

/**
 * 页面标题栏：左侧大标题 + 可选副标题，右侧一个文字动作。
 *
 * 不用 Material 的 TopAppBar：它自带 64dp 高度与固定字号，
 * 会让自建页面看起来像系统设置而不是一个应用。
 */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Gap.lg, bottom = Gap.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(Gap.xs))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (action != null && onAction != null) {
            Spacer(Modifier.width(Gap.sm))
            TextAction(action, onAction)
        }
    }
}

/** 区块标签：小字号 + 字距 + 弱化色。分组靠它 + 间距区分，不靠边框。 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = Gap.xs, bottom = Gap.sm),
    )
}

/** 分组容器：整块卡片，无边框，圆角 12dp。 */
@Composable
fun SurfaceGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.card))
            .background(MaterialTheme.colorScheme.surface),
        content = content,
    )
}

/** 组内分隔线：从左侧留白开始，不横贯整块，视觉更轻。 */
@Composable
fun RowDivider(startInset: androidx.compose.ui.unit.Dp = Gap.md) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = startInset)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

/**
 * 主按钮：实心前景色块。
 *
 * 浅色模式黑底白字、深色模式白底黑字——与官方页面一致。
 * 带轻微按压回弹，否则在纯色块上按下去没有反馈。
 */
@Composable
fun PrimaryButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.98f else 1f,
        animationSpec = MotionSpec.press(),
        label = "primary-scale",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .scale(scale)
            .clip(RoundedCornerShape(Radius.control))
            .background(
                if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 文字动作：只有文字，弱化色。次要动作一律用它，不做成第二个按钮。 */
@Composable
fun TextAction(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    emphasize: Boolean = false,
    color: Color? = null,
) {
    val base = color ?: if (emphasize) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = if (emphasize) FontWeight.SemiBold else FontWeight.Medium,
        color = if (enabled) base else base.copy(alpha = 0.4f),
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.pill))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Gap.sm, vertical = Gap.xs),
    )
}

/** 设置行：标题 + 说明 + 右侧可选控件。整行可点。 */
@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = Gap.md, vertical = Gap.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = titleColor,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(Gap.hair))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.let {
            Spacer(Modifier.width(Gap.sm))
            it()
        }
    }
}

/** 状态点：一个小圆点，用于「已开启 / 未授权」这类二元状态。 */
@Composable
fun StatusDot(color: Color, size: androidx.compose.ui.unit.Dp = 7.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(percent = 50))
            .background(color),
    )
}

/**
 * 前进箭头。
 *
 * 用 Canvas 画一条折线，而不是拿两根旋转横杠拼——两根 45°/-45° 横杠在中心相交
 * 会画成「×」，语义完全反了（那是关闭，不是前进）。
 */
@Composable
fun Chevron(color: Color, size: androidx.compose.ui.unit.Dp = 8.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val path = Path().apply {
            moveTo(w * 0.3f, h * 0.18f)
            lineTo(w * 0.72f, h * 0.5f)
            lineTo(w * 0.3f, h * 0.82f)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/** 品牌标记：方块字标。不放图片资源，避免多一份资产要维护。 */
@Composable
fun BrandMark(size: androidx.compose.ui.unit.Dp = 26.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size / 4))
            .background(MaterialTheme.colorScheme.onBackground),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Z",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.background,
        )
    }
}

/** 空状态：一句说明居中偏上，不铺满整屏。 */
@Composable
fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Gap.lg),
    )
}
