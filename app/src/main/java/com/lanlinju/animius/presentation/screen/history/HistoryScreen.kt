package com.lanlinju.animius.presentation.screen.history

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.lanlinju.animius.R
import com.lanlinju.animius.domain.model.History
import com.lanlinju.animius.presentation.component.BackTopAppBar
import com.lanlinju.animius.presentation.component.tvFocus
import com.lanlinju.animius.presentation.component.LoadingIndicator
import com.lanlinju.animius.presentation.component.PopupMenuListItem
import com.lanlinju.animius.presentation.component.SourceBadge
import com.lanlinju.animius.presentation.component.StateHandler
import com.lanlinju.animius.util.CROSSFADE_DURATION
import com.lanlinju.animius.util.LOW_CONTENT_ALPHA
import com.lanlinju.animius.util.SourceMode
import com.lanlinju.animius.util.VIDEO_ASPECT_RATIO
import com.lanlinju.animius.util.isAndroidTV

@Composable
fun HistoryScreen(
    onBackClick: () -> Unit,
    onNavigateToAnimeDetail: (detailUrl: String, mode: SourceMode) -> Unit,
) {
    val viewModel: HistoryViewModel = hiltViewModel()
    val historyListState by viewModel.historyList.collectAsState()
    var showDeleteAllHistoriesDialog by remember { mutableStateOf(false) }
    var pendingDeleteUrl by remember { mutableStateOf<String?>(null) }

    StateHandler(state = historyListState,
        onLoading = { LoadingIndicator() },
        onFailure = {}
    ) { resource ->
        resource.data?.let { histories ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .navigationBarsPadding()
            ) {
                BackTopAppBar(
                    title = stringResource(id = R.string.play_history),
                    actions = {
                        DeleteHistoryButton(onClick = { showDeleteAllHistoriesDialog = true })
                    },
                    onBackClick = onBackClick
                )

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(dimensionResource(id = R.dimen.small_padding))
                ) {
                    items(histories, key = { it.detailUrl }) { history ->
                        PopupMenuListItem(
                            menuText = stringResource(id = R.string.delete),
                            onClick = {
                                onNavigateToAnimeDetail(history.detailUrl, history.sourceMode)
                            },
                            // 选了"删除"先弹二次确认（P0-5），不确认绝不删
                            onMenuItemClick = { pendingDeleteUrl = history.detailUrl }
                        ) {
                            HistoryItem(history = history)
                        }
                    }
                }
            }
        }
    }

    if (showDeleteAllHistoriesDialog) {
        DeleteAllHistoriesDialog(
            onDismissRequest = { showDeleteAllHistoriesDialog = false },
            onDeleteAllHistories = viewModel::deleteAllHistories
        )
    }

    if (pendingDeleteUrl != null) {
        DeleteHistoryConfirmDialog(
            onDismissRequest = { pendingDeleteUrl = null },
            onConfirm = {
                viewModel.deleteHistory(pendingDeleteUrl.orEmpty())
                pendingDeleteUrl = null
            }
        )
    }
}

@Composable
fun HistoryItem(
    modifier: Modifier = Modifier,
    history: History,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(dimensionResource(id = R.dimen.history_item_height)),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = dimensionResource(id = R.dimen.small_padding),
                vertical = 4.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(dimensionResource(id = R.dimen.small_padding))
        ) {
            SourceBadge(
                text = history.sourceMode.name,
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(history.imgUrl)
                        .crossfade(CROSSFADE_DURATION)
                        .build(),
                    contentDescription = stringResource(id = R.string.lbl_anime_img),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(VIDEO_ASPECT_RATIO)
                        .clip(RoundedCornerShape(dimensionResource(id = R.dimen.history_cover_radius)))
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = history.title,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 2,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = history.lastEpisodeName,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = LOW_CONTENT_ALPHA),
                        style = MaterialTheme.typography.bodySmall,
                    )

                    Text(
                        text = history.time,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = LOW_CONTENT_ALPHA),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

            }
        }
    }

}

@Composable
fun DeleteHistoryButton(
    onClick: () -> Unit,
) {
    IconButton(
        modifier = Modifier.tvFocus(shape = CircleShape),
        onClick = onClick
    ) {
        Icon(Icons.Outlined.DeleteOutline, contentDescription = stringResource(id = R.string.delete))
    }
}

/**
 * 确认框按钮的按键免疫：长按 2 秒触发的对话框弹出时，用户还按着确定键，
 * 残余的按键重复(KeyDown repeat)与松开的 KeyUp 会打进框内焦点按钮把它误点掉。
 * 这里要求"框内见过的第一次新按压(repeatCount==0 的 KeyDown)"之后才放行按键，
 * 旧按压流一律吞掉。
 */
private fun Modifier.tvDialogKeyGuard(): Modifier = composed {
    var sawFreshDown by remember { mutableStateOf(false) }
    onPreviewKeyEvent { event ->
        val keyCode = event.nativeKeyEvent.keyCode
        val isConfirmKey = keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_SPACE
        if (!isConfirmKey) return@onPreviewKeyEvent false
        when {
            event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0 -> {
                sawFreshDown = true
                false
            }

            sawFreshDown -> false

            else -> true
        }
    }
}

@Composable
fun DeleteHistoryConfirmDialog(
    modifier: Modifier = Modifier,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit
) {
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    val isTv = remember { isAndroidTV(context) }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(R.string.delete_history_record)) },
        text = { Text(text = stringResource(R.string.confirm_delete_this_history)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.tvDialogKeyGuard()
            ) {
                Text(stringResource(id = R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissRequest,
                modifier = Modifier
                    .then(if (isTv) Modifier.focusRequester(focusRequester) else Modifier)
                    .tvDialogKeyGuard()
            ) {
                Text(stringResource(id = R.string.cancel))
            }
        }
    )
    // TV 上默认焦点落在「取消」，连按确定绝不会误删（总表原则 3）
    LaunchedEffect(isTv) {
        if (isTv) {
            withFrameNanos { }
            runCatching { focusRequester.requestFocus() }
        }
    }
}

@Composable
fun DeleteAllHistoriesDialog(
    modifier: Modifier = Modifier,
    onDismissRequest: () -> Unit,
    onDeleteAllHistories: () -> Unit
) {
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    val isTv = remember { isAndroidTV(context) }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(R.string.clear_all_histories)) },
        text = { Text(text = stringResource(R.string.are_you_sure_you_want_to_clear_all_histories)) },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismissRequest()
                    onDeleteAllHistories()
                },
                modifier = Modifier.tvDialogKeyGuard()
            ) {
                Text(stringResource(id = R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissRequest,
                modifier = Modifier
                    .then(if (isTv) Modifier.focusRequester(focusRequester) else Modifier)
                    .tvDialogKeyGuard()
            ) {
                Text(stringResource(id = R.string.cancel))
            }
        }
    )
    LaunchedEffect(isTv) {
        if (isTv) {
            withFrameNanos { }
            runCatching { focusRequester.requestFocus() }
        }
    }
}
