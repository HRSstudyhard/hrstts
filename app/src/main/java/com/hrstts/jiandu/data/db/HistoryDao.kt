package com.hrstts.jiandu.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {

    @Query("SELECT * FROM history ORDER BY last_opened_at DESC")
    fun observeAll(): Flow<List<HistoryEntry>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: HistoryEntry)

    @Query("UPDATE history SET last_opened_at = :timestamp, chapter_index = :chapter, paragraph_index = :paragraph WHERE uri = :uri")
    suspend fun updateProgress(uri: String, timestamp: Long, chapter: Int, paragraph: Int)

    @Query("DELETE FROM history WHERE uri = :uri")
    suspend fun deleteByUri(uri: String)

    @Query("SELECT * FROM history WHERE uri = :uri LIMIT 1")
    suspend fun findByUri(uri: String): HistoryEntry?

    /** 同一文件可能被系统授予不同的 document URI，按「文件名 + 字数」识别重复记录 */
    @Query("SELECT * FROM history WHERE name = :name AND char_count = :charCount LIMIT 1")
    suspend fun findByNameAndCharCount(name: String, charCount: Int): HistoryEntry?
}
