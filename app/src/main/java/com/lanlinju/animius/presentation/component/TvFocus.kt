package com.lanlinju.animius.presentation.component

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lanlinju.animius.util.isAndroidTV

/**
 * TV 端统一的焦点外观：白色描边 + 轻微放大（方案 §5 层次 A1）。
 *
 * 只负责"画"，不负责"聚"：焦点语义仍由调用点的 clickable/combinedClickable 提供。
 * 切勿在调用点再手挂 focusable/indication/hoverable——Button 内部 clickable 本身就是
 * 焦点目标，重复挂会造出第二个"只有焦点、没有点击"的节点，把第一下确定吃掉（方案 P1-5）。
 * 手机/平板上自动退化为原样。
 */
fun Modifier.tvFocus(
    shape: Shape,
    focusedBorderWidth: Dp = 3.dp,
    focusedScale: Float = 1.05f,
): Modifier = composed {
    val context = LocalContext.current
    val isTv = remember { isAndroidTV(context) }
    if (!isTv) {
        this
    } else {
        var isFocused by remember { mutableStateOf(false) }
        val scale by animateFloatAsState(
            targetValue = if (isFocused) focusedScale else 1f,
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            label = "tvFocusScale"
        )
        // 边框宽度恒定、用透明度过渡：border 在宽度为 0（Dp.Hairline）时仍会画 1px 发丝线，
        // 不能拿 0dp 当"不画"用；颜色取主题色，自动跟随外观设置里的主题颜色
        val borderAlpha by animateFloatAsState(
            targetValue = if (isFocused) 1f else 0f,
            animationSpec = spring(stiffness = Spring.StiffnessMedium),
            label = "tvFocusBorderAlpha"
        )
        this
            .onFocusChanged { isFocused = it.isFocused }
            .scale(scale)
            .border(
                width = focusedBorderWidth,
                color = MaterialTheme.colorScheme.primary.copy(alpha = borderAlpha),
                shape = shape
            )
    }
}
