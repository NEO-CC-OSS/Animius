package com.lanlinju.animius.presentation.screen.search

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lanlinju.animius.R
import com.lanlinju.animius.data.repository.AggregateGroup
import com.lanlinju.animius.presentation.component.MediaSmall
import com.lanlinju.animius.presentation.screen.captcha.CaptchaWebViewActivity
import com.lanlinju.animius.util.SourceMode
import com.lanlinju.animius.util.isAndroidTV

/**
 * 搜索页（tv.9 切片 3）：搜索是兜底，一次搜全部可用源，按源分组展示。
 *
 * 只显示源站结果；不显示元数据搜索结果（用户 2026-10-22 拍板）。
 * 每个源单独一组：搜索中 / 超时不可用 / 无结果 / 结果列表。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onNavigateToAnimeDetail: (detailUrl: String, mode: SourceMode) -> Unit,
    onBackClick: () -> Unit,
    initialQuery: String = "",
) {
    val viewModel = hiltViewModel<SearchViewModel>()
    val groups by viewModel.groups.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val searchQuery by viewModel.query.collectAsState()
    val needCaptchaUrl by viewModel.needCaptchaUrl.collectAsState()
    var searchBarExpanded by rememberSaveable { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val context = LocalContext.current

    // 从周表/详情点进来时带标题预填并直接搜全部源；只消费一次
    LaunchedEffect(initialQuery) {
        if (!viewModel.initialQueryConsumed && initialQuery.isNotBlank()) {
            viewModel.initialQueryConsumed = true
            if (viewModel.query.value.isBlank()) {
                searchBarExpanded = true
                viewModel.searchAll(initialQuery)
            }
        }
    }

    val captchaLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.clearNeedCaptcha()
            viewModel.searchAll(searchQuery)
        }
    }

    // 显示验证码提示对话框
    needCaptchaUrl?.let { url ->
        AlertDialog(
            onDismissRequest = { viewModel.clearNeedCaptcha() },
            title = { Text("需要验证码验证") },
            text = { Text("搜索时遇到验证码，请完成验证后重试") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearNeedCaptcha()
                        captchaLauncher.launch(
                            CaptchaWebViewActivity.createIntent(context, url)
                        )
                    }
                ) {
                    Text("去验证")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.clearNeedCaptcha() }) {
                    Text("取消")
                }
            }
        )
    }

    Box(Modifier.fillMaxSize()) {
        SearchBar(
            inputField = {
                SearchBarDefaults.InputField(
                    query = searchQuery,
                    onQueryChange = viewModel::onQuery,
                    onSearch = {
                        searchBarExpanded = true
                        viewModel.searchAll(it)
                        keyboardController?.hide()
                    },
                    expanded = searchBarExpanded,
                    onExpandedChange = { },
                    placeholder = {
                        Text(stringResource(id = R.string.lbl_search_placeholder))
                    },
                    leadingIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(id = R.string.back)
                            )
                        }
                    },
                    trailingIcon = {
                        IconButton(onClick = viewModel::clearSearchQuery) {
                            Icon(
                                imageVector = Icons.Rounded.Clear,
                                contentDescription = stringResource(id = R.string.clear)
                            )
                        }
                    }
                )
            },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .focusRequester(focusRequester),
            expanded = searchBarExpanded,
            onExpandedChange = { if (!it) onBackClick() },
        ) {
            SearchResults(
                groups = groups,
                isSearching = isSearching,
                onItemClick = onNavigateToAnimeDetail,
            )
        }
    }

    LaunchedEffect(Unit) {
        if (!viewModel.hasFocusRequest) {
            viewModel.hasFocusRequest = true
            focusRequester.requestFocus()
        }
    }
}

@Composable
private fun SearchResults(
    groups: List<AggregateGroup>,
    isSearching: Boolean,
    onItemClick: (String, SourceMode) -> Unit,
) {
    if (groups.isEmpty()) {
        if (isSearching) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(id = R.string.txt_empty_result),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        return
    }

    val isTv = isAndroidTV(LocalContext.current)
    val firstItemFocus = remember { FocusRequester() }
    val firstGroup = groups.firstOrNull { it.results.isNotEmpty() }
    // 结果首次出现时把焦点送到第一个结果（TV 遥控器从搜索框按 DOWN 不一定进得来）
    LaunchedEffect(firstGroup) {
        if (isTv && firstGroup != null) {
            runCatching { firstItemFocus.requestFocus() }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        groups.forEach { group ->
            item(key = "header-${group.mode.name}") {
                SourceGroupHeader(group = group, isSearching = isSearching)
            }
            if (group.results.isNotEmpty()) {
                item(key = "row-${group.mode.name}") {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp),
                    ) {
                        itemsIndexed(
                            items = group.results,
                            key = { _, item -> "${group.mode.name}-${item.url}" },
                        ) { index, item ->
                            val isFirst = group.mode == firstGroup?.mode && index == 0
                            MediaSmall(
                                image = item.img,
                                label = item.title,
                                onClick = { onItemClick(item.url, group.mode) },
                                modifier = if (isFirst) {
                                    Modifier.focusRequester(firstItemFocus)
                                } else {
                                    Modifier
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 组标题：源名 + 该组状态（搜索中 / 超时 / 无结果 / N 个结果） */
@Composable
private fun SourceGroupHeader(group: AggregateGroup, isSearching: Boolean) {
    val status = when {
        group.error != null -> "不可用：${group.error}"
        group.results.isEmpty() && isSearching -> "搜索中…"
        group.results.isEmpty() -> "无结果"
        else -> "${group.results.size} 个结果"
    }
    Column(Modifier.padding(start = 4.dp, top = 4.dp)) {
        Text(
            text = group.mode.name,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = status,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
