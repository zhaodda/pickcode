package com.pickcode.app.data.repository

import android.content.Context
import com.pickcode.app.data.db.PickCodeDatabase
import com.pickcode.app.data.model.CodeRecord
import kotlinx.coroutines.flow.Flow

class CodeRepository(context: Context) {

    private val dao = PickCodeDatabase.getInstance(context).codeRecordDao()

    val allRecords: Flow<List<CodeRecord>> = dao.getAllRecords()

    suspend fun insert(record: CodeRecord): Long {
        val id = dao.insert(record)
        dao.trimToLimit()
        return id
    }

    suspend fun update(record: CodeRecord) = dao.update(record)

    suspend fun delete(record: CodeRecord) = dao.delete(record)

    suspend fun clearAll() = dao.clearAll()

    suspend fun getLatest() = dao.getLatestRecord()
}
