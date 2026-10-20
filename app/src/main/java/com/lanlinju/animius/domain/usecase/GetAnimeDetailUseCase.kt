package com.lanlinju.animius.domain.usecase

import com.lanlinju.animius.domain.model.AnimeDetail
import com.lanlinju.animius.domain.model.Episode
import com.lanlinju.animius.domain.repository.AnimeRepository
import com.lanlinju.animius.domain.repository.RoomRepository
import com.lanlinju.animius.util.Resource
import com.lanlinju.animius.util.SourceMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject

class GetAnimeDetailUseCase @Inject constructor(
    private val animeRepository: AnimeRepository,
    private val roomRepository: RoomRepository
) {
    suspend operator fun invoke(detailUrl: String, mode: SourceMode): Flow<Resource<AnimeDetail?>> {
        return flow {
            when (val resource = animeRepository.getAnimeDetail(detailUrl, mode)) {
                is Resource.Error -> emit(Resource.Error(error = resource.error))
                is Resource.Loading -> emit(Resource.Loading)
                is Resource.Success -> {
                    try {
                        emitMergedWithHistory(detailUrl, resource.data!!)
                    } catch (e: Exception) {
                        emit(Resource.Error(error = e))
                    }
                }
            }
        }
    }

    /**
     * 自动选源时解析层已经请求过详情页，直接复用，避免同一页面请求两次。
     */
    fun fromPreloaded(detailUrl: String, detail: AnimeDetail): Flow<Resource<AnimeDetail?>> {
        return flow {
            try {
                emitMergedWithHistory(detailUrl, detail)
            } catch (e: Exception) {
                emit(Resource.Error(error = e))
            }
        }
    }

    /** 把本地历史进度合并进远端详情（本地按剧集 URL 对应） */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<Resource<AnimeDetail?>>.emitMergedWithHistory(
        detailUrl: String,
        remote: AnimeDetail,
    ) {
        roomRepository.checkHistory(detailUrl).collect { isStoredHistory ->
            if (!isStoredHistory) {
                emit(Resource.Success(data = remote))
            } else {
                roomRepository.getEpisodes(detailUrl).collect { localEpisodes ->
                    if (localEpisodes.isEmpty()) {
                        emit(Resource.Success(data = remote))
                    } else {
                        val lastPlayedEpisode = localEpisodes.first()
                        val remoteEpisodes = remote.episodes

                        val lastPosition =
                            remoteEpisodes.indexOfFirst { it.url == lastPlayedEpisode.url }
                        val episodeList = remoteEpisodes.map { episode ->
                            val index =
                                localEpisodes.indexOfFirst { e -> e.url == episode.url }
                            Episode(
                                name = episode.name,
                                url = episode.url,
                                lastPlayPosition = if (index != -1) localEpisodes[index].lastPlayPosition else 0L,
                                isPlayed = index != -1
                            )
                        }

                        emit(
                            Resource.Success(
                                data = remote.copy(
                                    lastPosition = lastPosition.coerceAtLeast(0),
                                    episodes = episodeList
                                )
                            )
                        )
                    }
                }
            }
        }
    }
}