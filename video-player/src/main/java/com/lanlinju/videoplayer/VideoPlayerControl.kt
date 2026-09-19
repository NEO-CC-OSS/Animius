package com.lanlinju.videoplayer


import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.view.KeyEvent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lanlinju.videoplayer.icons.Fullscreen
import com.lanlinju.videoplayer.icons.FullscreenExit
import com.lanlinju.videoplayer.component.Slider
import com.lanlinju.videoplayer.icons.ArrowBackIos
import com.lanlinju.videoplayer.icons.Pause
import com.lanlinju.videoplayer.icons.Subtitles
import com.lanlinju.videoplayer.icons.SubtitlesOff
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun VideoPlayerControl(
    state: VideoPlayerState,
    title: String,
    subtitle: String? = null,
    background: Color = Color.Black.copy(0.2f),
    contentColor: Color = Color.LightGray,
    progressLineColor: Color = MaterialTheme.colorScheme.inversePrimary,
    danmakuEnabled: Boolean,
    onBackClick: () -> Unit = {},
    onNextClick: () -> Unit = {},
    onDanmakuClick: (Boolean) -> Unit = {},
    optionsContent: (@Composable () -> Unit)? = null,
    playPauseFocusRequester: FocusRequester? = null,
) {
    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(background)
                // TV 焦点导航期间：控制栏内任何焦点变化/按键都重置自动隐藏计时
                .onFocusChanged { if (it.hasFocus) state.keepControlUiAlive() }
                .onPreviewKeyEvent {
                    state.keepControlUiAlive()
                    false
                }
                .padding(
                    start = horizontalPadding(),
                    end = horizontalPadding(),
                    top = 18.dp
                )
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                ControlHeader(
                    modifier = Modifier.fillMaxWidth(),
                    title = title,
                    subtitle = subtitle,
                    isSeeking = state.isSeeking.value,
                    onBackClick = onBackClick,
                    optionsContent = optionsContent,
                )

                Spacer(Modifier.size(1.dp))

                BottomControlBar(
                    modifier = Modifier.fillMaxWidth(),
                    progressLineColor = progressLineColor,
                    state = state,
                    enabledDanmaku = danmakuEnabled,
                    onNextClick = onNextClick,
                    onDanmakuClick = onDanmakuClick,
                    playPauseFocusRequester = playPauseFocusRequester
                )
            }
        }
    }
}

@Composable
private fun ControlHeader(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String?,
    isSeeking: Boolean,
    onBackClick: (() -> Unit)?,
    optionsContent: (@Composable () -> Unit)? = null,
) {
    if (isSeeking) return

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            modifier = Modifier
                .tvControlFocus()
                .size(BigIconButtonSize),
            onClick = { onBackClick?.invoke() }
        ) {
            Icon(imageVector = Icons.Rounded.ArrowBackIos, contentDescription = "返回")
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = LocalContentColor.current,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            subtitle?.let {
                Text(
                    text = it,
                    color = LocalContentColor.current.copy(0.80f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        optionsContent?.invoke()
    }
}

@Composable
private fun BottomControlBar(
    modifier: Modifier,
    progressLineColor: Color,
    state: VideoPlayerState,
    enabledDanmaku: Boolean,
    onNextClick: () -> Unit,
    onDanmakuClick: (Boolean) -> Unit,
    playPauseFocusRequester: FocusRequester? = null,
) {
    val timestamp =
        remember(
            state.videoDurationMs.value,
            state.videoPositionMs.value.milliseconds.inWholeSeconds
        ) {
            prettyVideoTimestamp(
                state.videoPositionMs.value.milliseconds,
                state.videoDurationMs.value.milliseconds
            )
        }
    val context = LocalContext.current
    val isTv = remember { isTvDevice(context) }
    var sliderFocused by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        if (!state.isSeeking.value) {
            TimelineControl(
                timestamp = timestamp,
                isFullScreen = state.isFullscreen.value,
                onFullScreenToggle = { state.control.setFullscreen(!state.isFullscreen.value) }
            )
        }

        Slider(
            value = state.videoProgress.value.safeValue(),
            secondValue = state.videoBufferedProgress.value.safeValue(),
            onClick = { state.onClickSlider(it) },
            onValueChange = { state.onSeeking(it) },
            onValueChangeFinished = { state.onSeeked() },
            isSeeking = state.isSeeking.value || sliderFocused,
            color = progressLineColor,
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .then(
                    if (isTv) Modifier
                        .onFocusChanged { sliderFocused = it.isFocused }
                        .focusable()
                        // 总表：焦点在进度条时左右＝拖动进度。单击步进 15s 立即 seek
                        // （与点击进度条行为一致）；过滤按住重复，防止连发疯狂跳集
                        .onKeyEvent { keyEvent ->
                            if (keyEvent.type == KeyEventType.KeyDown &&
                                keyEvent.nativeKeyEvent.repeatCount == 0
                            ) {
                                when (keyEvent.nativeKeyEvent.keyCode) {
                                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                                        state.control.skip(-15_000L)
                                        true
                                    }

                                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                        state.control.skip(15_000L)
                                        true
                                    }

                                    else -> false
                                }
                            } else {
                                false
                            }
                        }
                    else Modifier
                ),
        )

        if (!state.isSeeking.value) {
            PlaybackControl(
                isPlaying = state.isPlaying.value,
                enabledDanmaku = enabledDanmaku,
                onPlayPause = { if (state.isPlaying.value) state.control.pause() else state.control.play() },
                onNextClick = onNextClick,
                onDanmakuClick = onDanmakuClick,
                speedText = state.speedText.value,
                resizeText = state.resizeText.value,
                onSpeedClick = state::showSpeedUi,
                onResizeClick = state::showResizeUi,
                onEpisodeClick = state::showEpisodeUi,
                playPauseFocusRequester = playPauseFocusRequester
            )
        } else Spacer(modifier = Modifier.size(MediumIconButtonSize))
    }
}

