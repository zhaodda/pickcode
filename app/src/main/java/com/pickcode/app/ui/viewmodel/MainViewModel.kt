package com.pickcode.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pickcode.app.data.model.CodeRecord
import com.pickcode.app.data.repository.CodeRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = CodeRepository(app)

    val records: StateFlow<List<CodeRecord>> = repository.allRecords
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** 未取件记录 */
    val notPickedUpRecords: StateFlow<List<CodeRecord>> = repository.notPickedUpRecords
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** 已取件记录 */
    val pickedUpRecords: StateFlow<List<CodeRecord>> = repository.pickedUpRecords
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** 当前选中的 Tab（0=未取件, 1=已取件），由 UI 通过 setCurrentTab 切换 */
    private val _currentTab = MutableStateFlow(0)
    val currentTab: StateFlow<Int> = _currentTab

    /** 根据 currentTab 自动切换的列表，UI 只需观察这一个 Flow */
    val currentRecords: StateFlow<List<CodeRecord>> =
        combine(_currentTab, notPickedUpRecords, pickedUpRecords) { tab, notPicked, picked ->
            if (tab == 0) notPicked else picked
        }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun setCurrentTab(tab: Int) {
        _currentTab.value = tab
    }

    fun delete(record: CodeRecord) = viewModelScope.launch {
        repository.delete(record)
    }

    fun toggleFavorite(record: CodeRecord) = viewModelScope.launch {
        repository.update(record.copy(isFavorite = !record.isFavorite))
    }

    /** 切换取件状态 */
    fun togglePickedUp(record: CodeRecord) = viewModelScope.launch {
        repository.update(record.copy(isPickedUp = !record.isPickedUp))
    }

    fun clearAll() = viewModelScope.launch {
        repository.clearAll()
    }
}
