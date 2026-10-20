package com.lanlinju.animius.presentation.screen.videoplayer

import android.app.Application
import androidx.core.content.edit
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.anime.danmaku.api.DanmakuSession
import com.lanlinju.animius.R
import com.lanlinju.animius.data.repository.EpisodeResolveResult
import com.lanlinju.animius.data.repository.SourceResolver
import com.lanlinju.animius.domain.matching.EpisodeNumber
import com.lanlinju.animius.domain.model.DanmakuResult
import com.lanlinju.animius.domain.model.Episode
import com.lanlinju.animius.domain.model.Video
import com.lanlinju.animius.domain.model.WebVideo
import com.lanlinju.animius.domain.repository.AnimeRepository
import com.lanlinju.animius.domain.repository.DanmakuRepository
import com.lanlinju.animius.domain.repository.RoomRepository
import com.lanlinju.animius.presentation.navigation.PlayerParameters
import com.lanlinju.animius.presentation.navigation.Screen
import com.lanlinju.animius.util.KEY_DANMAKU_ENABLED
import com.lanlinju.animius.util.Resource
import com.lanlinju.animius.util.Result
import com.lanlinju.animius.util.SourceMode
import com.lanlinju.animius.util.log
import com.lanlinju.animius.util.preferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import javax.inject.Inject

