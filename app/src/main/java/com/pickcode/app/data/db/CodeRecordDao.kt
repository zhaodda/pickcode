package com.pickcode.app.data.db

import androidx.room.*
import com.pickcode.app.data.model.CodeRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface CodeRecordDao {

    @Query("SELECT * FROM code_records ORDER BY timestamp DESC LIMIT 50")
    fun getAllRecords(): Flow<List<CodeRecord>>

    @Query("SELECT * FROM code_records ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestRecord(): CodeRecord?

    /** 获取最近 N 条记录（用于小组件列表展示） */
    @Query("SELECT * FROM code_records ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentRecords(limit: Int = 20): List<CodeRecord>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: CodeRecord): Long

    @Update
    suspend fun update(record: CodeRecord)

    @Delete
    suspend fun delete(record: CodeRecord)

    @Query("DELETE FROM code_records WHERE id NOT IN (SELECT id FROM code_records ORDER BY timestamp DESC LIMIT 50)")
    suspend fun trimToLimit()

    @Query("DELETE FROM code_records")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM code_records")
    suspend fun getCount(): Int
}
