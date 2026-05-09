package com.pickcode.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pickcode.app.data.model.CodeRecord
import com.pickcode.app.data.repository.CodeRepository
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = CodeRepository(app)

    /** 未取件记录 */
    val notPickedUpRecords = repository.notPickedUpRecords

    /** 已取件记录 */
    val pickedUpRecords = repository.pickedUpRecords

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