@HiltViewModel
class VideoPlayerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val roomRepository: RoomRepository,
    private val animeRepository: AnimeRepository,
    private val danmakuRepository: DanmakuRepository,
    private val sourceResolver: SourceResolver,
    private val application: Application
) : ViewModel() {

    // 保存视频状态的流，默认为加载中
    private val _videoState: MutableStateFlow<Resource<Video>> = MutableStateFlow(Resource.Loading)
    val videoState: StateFlow<Resource<Video>> get() = _videoState

    // 获取保存的偏好设置，初始化弹幕启用状态
    private val preferences = application.preferences
    private val _danmakuEnabled =
        MutableStateFlow(preferences.getBoolean(KEY_DANMAKU_ENABLED, false))
    val danmakuEnabled = _danmakuEnabled.asStateFlow()

    private val _danmakuSession = MutableStateFlow<DanmakuSession?>(null)
    val danmakuSession = _danmakuSession.asStateFlow()

    // 最近一次弹幕获取结果（含失败原因），UI 仅对失败类型轻提示（Success/null 均不渲染）
    private val _danmakuStatus = MutableStateFlow<DanmakuResult?>(null)
    val danmakuStatus = _danmakuStatus.asStateFlow()

    // 换源提示（"正在切换播放源…" / "已切换到 X"），由 UI 用浮层显示后清除
    private val _sourceNotice = MutableStateFlow<String?>(null)
    val sourceNotice = _sourceNotice.asStateFlow()

    // 判断是否为本地视频
    private var isLocalVideo = false
    private var mode: SourceMode? = null

    // 元数据点播才允许取址失败自动换源；搜索/历史/收藏入口保持原行为
    private var autoSource: Boolean = false

    // 当前播放源名（TV 控制条展示）
    val currentSourceName: String?
        get() = mode?.name

    /** 当前番剧标题：失败页「去搜索」用 */
    val currentTitle: String
        get() = playerParams?.title.orEmpty()

    /** 是否为元数据点播（失败页显示换源原因与「去搜索」；其他入口保持原 UI） */
    val canAutoSwitchSource: Boolean
        get() = autoSource

    // 当前集数的URL和历史记录ID
    private var currentEpisodeUrl: String = ""
    private var currentEpisodeIndex: Int = 0
    private var historyId: Long = -1L

    // 导航参数（标题/集数列表等），retry 时旧数据已被 Loading 清空，需由此重建 Video
    private var playerParams: PlayerParameters? = null

    // 自动连播相关
    private var autoContinuePlayJob: Job? = null

    init {
        viewModelScope.launch {
            // 从SavedStateHandle中获取播放模式和视频集数的URL
            savedStateHandle.toRoute<Screen.VideoPlayer>().let {
                val params = PlayerParameters.deserialize(it.parameters)
                playerParams = params
                this@VideoPlayerViewModel.mode = params.mode
                this@VideoPlayerViewModel.autoSource = params.autoSource
                val url = params.episodes[params.episodeIndex].url
                if (params.isLocalVideo) {
                    // 如果是本地视频，获取本地视频
                    isLocalVideo = true
                    getVideoFromLocal(params)
                } else {
                    currentEpisodeUrl = url
                    currentEpisodeIndex = params.episodeIndex
                    // 如果是远程视频，获取历史记录Id并加载远程视频
                    getHistoryId(url)
                    loadRemoteVideo(
                        episodeUrl = url,
                        index = params.episodeIndex,
                        allowSourceSwitch = params.autoSource,
                    )
                }
            }
        }
    }

    /**
     * 获取历史记录ID，用于后续保存播放进度
     */
    private fun getHistoryId(episodeUrl: String) {
        viewModelScope.launch {
            roomRepository.getEpisode(episodeUrl).collect { episode ->
                episode?.let { historyId = it.historyId }
            }
        }
    }

    /**
     * 获取本地视频信息
     * @param params 格式化的本地视频参数
     */
    private fun getVideoFromLocal(params: PlayerParameters) {
        viewModelScope.launch {
            val title = params.title
            val index = params.episodeIndex
            val episodes = params.episodes

            _videoState.value = Resource.Success(
                Video(
                    title = title,
                    url = episodes[index].url,
                    episodeName = episodes[index].name,
                    episodeUrl = episodes[index].url,
                    currentEpisodeIndex = index,
                    episodes = episodes
                )
            )
            "VIDEO local title=$title ep=${episodes[index].name}".log("BehaviorTrace")

            fetchDanmakuSession()
        }
    }

    /**
     * 加载远程视频；取址失败且允许自动换源时，按集号去下一个源找同一集。
     */
    private suspend fun loadRemoteVideo(
        episodeUrl: String,
        index: Int,
        allowSourceSwitch: Boolean,
    ) {
        val currentMode = mode ?: return
        when (val result = animeRepository.getVideoData(episodeUrl, currentMode)) {
            is Result.Success -> applyVideo(result.data, episodeUrl, index)
            is Result.Error -> {
                "VIDEO error mode=$currentMode ep=$episodeUrl reason=${result.error.message}"
                    .log("BehaviorTrace")
                if (allowSourceSwitch) {
                    // 详情能打开不代表能播（如 Age动漫部分条目禁止网页播放）：
                    // 清掉缓存，避免 7 天内一直命中坏源
                    playerParams?.let { sourceResolver.invalidate(it.metadataId, it.title) }
                    switchSource(index, result.error)
                } else {
                    _videoState.value = Resource.Error(result.error)
                }
            }
        }
    }

    /**
     * 取不到播放地址时换源：只按「集号」对应，找不到同集就报错，绝不播成别的集。
     */
    private suspend fun switchSource(index: Int, cause: Throwable) {
        val params = playerParams ?: run {
            _videoState.value = Resource.Error(cause)
            return
        }
        val currentMode = mode ?: return
        val episodeName = params.episodes.getOrNull(index)?.name
        val episodeNumber = episodeName?.let { EpisodeNumber.normalize(it) }
        if (episodeNumber == null) {
            // 集号无法识别（SP/特别篇等）：不猜，直接提示失败
            _videoState.value = Resource.Error(
                IllegalStateException(application.getString(R.string.source_switch_failed, "无法识别集号"))
            )
            "VIDEO switch skipped reason=unknown-episode ep=$episodeName".log("BehaviorTrace")
            return
        }

        _sourceNotice.value = application.getString(R.string.source_switching)
        val result = sourceResolver.resolveEpisode(
            title = params.title,
            originalTitle = params.originalTitle,
            aliases = params.aliases,
            episodeNumber = episodeNumber,
            currentMode = currentMode,
            exclude = setOf(currentMode),
            onProgress = { progress ->
                _sourceNotice.value = application.getString(R.string.source_switching) +
                    "（${progress.index}/${progress.total}：${progress.mode.name}）"
            },
        )

        when (result) {
            is EpisodeResolveResult.Success -> {
                val episode = result.episode
                mode = episode.mode
                playerParams = params.copy(
                    episodes = episode.episodes,
                    episodeIndex = episode.episodeIndex,
                )
                // 换源成功：把可用源写回缓存，下次进入直接命中
                sourceResolver.remember(
                    metadataId = params.metadataId,
                    title = params.title,
                    mode = episode.mode,
                    detailUrl = episode.detailUrl,
                    siteTitle = episode.siteTitle,
                )
                // retry/换集要用新源的地址与索引；历史 Id 按新地址再查一次
                currentEpisodeUrl = episode.episodeUrl
                currentEpisodeIndex = episode.episodeIndex
                getHistoryId(episode.episodeUrl)
                _sourceNotice.value = application.getString(R.string.source_switched, episode.mode.name)
                applyVideo(
                    webVideo = WebVideo(episode.videoUrl, episode.headers),
                    episodeUrl = episode.episodeUrl,
                    index = episode.episodeIndex,
                    episodesOverride = episode.episodes,
                    episodeNameOverride = episode.episodeName,
                )
            }

            is EpisodeResolveResult.Failure -> {
                _sourceNotice.value = null
                val reason = result.attempts.joinToString("；") { "${it.mode.name}: ${it.reason}" }
                val detail = application.getString(
                    R.string.source_resolve_failed_detail,
                    result.attempts.size,
                ) + "：" + reason
                _videoState.value = Resource.Error(
                    IllegalStateException(application.getString(R.string.source_switch_failed, detail))
                )
                "VIDEO switch failed from=$currentMode ep=$episodeNumber reason=$reason"
                    .log("BehaviorTrace")
            }
        }
    }

    private suspend fun applyVideo(
        webVideo: WebVideo,
        episodeUrl: String,
        index: Int,
        episodesOverride: List<Episode>? = null,
        episodeNameOverride: String? = null,
    ) {
        val episodes = episodesOverride
            ?: _videoState.value.data?.episodes
            ?: playerParams?.episodes
            ?: return
        val episodeName = episodeNameOverride
            ?: episodes.getOrNull(index)?.name
            ?: return
        val lastPlayPosition =
            roomRepository.getEpisode(episodeUrl).first()?.lastPlayPosition ?: 0L
        val existing = _videoState.value.data
        _videoState.value = Resource.Success(
            // 换集时旧数据还在，在其上合并新地址；retry 时旧数据已被
            // Resource.Loading 清空（data == null），必须从导航参数重建，
            // 否则这里就是"重试成功反而崩溃"（P2-4）
            existing?.copy(
                url = webVideo.url,
                currentEpisodeIndex = index,
                episodeName = episodeName,
                episodeUrl = episodeUrl,
                lastPlayPosition = lastPlayPosition,
                headers = webVideo.headers,
                episodes = episodes,
            ) ?: Video(
                title = playerParams?.title.orEmpty(),
                url = webVideo.url,
                episodeName = episodeName,
                episodeUrl = episodeUrl,
                lastPlayPosition = lastPlayPosition,
                currentEpisodeIndex = index,
                episodes = episodes,
                headers = webVideo.headers
            )
        )
        "VIDEO success title=${playerParams?.title} ep=$episodeName mode=${mode?.name}"
            .log("BehaviorTrace")
        fetchDanmakuSession()
    }

    fun clearSourceNotice() {
        _sourceNotice.value = null
    }

    /**
     * 获取远程视频
     * @param episodeUrl 视频集数的URL
     */
    private fun getVideoFromRemote(episodeUrl: String, index: Int) {
        viewModelScope.launch {
            loadRemoteVideo(episodeUrl, index, allowSourceSwitch = autoSource)
        }
    }

    /**
     * 获取弹幕会话
     * @return 弹幕会话或null
     *
     * TODO: 优化
     */
    private fun fetchDanmakuSession() {
        _danmakuSession.value = null // 清除当前剧集的弹幕
        _danmakuStatus.value = null

        // 如果未启用了弹幕，直接返回
        if (!_danmakuEnabled.value) {
            "DANMAKU disabled".log("BehaviorTrace")
            return
        }

        viewModelScope.launch {
            _videoState.value.data?.let { video ->
                // 使用视频的标题和集数名获取对应的弹幕
                val result =
                    danmakuRepository.fetchDanmakuSession(video.title, video.episodeName)
                if (result is DanmakuResult.Success) {
                    _danmakuSession.value = result.session
                }
                _danmakuStatus.value = result
                "DANMAKU title=${video.title} ep=${video.episodeName} result=${result::class.simpleName}".log("BehaviorTrace")
            }
        }
    }

    /**
     * 设置弹幕是否启用
     * @param enabled 弹幕启用状态
     */
    fun setEnabledDanmaku(enabled: Boolean) {
        _danmakuEnabled.value = enabled
        preferences.edit { putBoolean(KEY_DANMAKU_ENABLED, enabled) }
        viewModelScope.launch {
            // 如果启用了弹幕且当前弹幕会话为空，则获取弹幕会话
            if (enabled && _danmakuSession.value == null) {
                fetchDanmakuSession()
            }
        }
    }

    /**
     * 获取当前视频或切换视频集数
     * @param url 当前集数的URL
     * @param episodeName 当前集数的名称
     * @param index 当前集数的索引
     * @param videoPosition 当前视频的播放位置
     */
    fun getVideo(url: String, episodeName: String, index: Int, videoPosition: Long) {
        if (isLocalVideo) {
            // 如果是本地视频，更新状态
            _videoState.value.data?.let { video ->
                _videoState.value = Resource.Success(
                    video.copy(url = url, episodeName = episodeName, currentEpisodeIndex = index)
                )
            }
            fetchDanmakuSession()
        } else {
            // 如果是远程视频，保存播放进度并重新获取远程视频
            currentEpisodeUrl = url
            currentEpisodeIndex = index
            saveVideoPosition(videoPosition)
            getVideoFromRemote(url, index)
        }
    }

    /**
     * 播放器播放结束时触发，启动 3 秒延迟的自动连播
     */
    fun startAutoContinuePlay(currPlayPosition: Long) {
        cancelAutoContinuePlay()
        autoContinuePlayJob = viewModelScope.launch {
            delay(3000)
            playNextEpisode(currPlayPosition)
        }
    }

    fun cancelAutoContinuePlay() {
        autoContinuePlayJob?.cancel()
        autoContinuePlayJob = null
    }

    /**
     * 切换到下一集
     * @param currPlayPosition 当前视频的播放位置, 单位：毫秒
     */
    fun playNextEpisode(currPlayPosition: Long) {
        viewModelScope.launch {
            _videoState.value.data?.let { video ->
                val nextEpisodeIndex = video.currentEpisodeIndex + 1
                if (nextEpisodeIndex < video.episodes.size) {
                    // 获取下一集视频
                    getVideo(
                        video.episodes[nextEpisodeIndex].url,
                        video.episodes[nextEpisodeIndex].name,
                        nextEpisodeIndex,
                        currPlayPosition
                    )
                }
            }
        }
    }

    /**
     * 保存当前视频的播放进度
     * @param currPlayPosition 当前视频的播放位置, 单位：毫秒
     */
    fun saveVideoPosition(currPlayPosition: Long) {
        // 观看时长少于5秒或本地视频的播放时长不保存
        if (currPlayPosition < 5_000 || isLocalVideo) return

        _videoState.value.data?.let { video ->
            viewModelScope.launch {
                val episode = Episode(
                    name = video.episodeName,
                    url = video.episodeUrl,
                    lastPlayPosition = currPlayPosition,
                    historyId = historyId
                )
                // 将当前视频进度保存到Room数据库中
                roomRepository.addEpisode(episode)
            }
        }
    }

    /**
     * 重新尝试加载远程视频
     */
    fun retry() {
        _videoState.value = Resource.Loading
        getVideoFromRemote(currentEpisodeUrl, currentEpisodeIndex)
    }
}
