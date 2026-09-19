package com.lanlinju.animius.domain.repository

import com.lanlinju.animius.domain.model.DanmakuResult

interface DanmakuRepository {
    suspend fun fetchDanmakuSession(subjectName: String, episodeName: String?): DanmakuResult
}
