package com.lanlinju.animius.presentation.screen.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lanlinju.animius.data.remote.parse.util.CaptchaCookieManager
import com.lanlinju.animius.data.repository.AggregateGroup
import com.lanlinju.animius.data.repository.AggregateSearch
import com.lanlinju.animius.util.SourceMode
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 搜索页 ViewModel（tv.9 切片 3）。
 *
 * 搜索是兜底：一次搜所有可用源，按源分组、先到先显示；**只显示源站结果**，
 * 不再叠加元数据搜索结果（用户 2026-10-22 拍板）。
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val aggregateSearch: AggregateSearch,
) : ViewModel() {

    /** 按源分组的结果；某源 `error == null && results 为空` 且 [isSearching] = 搜索中 */
    private val _groups: MutableStateFlow<List<AggregateGroup>> = MutableStateFlow(emptyList())
    val groups: StateFlow<List<AggregateGroup>>
        get() = _groups

    private val _isSearching: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean>
        get() = _isSearching

    private val _query: MutableStateFlow<String> = MutableStateFlow(value = "")
    val query: StateFlow<String>
        get() = _query

    private val _needCaptchaUrl: MutableStateFlow<String?> = MutableStateFlow(value = null)
    val needCaptchaUrl: StateFlow<String?>
        get() = _needCaptchaUrl

    // 只在第一次进入时请求焦点
    var hasFocusRequest = false

    // 从周表/详情带进来的初始 query 是否已消费；放在 ViewModel 里跨组合存活
    var initialQueryConsumed = false

    private var searchJob: Job? = null

    fun onQuery(query: String) {
        _query.value = query
    }

    fun clearSearchQuery() {
        searchJob?.cancel()
        _isSearching.value = false
        _query.value = ""
        _groups.value = emptyList()
    }

    fun clearNeedCaptcha() {
        _needCaptchaUrl.value = null
        CaptchaCookieManager.captchaUrl = ""
    }

    /** 用户点搜索 / 进入页面自动搜索：一次打全部可选源 */
    fun searchAll(query: String) {
        if (query.isBlank()) return
        searchJob?.cancel()
        _query.value = query
        _isSearching.value = true

        val sources = SourceMode.entries.filter { it.selectable }
        // 占位：每个源先显示「搜索中」，结果到达后原地替换（先到先显示）
        _groups.value = sources.map { AggregateGroup(it) }

        searchJob = viewModelScope.launch {
            val finalGroups = aggregateSearch.search(query, sources) { group ->
                _groups.update { current ->
                    current.map { if (it.mode == group.mode) group else it }
                }
            }
            _groups.value = finalGroups
            _isSearching.value = false
            checkNeedCaptcha()
        }
    }

    /** 检查是否需要验证码（源实现触发时写入 CaptchaCookieManager） */
    private fun checkNeedCaptcha() {
        val url = CaptchaCookieManager.captchaUrl
        if (url.isNotEmpty()) {
            _needCaptchaUrl.value = url
            CaptchaCookieManager.captchaUrl = ""
        }
    }
}
