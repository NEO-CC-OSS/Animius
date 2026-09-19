package com.lanlinju.animius.data.repository

import com.lanlinju.animius.data.remote.dandanplay.DanmakuProvider
import com.lanlinju.animius.domain.model.DanmakuResult
import com.lanlinju.animius.domain.repository.DanmakuRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provider 内部已把网络/鉴权/匹配失败全部分类为 [DanmakuResult]，
 * 这里直接转发，不再吞异常返回 null。
 */
@Singleton
class DanmakuRepositoryImpl @Inject constructor(
    private val danmakuProvider: DanmakuProvider
) : DanmakuRepository {
    override suspend fun fetchDanmakuSession(
        subjectName: String,
        episodeName: String?
    ): DanmakuResult {
        return danmakuProvider.fetch(subjectName, episodeName)
    }
}
