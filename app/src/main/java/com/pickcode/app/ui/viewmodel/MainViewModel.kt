package com.pickcode.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pickcode.app.data.model.CodeRecord
import com.pickcode.app.data.repository.CodeRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = CodeRepository(app)

    val records: StateFlow<List<CodeRecord>> = repository.allRecords
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun delete(record: CodeRecord) = viewModelScope.launch {
        repository.delete(record)
    }

    fun toggleFavorite(record: CodeRecord) = viewModelScope.launch {
        repository.update(record.copy(isFavorite = !record.isFavorite))
    }

    fun clearAll() = viewModelScope.launch {
        repository.clearAll()
    }
}
