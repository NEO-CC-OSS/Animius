package com.lanlinju.animius.presentation.screen.detail

import android.content.Context
import android.os.Environment
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.lanlinju.animius.data.remote.bangumi.MetadataDetailMerger
import com.lanlinju.animius.data.remote.bangumi.MetadataIndexClient
import com.lanlinju.animius.data.remote.bangumi.MetadataItem
import com.lanlinju.animius.data.repository.ResolveProgress
import com.lanlinju.animius.data.repository.SourceResolveResult
import com.lanlinju.animius.data.repository.SourceResolver
import com.lanlinju.animius.domain.model.AnimeDetail
import com.lanlinju.animius.domain.model.Download
import com.lanlinju.animius.domain.model.Episode
import com.lanlinju.animius.domain.model.Favourite
import com.lanlinju.animius.domain.model.History
import com.lanlinju.animius.domain.repository.AnimeRepository
import com.lanlinju.animius.domain.repository.RoomRepository
import com.lanlinju.animius.domain.usecase.GetAnimeDetailUseCase
import com.lanlinju.animius.presentation.navigation.Screen
import com.lanlinju.animius.util.KEY_USE_DOWNLOAD_DIRECTORY
import com.lanlinju.animius.util.Resource
import com.lanlinju.animius.util.SourceMode
import com.lanlinju.animius.util.log
import com.lanlinju.animius.util.onSuccess
import com.lanlinju.animius.util.preferences
import com.lanlinju.download.download
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 元数据详情页的选源状态（驱动进度提示与「播放源」信息行） */
sealed interface SourceInfoState {
    data object Idle : SourceInfoState

    /** 正在逐个尝试源；index/total 从 1 开始，total 为 0 表示还在准备 */
    data class Resolving(val index: Int, val total: Int, val mode: SourceMode?) : SourceInfoState

    /**
     * 正在并发匹配：concurrency 个源同时在飞，tried 为已启动的源数，total 为候选总数。
     * 风控降级回串行后重新变回 [Resolving]。
     */
    data class ResolvingConcurrent(
        val concurrency: Int,
        val tried: Int,
        val total: Int,
    ) : SourceInfoState

    data class Ready(
        val mode: SourceMode,
        val siteTitle: String,
        val fromCache: Boolean,
        val metadataTitle: String,
    ) : SourceInfoState

    data class Failed(val reason: String, val attempts: Int) : SourceInfoState
}