@Composable
private fun TimelineControl(
    timestamp: String,
    isFullScreen: Boolean,
    onFullScreenToggle: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = timestamp, style = MaterialTheme.typography.bodySmall)
        Spacer(modifier = Modifier.weight(1.0f))
        AdaptiveIconButton(
            modifier = Modifier.size(SmallIconButtonSize),
            onClick = onFullScreenToggle
        ) {
            Icon(
                imageVector = if (isFullScreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                contentDescription = if (isFullScreen) "退出全屏" else "全屏"
            )
        }
    }
}

@Composable
private fun PlaybackControl(
    isPlaying: Boolean,
    enabledDanmaku: Boolean,
    onPlayPause: () -> Unit,
    onNextClick: () -> Unit,
    onDanmakuClick: (Boolean) -> Unit,
    speedText: String,
    resizeText: String,
    onSpeedClick: () -> Unit,
    onResizeClick: () -> Unit,
    onEpisodeClick: () -> Unit,
    playPauseFocusRequester: FocusRequester? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PlayPauseButton(isPlaying, onPlayPause, playPauseFocusRequester)
            NextEpisodeIcon(onClick = onNextClick)
            DanmakuIcon(onClick = onDanmakuClick, danmakuEnabled = enabledDanmaku)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            AdaptiveTextButton(text = "选集", onClick = onEpisodeClick)
            AdaptiveTextButton(text = speedText, onClick = onSpeedClick)
            AdaptiveTextButton(text = resizeText, onClick = onResizeClick)
        }
    }
}

@Composable
private fun PlayPauseButton(
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    focusRequester: FocusRequester?,
) {
    AdaptiveIconButton(
        modifier = Modifier
            .size(MediumIconButtonSize)
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester)
                else Modifier
            ),
        onClick = onPlayPause
    ) {
        Icon(
            modifier = Modifier.fillMaxSize(),
            imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            contentDescription = if (isPlaying) "暂停" else "播放"
        )
    }
}

@Composable
private fun horizontalPadding(): Dp {
    return 8.dp + if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) {
        24.dp
    } else 0.dp
}

private fun Float?.safeValue() = this?.takeIf { !it.isNaN() } ?: 0f

@Composable
private fun DanmakuIcon(
    danmakuEnabled: Boolean,
    onClick: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    AdaptiveIconButton(
        onClick = { onClick(!danmakuEnabled) },
        modifier.size(MediumIconButtonSize),
    ) {
        if (danmakuEnabled) {
            Icon(Icons.Rounded.Subtitles, contentDescription = "禁用弹幕")
        } else {
            Icon(Icons.Rounded.SubtitlesOff, contentDescription = "启用弹幕")
        }
    }
}

@Composable
private fun NextEpisodeIcon(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AdaptiveIconButton(
        modifier = modifier.size(MediumIconButtonSize), // 下一集
        onClick = onClick
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_next),
            contentDescription = "下一集"
        )
    }
}

@Composable
fun AdaptiveTextButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    color: Color = LocalContentColor.current,
    style: TextStyle = MaterialTheme.typography.bodyMedium
) {
    AdaptiveIconButton(
        modifier = modifier.size(MediumIconButtonSize),
        onClick = onClick
    ) {
        Text(
            text = text,
            color = color,
            style = style,
        )
    }
}

@Composable
private fun AdaptiveIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    enabledIndication: Boolean = true,
    content: @Composable () -> Unit
) {
    val indication = LocalIndication.current

    Box(
        modifier = modifier
            .tvControlFocus()
            .clip(CircleShape)
            .clickable(
                onClick = onClick,
                enabled = enabled,
                interactionSource = interactionSource,
                indication = if (enabledIndication) indication else null
            ),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

private fun isTvDevice(context: Context): Boolean {
    val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
    val isTv = uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    val hasLeanbackFeature =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    return isTv || hasLeanbackFeature
}

/**
 * 播放器控制栏的 TV 焦点外观（与 app 模块的 TvFocus 同款机制，主题色描边+放大）。
 * 控制栏背景为深色，按钮小，描边收窄到 2dp、放大 1.1 保证辨识度。
 * 模块内独立实现：video-player 不依赖 app，两端样式语义保持一致。
 */
private fun Modifier.tvControlFocus(): Modifier = composed {
    val context = LocalContext.current
    val isTv = remember { isTvDevice(context) }
    if (!isTv) {
        this
    } else {
        var isFocused by remember { mutableStateOf(false) }
        val scale by animateFloatAsState(
            targetValue = if (isFocused) 1.1f else 1f,
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            label = "tvControlScale"
        )
        val borderAlpha by animateFloatAsState(
            targetValue = if (isFocused) 1f else 0f,
            animationSpec = spring(stiffness = Spring.StiffnessMedium),
            label = "tvControlBorderAlpha"
        )
        this
            .onFocusChanged { isFocused = it.isFocused }
            .scale(scale)
            .border(
                width = 2.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = borderAlpha),
                shape = CircleShape
            )
    }
}

private val BigIconButtonSize = 52.dp
private val MediumIconButtonSize = 42.dp
private val SmallIconButtonSize = 32.dp