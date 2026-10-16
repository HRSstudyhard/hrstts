package com.hrstts.jiandu.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hrstts.jiandu.data.db.AppDatabase
import com.hrstts.jiandu.data.db.HistoryEntry
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 「我的」抽屉的历史记录状态持有者。
 *
 * 阶段 P0：仅打通 Room 读取链路（列表为空）。
 * 阶段 P1：导入成功后写入记录。
 * 阶段 P4：支持点击切换与移除记录。
 */
class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = AppDatabase.get(application).historyDao()

    val history: StateFlow<List<HistoryEntry>> = dao.observeAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    fun remove(entry: HistoryEntry) {
        viewModelScope.launch { dao.deleteByUri(entry.uri) }
    }
}