@HiltViewModel
class AnimeDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val animeRepository: AnimeRepository,
    private val roomRepository: RoomRepository,
    private val getAnimeDetailUseCase: GetAnimeDetailUseCase,
    private val sourceResolver: SourceResolver,
    private val metadataIndexClient: MetadataIndexClient,
) : ViewModel() {

    /**
     * 页面内容：`Success(骨架)`（只有元数据）→ `Success(合并后完整详情)`。
     * 选源失败不再把整页置 Error——有骨架就保留骨架，失败原因走 [sourceInfo]。
     * 2026-10-22 设备统一：骨架与并发选源对所有设备生效（手机/平板不再走 tv.6 的整页 Loading）。
     */
    private val _metadataDetailState: MutableStateFlow<Resource<AnimeDetail?>> =
        MutableStateFlow(value = Resource.Loading)
    val metadataDetailState: StateFlow<Resource<AnimeDetail?>>
        get() = _metadataDetailState

    private val _isFavourite: MutableStateFlow<Boolean> =
        MutableStateFlow(value = false)
    val isFavourite: StateFlow<Boolean>
        get() = _isFavourite

    private val _sourceInfo: MutableStateFlow<SourceInfoState> =
        MutableStateFlow(SourceInfoState.Idle)
    val sourceInfo: StateFlow<SourceInfoState>
        get() = _sourceInfo

    /**
     * 显式骨架标志：元数据骨架已上屏、剧集还没到 → true。
     *
     * Screen 用它决定剧集区渲染「匹配中/失败」占位，**不再用 `episodes.isEmpty()` 推断骨架**：
     * 源站入口 / 手机的零剧集详情（Age 的 PV-only 线路、通道全空）剧集同样为空，但那时不是骨架，
     * 必须保持原有剧集区逻辑（线路/反转/更多 不消失）。
     *
     * 转移：骨架上屏 → true；完整详情合并成功 → false；整页 Error → false；
     * 选源失败但骨架仍在屏上 → 保持 true（占位要显示每个源的原因 + 重试/去搜索）。
     */
    private val _isSkeleton: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val isSkeleton: StateFlow<Boolean>
        get() = _isSkeleton

    var detailUrl: String
    var mode: SourceMode

    private val metadataTitle: String
    private var metadataItem: MetadataItem? = null

    /** 源站入口的元数据反查只做一次（Room Flow 会多次 emit） */
    private var metadataLookupDone = false

    /** Bangumi id：播放失败时清缓存、换源成功后写回 */
    val metadataId: Long

    /** 连续点重试时取消上一轮解析，避免两个协程互相覆盖状态 */
    private var resolveJob: Job? = null

    /** 骨架出现的时刻：只用于 `DETAIL skeleton shown → DETAIL episodes ready` 的耗时打点 */
    private var skeletonShownAt: Long? = null

    /** 元数据详情：解析失败时「去搜索」用 */
    val isMetadataDetail: Boolean
        get() = metadataId > 0

    val searchTitle: String
        get() = metadataTitle

    /** 元数据原名：换源搜索中文标题失败时的兜底 */
    val originalTitle: String
        get() = metadataItem?.originalTitle.orEmpty()

    /** 元数据别名（Bangumi infobox）：搜索与换源时一并使用 */
    val aliases: List<String>
        get() = metadataItem?.aliases.orEmpty()

    init {
        savedStateHandle.toRoute<Screen.AnimeDetail>().let {
            this.mode = it.mode
            this.detailUrl = it.detailUrl
            this.metadataId = it.metadataId
            this.metadataTitle = it.metadataTitle
            if (it.metadataId > 0 || it.detailUrl.isBlank()) {
                resolveMetadata()
            } else {
                getAnimeDetail(this.detailUrl)
            }
        }
    }

    /**
     * 元数据条目没有源站详情地址：先按标题解析可用源，再复用现有详情/历史逻辑。
     * 元数据本身（简介/标签）只影响展示，解析失败也能靠路由里的标题继续找源。
     *
     * 顺序固定：`findItem`（纯内存）→ 立刻 `Success(骨架)` → 启动选源 → `Success(合并)`；
     * 选源失败保留骨架 + `sourceInfo = Failed`，不再把整页置 Error。
     */
    private fun resolveMetadata() {
        resolveJob?.cancel()
        resolveJob = viewModelScope.launch {
            // 重试时已有骨架：不清空已显示的内容（否则会闪回整页转圈）
            if (_metadataDetailState.value.data == null) {
                _metadataDetailState.value = Resource.Loading
            }
            _sourceInfo.value = SourceInfoState.Resolving(index = 0, total = 0, mode = null)

            // 元数据从已加载的轻包 index 取（days + related_subjects + featured 合并查找表），
            // 不再为了取一条数据解析 9 MB 整包
            val item = if (metadataId > 0) {
                metadataIndexClient.findItem(metadataId)
                    ?: withTimeoutOrNull(5_000) { metadataIndexClient.loadItem(metadataId) }
            } else {
                null
            }
            metadataItem = item

            val title = item?.title?.ifBlank { metadataTitle } ?: metadataTitle
            if (title.isBlank()) {
                val reason = "标题为空"
                _isSkeleton.value = false
                _sourceInfo.value = SourceInfoState.Failed(reason, 0)
                _metadataDetailState.value = Resource.Error(IllegalStateException(reason))
                return@launch
            }

            // 骨架：元数据全在本地 index 里，不等任何源站请求先出页面（所有设备统一）
            val skeletonState = AnimeDetailState.initial(item, fallbackTitle = title)
            if (skeletonState.data != null) {
                _metadataDetailState.value = skeletonState
                _isSkeleton.value = true
                skeletonShownAt = System.currentTimeMillis()
                "DETAIL skeleton shown id=$metadataId title=$title".log("BehaviorTrace")
            }

            // 并发选源（上限 3，先成功后取消其余）：所有设备统一，不再区分串行/并发
            val result = sourceResolver.resolveConcurrent(
                title = title,
                originalTitle = item?.originalTitle.orEmpty(),
                aliases = item?.aliases.orEmpty(),
                expectedEpisodes = item?.totalEpisodes ?: 0,
                metadataId = metadataId,
                currentMode = mode,
                onProgress = ::onResolveProgress,
            )

            when (result) {
                is SourceResolveResult.Success -> {
                    detailUrl = result.source.detailUrl
                    mode = result.source.mode
                    _sourceInfo.value = SourceInfoState.Ready(
                        mode = result.source.mode,
                        siteTitle = result.source.siteTitle,
                        fromCache = result.fromCache,
                        metadataTitle = title,
                    )
                    val resolveOkLog = "DETAIL resolve ok id=$metadataId mode=${result.source.mode} " +
                        "cache=${result.fromCache} episodes=${result.source.episodes.size}"
                    resolveOkLog.log("BehaviorTrace")
                    val episodesReadyLog = "DETAIL episodes ready id=$metadataId " +
                        "mode=${result.source.mode} episodes=${result.source.episodes.size} " +
                        "elapsedMs=${skeletonShownAt?.let { System.currentTimeMillis() - it } ?: -1}"
                    episodesReadyLog.log("BehaviorTrace")
                    loadResolvedDetail(result.source.detail)
                }

                is SourceResolveResult.Failure -> {
                    val reason = result.attempts.joinToString("；") {
                        "${it.mode.name}: ${it.reason}"
                    }
                    _sourceInfo.value = SourceInfoState.Failed(reason, result.attempts.size)
                    // 有骨架就保留骨架（页面继续可看），没有骨架才整页报错
                    _metadataDetailState.value =
                        AnimeDetailState.onResolveFailure(_metadataDetailState.value, reason)
                    "DETAIL resolve failed id=$metadataId reason=$reason".log("BehaviorTrace")
                }
            }
        }
    }

    /** 选源进度：并发档位（>1）走并发文案，降级回串行后走原有文案 */
    private fun onResolveProgress(progress: ResolveProgress) {
        _sourceInfo.value = if (progress.concurrency > 1) {
            SourceInfoState.ResolvingConcurrent(
                concurrency = progress.concurrency,
                tried = progress.index,
                total = progress.total,
            )
        } else {
            SourceInfoState.Resolving(
                index = progress.index,
                total = progress.total,
                mode = progress.mode,
            )
        }
    }

    /** 解析成功后用解析层已取到的详情合并历史进度，再用元数据覆盖标题/封面/简介/标签 */
    private fun loadResolvedDetail(preloaded: AnimeDetail) {
        viewModelScope.launch {
            _isFavourite.value = roomRepository.checkFavourite(detailUrl).first()
            getAnimeDetailUseCase.fromPreloaded(detailUrl, preloaded).collect {
                _metadataDetailState.value = when (it) {
                    is Resource.Success -> Resource.Success(
                        it.data?.let(::applyMetadata) ?: _metadataDetailState.value.data
                    )

                    else -> AnimeDetailState.onResolveFailure(
                        _metadataDetailState.value,
                        it.error?.message ?: "详情合并失败",
                    )
                }
                // 剧集到了 → 骨架结束；data 为空说明还是骨架（合并失败），保持 true
                if (it is Resource.Success && it.data != null) {
                    _isSkeleton.value = false
                }
                "DETAIL mode=$mode url=$detailUrl state=${it::class.simpleName}"
                    .log("BehaviorTrace")
            }
        }
    }

    private fun applyMetadata(detail: AnimeDetail): AnimeDetail =
        MetadataDetailMerger.merge(detail, metadataItem)

    private fun getAnimeDetail(detailUrl: String) {
        viewModelScope.launch {
            _isFavourite.value = roomRepository.checkFavourite(detailUrl).first()
            getAnimeDetailUseCase(detailUrl, mode).collect { state ->
                val merged = if (state is Resource.Success) {
                    val detail = state.data!!
                    // 混合详情（tv.9 切片 3）：源站标题在本地轻包反查元数据，
                    // 命中则标题/封面/简介/标签/相关推荐用元数据，剧集仍是源站；反查不到纯源站展示。
                    // 只查一次：Room 的剧集/历史 Flow 会多次 emit，重复全量遍历没有意义。
                    if (!metadataLookupDone) {
                        metadataLookupDone = true
                        metadataItem = metadataIndexClient.findByTitle(detail.title)
                        if (metadataItem != null) {
                            "DETAIL metadata matched title=${detail.title}".log("BehaviorTrace")
                        }
                    }
                    Resource.Success(applyMetadata(detail))
                } else {
                    state
                }
                _metadataDetailState.value = merged
                "DETAIL mode=$mode url=$detailUrl state=${state::class.simpleName}".log("BehaviorTrace")
            }
        }
    }

    fun favourite(favourite: Favourite) {
        // 骨架态（还没解析到源）detailUrl 为空：这种收藏从收藏页点开只带空地址，
        // 会变成 metadataId=0 + 标题空 → 永久 Error，所以直接不写库（UI 也已禁用入口）
        if (favourite.detailUrl.isBlank()) return
        viewModelScope.launch {
            _isFavourite.value = !_isFavourite.value
            roomRepository.addOrRemoveFavourite(favourite)
        }
    }

    fun getDefaultDownloadPath(title: String, episodeName: String, context: Context): String {
        val isDownloadDirEnabled = context.preferences.getBoolean(KEY_USE_DOWNLOAD_DIRECTORY, false)

        val baseDir = if (isDownloadDirEnabled) {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath + "/Animius"
        } else {
            context.getExternalFilesDir("download")!!.path
        }

        val path = baseDir + "/${mode}/${title}/${episodeName}.mp4"
        return path
    }

    fun addHistory(animeDetail: AnimeDetail, episode: Episode) {
        val history = History(
            title = animeDetail.title,
            imgUrl = animeDetail.img,
            detailUrl = detailUrl,
            episodes = listOf(episode),
            sourceMode = mode
        )
        viewModelScope.launch {
            roomRepository.addHistory(history)
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun addDownload(download: Download, episodeUrl: String, file: File) {
        viewModelScope.launch {
            animeRepository.getVideoData(episodeUrl, mode)
                .onSuccess {
                    val videoUrl = it.url
                    val downloadDetail =
                        download.downloadDetails.first().copy(downloadUrl = videoUrl)
                    roomRepository.addDownload(download.copy(downloadDetails = listOf(downloadDetail)))
                    // 开始下载视频
                    GlobalScope.download(videoUrl, saveName = file.name, savePath = file.parent!!)
                        .start()
                }
        }
    }

    fun handleDownloadedEpisode(episodes: List<Episode>): Flow<List<Episode>> {
        return flow {
            roomRepository.checkDownload(detailUrl).collect { isStoredDownload ->
                if (!isStoredDownload) {
                    emit(episodes)
                } else {
                    roomRepository.getDownloadDetails(detailUrl).collect { downloadedEpisodes ->
                        val episodeList = episodes.map { episode ->

                            val downloadIndex =
                                downloadedEpisodes.indexOfFirst { d -> d.title == episode.name }

                            episode.copy(isDownloaded = downloadIndex != -1)
                        }

                        emit(episodeList)
                    }
                }
            }
        }
    }

    fun retry() {
        when (AnimeDetailState.onRetry(_metadataDetailState.value)) {
            // 已有骨架：只重跑选源，保留已显示的内容
            AnimeDetailState.RetryPlan.ResolveOnly -> resolveMetadata()

            AnimeDetailState.RetryPlan.Full -> {
                _metadataDetailState.value = Resource.Loading
                if (detailUrl.isBlank()) {
                    // 还没解析到源：重新选源
                    resolveMetadata()
                } else {
                    getAnimeDetail(this.detailUrl)
                }
            }
        }
    }

    fun onChannelClick(index: Int, episodes: List<Episode>) {
        _metadataDetailState.value = Resource.Success(
            _metadataDetailState.value.data!!.copy(
                channelIndex = index,
                episodes = episodes
            )
        )
    }
}
