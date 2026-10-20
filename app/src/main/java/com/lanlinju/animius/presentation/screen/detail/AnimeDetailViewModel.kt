package com.lanlinju.animius.presentation.screen.detail

import android.content.Context
import android.os.Environment
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.lanlinju.animius.data.remote.bangumi.BangumiCalendarClient
import com.lanlinju.animius.data.remote.bangumi.MetadataDetailMerger
import com.lanlinju.animius.data.remote.bangumi.MetadataItem
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
    private val calendarClient: BangumiCalendarClient,
) : ViewModel() {

    private val _animeDetailState: MutableStateFlow<Resource<AnimeDetail?>> =
        MutableStateFlow(value = Resource.Loading)
    val animeDetailState: StateFlow<Resource<AnimeDetail?>>
        get() = _animeDetailState

    private val _isFavourite: MutableStateFlow<Boolean> =
        MutableStateFlow(value = false)
    val isFavourite: StateFlow<Boolean>
        get() = _isFavourite

    private val _sourceInfo: MutableStateFlow<SourceInfoState> =
        MutableStateFlow(SourceInfoState.Idle)
    val sourceInfo: StateFlow<SourceInfoState>
        get() = _sourceInfo

    var detailUrl: String
    var mode: SourceMode

    private val metadataId: Long
    private val metadataTitle: String
    private var metadataItem: MetadataItem? = null

    /** 连续点重试时取消上一轮解析，避免两个协程互相覆盖状态 */
    private var resolveJob: Job? = null

    /** 元数据详情：解析失败时「去搜索」用 */
    val isMetadataDetail: Boolean
        get() = metadataId > 0

    val searchTitle: String
        get() = metadataTitle

    /** 元数据原名：换源搜索中文标题失败时的兜底 */
    val originalTitle: String
        get() = metadataItem?.originalTitle.orEmpty()

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
     */
    private fun resolveMetadata() {
        resolveJob?.cancel()
        resolveJob = viewModelScope.launch {
            _animeDetailState.value = Resource.Loading
            _sourceInfo.value = SourceInfoState.Resolving(index = 0, total = 0, mode = null)

            val item = if (metadataId > 0) {
                calendarClient.findItem(metadataId)
                    ?: withTimeoutOrNull(5_000) { calendarClient.loadItem(metadataId) }
            } else {
                null
            }
            metadataItem = item

            val title = item?.title?.ifBlank { metadataTitle } ?: metadataTitle
            if (title.isBlank()) {
                val reason = "标题为空"
                _sourceInfo.value = SourceInfoState.Failed(reason, 0)
                _animeDetailState.value = Resource.Error(IllegalStateException(reason))
                return@launch
            }

            when (
                val result = sourceResolver.resolve(
                    title = title,
                    originalTitle = item?.originalTitle.orEmpty(),
                    metadataId = metadataId,
                    currentMode = mode,
                    onProgress = { progress ->
                        _sourceInfo.value = SourceInfoState.Resolving(
                            index = progress.index,
                            total = progress.total,
                            mode = progress.mode,
                        )
                    },
                )
            ) {
                is SourceResolveResult.Success -> {
                    detailUrl = result.source.detailUrl
                    mode = result.source.mode
                    _sourceInfo.value = SourceInfoState.Ready(
                        mode = result.source.mode,
                        siteTitle = result.source.siteTitle,
                        fromCache = result.fromCache,
                        metadataTitle = title,
                    )
                    "DETAIL resolve ok id=$metadataId mode=${result.source.mode} " +
                        "cache=${result.fromCache} episodes=${result.source.episodes.size}"
                        .log("BehaviorTrace")
                    loadResolvedDetail(result.source.detail)
                }

                is SourceResolveResult.Failure -> {
                    val reason = result.attempts.joinToString("；") {
                        "${it.mode.name}: ${it.reason}"
                    }
                    _sourceInfo.value = SourceInfoState.Failed(reason, result.attempts.size)
                    _animeDetailState.value = Resource.Error(IllegalStateException(reason))
                    "DETAIL resolve failed id=$metadataId reason=$reason".log("BehaviorTrace")
                }
            }
        }
    }

    /** 解析成功后用解析层已取到的详情合并历史进度，再用元数据覆盖标题/封面/简介/标签 */
    private fun loadResolvedDetail(preloaded: AnimeDetail) {
        viewModelScope.launch {
            _isFavourite.value = roomRepository.checkFavourite(detailUrl).first()
            getAnimeDetailUseCase.fromPreloaded(detailUrl, preloaded).collect {
                _animeDetailState.value = when (it) {
                    is Resource.Success -> Resource.Success(it.data?.let(::applyMetadata))
                    else -> it
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
            getAnimeDetailUseCase(detailUrl, mode).collect {
                _animeDetailState.value = it
                "DETAIL mode=$mode url=$detailUrl state=${it::class.simpleName}".log("BehaviorTrace")
            }
        }
    }

    fun favourite(favourite: Favourite) {
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
        _animeDetailState.value = Resource.Loading
        if (detailUrl.isBlank()) {
            // 还没解析到源：重新选源
            resolveMetadata()
        } else {
            getAnimeDetail(this.detailUrl)
        }
    }

    fun onChannelClick(index: Int, episodes: List<Episode>) {
        _animeDetailState.value = Resource.Success(
            _animeDetailState.value.data!!.copy(
                channelIndex = index,
                episodes = episodes
            )
        )
    }
}
