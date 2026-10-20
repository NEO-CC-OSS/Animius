package com.lanlinju.animius.presentation.screen.main

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import com.lanlinju.animius.domain.model.UpdateState
import com.lanlinju.animius.presentation.component.AdaptiveNavigationBar
import com.lanlinju.animius.presentation.component.NavigationBarPath
import com.lanlinju.animius.presentation.screen.favourite.FavouriteScreen
import com.lanlinju.animius.presentation.screen.home.HomeScreen
import com.lanlinju.animius.presentation.screen.week.VersionUpdateDialog
import com.lanlinju.animius.presentation.screen.week.WeekScreen
import com.lanlinju.animius.util.SourceMode
import com.lanlinju.animius.util.isWideScreen
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainScreen(
    onNavigateToAnimeDetail: (detailUrl: String, mode: SourceMode) -> Unit,
    onNavigateToHistory: () -> Unit,
    onNavigateToDownload: () -> Unit,
    onNavigateToSearch: () -> Unit,
    onNavigateToSearchWithQuery: (String) -> Unit,
    onNavigateToAppearance: () -> Unit,
    onNavigateToDanmakuSettings: () -> Unit,
) {
    val viewModel = hiltViewModel<MainViewModel>()
    val showVersionUpdateDialog by viewModel.showVersionUpdateDialog.collectAsState()
    val updateState by viewModel.updateState.collectAsState()
    // TV 优化：启动默认落在时间表页（用户拍板），侧栏顺序不变
    var currentDestination by rememberSaveable { mutableStateOf(NavigationBarPath.RSlash.route) }
    val pagerState = rememberPagerState(initialPage = 0) { NavigationBarPath.entries.size }
    val scope = rememberCoroutineScope()

    val configuration = LocalConfiguration.current // 屏幕方向改变会触发 recompose
    val context = LocalContext.current

    // 在屏幕方向变化时更新 isWideScreen
    val isWideScreen = remember(configuration.orientation) { isWideScreen(context) }

    val navigationBar: @Composable () -> Unit = {
        AdaptiveNavigationBar(
            destinations = NavigationBarPath.entries,
            currentDestination = currentDestination,
            onNavigateToDestination = {
                currentDestination = NavigationBarPath.entries[it].route
                scope.launch { pagerState.scrollToPage(it) }
            },
            isWideScreen = isWideScreen,
        )
    }

    val pagerContent: @Composable (Modifier) -> Unit = { modifier ->
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = false,
            modifier = modifier
        ) { page ->
            when (page) {
                0 -> WeekScreen(
                    onNavigateToAnimeDetail = onNavigateToAnimeDetail,
                    onNavigateToSearchWithQuery = onNavigateToSearchWithQuery,
                    onNavigateToSearch = onNavigateToSearch,
                    onNavigateToHistory = onNavigateToHistory,
                    onNavigateToDownload = onNavigateToDownload,
                    onNavigateToAppearance = onNavigateToAppearance,
                    onNavigateToDanmakuSettings = onNavigateToDanmakuSettings
                )

                1 -> HomeScreen(onNavigateToAnimeDetail)
                2 -> FavouriteScreen(onNavigateToAnimeDetail)
            }
        }
    }

    if (isWideScreen) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .displayCutoutPadding()
        ) {
            navigationBar()
            pagerContent(
                Modifier
                    .fillMaxHeight()
                    .weight(1f)
            )
        }
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            pagerContent(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
            navigationBar()
        }
    }

    // 下载阶段（updateState 非 Idle）也要保持对话框显示，直到完成或用户关闭
    if (showVersionUpdateDialog || updateState !is UpdateState.Idle) {
        VersionUpdateDialog(
            updateVersionName = viewModel.updateVersionName,
            updateDescription = viewModel.updateDescription,
            updateState = updateState,
            onDismissUpdateDialog = viewModel::dismissUpdateDialog,
            onDownloadUpdate = { owner -> viewModel.downloadUpdate(context, owner) },
            onCancelDownload = { viewModel.cancelUpdate(context) },
        )
    }
}
