package com.lanlinju.animius.presentation.component

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.lanlinju.animius.R
import com.lanlinju.animius.util.VIDEO_ASPECT_RATIO
import com.lanlinju.animius.util.isAndroidTV

/** TV 端确认键长按触发菜单的时长，延长到 2 秒防误触（默认 500ms 太灵敏） */
private const val TV_LONG_PRESS_MILLIS = 2000L

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PopupMenuListItem(
    menuText: String,
    onClick: () -> Unit,
    onMenuItemClick: () -> Unit,
    content: @Composable () -> Unit,
) {

    var expanded by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val isTv = remember { isAndroidTV(context) }
    var confirmKeyDownMs by remember { mutableStateOf(0L) }
    var longPressFired by remember { mutableStateOf(false) }

    // TV：确定键全接管（onPreviewKeyEvent 先于内部 clickable）——按住 2 秒（震动提示）
    // 松手后弹菜单、不足 2 秒松开才算"打开"；clickable 只保留焦点目标与鼠标点击。
    // 菜单必须在松手后弹出：若按住时就弹，焦点会立刻跳进菜单项，随后松开的 KeyUp
    // 会被菜单项当成"点击"，导致菜单刚出现就被误触（实测踩坑）。
    // 手机/平板：维持原 combinedClickable（500ms 长按）不变。
    val inputModifier = if (isTv) {
        Modifier
            .onPreviewKeyEvent { event ->
                val keyCode = event.nativeKeyEvent.keyCode
                val isConfirmKey = keyCode == KeyEvent.KEYCODE_ENTER ||
                    keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                    keyCode == KeyEvent.KEYCODE_SPACE
                when {
                    !isConfirmKey -> false

                    event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0 -> {
                        confirmKeyDownMs = SystemClock.uptimeMillis()
                        longPressFired = false
                        true
                    }

                    event.type == KeyEventType.KeyDown -> {
                        if (!longPressFired &&
                            SystemClock.uptimeMillis() - confirmKeyDownMs >= TV_LONG_PRESS_MILLIS
                        ) {
                            longPressFired = true
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            // 用户拍板：长按满 2 秒不松手直接弹动作（历史=删除确认框），
                            // 后续残余按键由对话框的 keyGuard 忽略
                            onMenuItemClick()
                        }
                        true
                    }

                    else -> {
                        if (!longPressFired) onClick()
                        true
                    }
                }
            }
            .clickable(onClick = onClick)
    } else {
        Modifier.combinedClickable(
            onLongClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                expanded = true
            },
            onClick = onClick
        )
    }

    Box(
        modifier = Modifier
            .tvFocus(shape = RoundedCornerShape(8.dp))
            .then(inputModifier)
    ) {

        content()

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            offset = DpOffset(
                x = dimensionResource(id = R.dimen.image_cover_height) * VIDEO_ASPECT_RATIO + dimensionResource(
                    id = R.dimen.small_padding
                ),
                y = 0.dp
            ),
        ) {

            DropdownMenuItem(
                text = {
                    Text(
                        text = menuText,
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                onClick = {
                    expanded = false
                    onMenuItemClick()
                }
            )

        }
    }
}