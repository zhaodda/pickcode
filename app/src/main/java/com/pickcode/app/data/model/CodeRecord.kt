package com.pickcode.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 识别结果实体
 */
@Entity(tableName = "code_records")
data class CodeRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val code: String,              // 提取到的验证码
    val codeType: CodeType,        // 类型：快递/餐饮/停车/其他
    val rawText: String = "",      // OCR 原始文本片段
    val address: String = "",      // 驿站地址（快递类型时填充）
    val timestamp: Long = System.currentTimeMillis(),
    val isFavorite: Boolean = false
)

enum class CodeType(val label: String, val emoji: String) {
    EXPRESS("快递取件", "📦"),
    FOOD("取餐码", "🧋"),
    PARKING("停车码", "🚗"),
    OTHER("其他", "🔢")
}
