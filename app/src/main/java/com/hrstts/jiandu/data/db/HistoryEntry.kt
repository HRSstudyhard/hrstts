package com.hrstts.jiandu.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** 「我的」抽屉中的一条历史记录（同一文本以 uri 唯一） */
@Entity(tableName = "history")
data class HistoryEntry(
    @PrimaryKey
    @ColumnInfo(name = "uri")
    val uri: String,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "last_opened_at")
    val lastOpenedAt: Long,
    @ColumnInfo(name = "chapter_index")
    val chapterIndex: Int = 0,
    @ColumnInfo(name = "paragraph_index")
    val paragraphIndex: Int = 0,
    @ColumnInfo(name = "char_count")
    val charCount: Int = 0,
)
