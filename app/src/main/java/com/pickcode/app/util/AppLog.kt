package com.pickcode.app.util

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 码速达运行日志管理器（单例）
 *
 * 功能：
 * - 记录所有关键操作事件（触发入口、授权状态、截图结果、OCR 结果、错误信息等）
 * - 按日期分文件存储到应用私有目录
 * - 线程安全写入（单线程 executor）
 * - 自动清理 30 天前的旧日志
 * - 提供读取接口供 LogViewerActivity 展示
 *
 * 使用方式：
 *   AppLog.i("CaptureActivity", "用户点击了通知栏按钮")
 *   AppLog.e("CaptureActivity", "截图失败", exception)
 */
object AppLog {

    private const val TAG = "AppLog"
    private const val LOG_DIR = "app_logs"
    private const val MAX_LOG_DAYS = 30
    private const val MAX_ENTRIES_PER_DAY = 2000

    /** 日志级别 */
    enum class Level(val label: String, val prefix: String) {
        INFO    ("INFO" , "ℹ️"),
        WARN    ("WARN" , "⚠️"),
        ERROR   ("ERROR", "❌"),
        DEBUG   ("DEBUG", "🛈")
    }

    /** 单条日志数据 */
    data class Entry(
        val timestamp: Long,
        val level: Level,
        val source: String,
        val message: String,
        val detail: String? = null,
        val triggerFrom: String? = null
    )

    private val executor = Executors.newSingleThreadExecutor()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val fileDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    @Volatile
    private var logDir: File? = null

    /** 初始化（在 Application.onCreate 或首次使用前调用一次） */
    fun init(context: Context) {
        if (logDir == null) {
            logDir = File(context.filesDir, LOG_DIR).apply { mkdirs() }
            cleanOldLogs()
        }
    }

    // ── 便捷写法 ──────────────────────────────────

    fun i(source: String, message: String, triggerFrom: String? = null) =
        log(Level.INFO, source, message, null, triggerFrom)

    fun w(source: String, message: String, triggerFrom: String? = null) =
        log(Level.WARN, source, message, null, triggerFrom)

    fun e(source: String, message: String, throwable: Throwable? = null, triggerFrom: String? = null) =
        log(Level.ERROR, source, message, throwable?.stackTraceToString(), triggerFrom)

    fun d(source: String, message: String, triggerFrom: String? = null) =
        log(Level.DEBUG, source, message, null, triggerFrom)

    /** 核心写入方法（异步 + 线程安全） */
    fun log(
        level: Level,
        source: String,
        message: String,
        detail: String? = null,
        triggerFrom: String? = null
    ) {
        val entry = Entry(
            timestamp = System.currentTimeMillis(),
            level = level,
            source = source,
            message = message,
            detail = detail,
            triggerFrom = triggerFrom
        )

        val logMsg = buildString {
            append("[${entry.source}] ")
            append(entry.message)
            entry.detail?.let { append("\n$it") }
        }
        when (level) {
            Level.INFO  -> android.util.Log.i(TAG, logMsg)
            Level.WARN  -> android.util.Log.w(TAG, logMsg)
            Level.ERROR -> android.util.Log.e(TAG, logMsg)
            Level.DEBUG -> android.util.Log.d(TAG, logMsg)
        }

        executor.submit { writeToFile(entry) }
    }

    // ── 文件读写 ──────────────────────────────────

    private fun writeToFile(entry: Entry) {
        val dir = logDir ?: return
        val fileName = "${fileDateFormat.format(Date(entry.timestamp))}.log"
        val file = File(dir, fileName)

        try {
            if (file.exists() && file.useLines { it.count() >= MAX_ENTRIES_PER_DAY }) return

            FileWriter(file, true).use { writer ->
                val line = formatEntry(entry)
                writer.append(line).append("\n")
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to write log file", e)
        }
    }

    private fun formatEntry(entry: Entry): String {
        return buildString {
            append(dateFormat.format(Date(entry.timestamp)))
            append(" | ${entry.level.label}")
            append(" | ${entry.source}")
            entry.triggerFrom?.let { append(" | from:$it") }
            append(" | ${entry.message}")
            entry.detail?.let { append("\n  $it") }
        }
    }

    /** 读取日志条目（按时间倒序，最新的在前） */
    fun readEntries(limit: Int = 1000, levelFilter: Level? = null): List<Entry> {
        val dir = logDir ?: return emptyList()
        val entries = mutableListOf<Entry>()
        val files = dir.listFiles()
            ?.filter { it.name.endsWith(".log") && it.isFile }
            ?.sortedByDescending { it.name }
            ?: emptyList()

        for (file in files) {
            try {
                file.forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    val entry = parseLine(line)
                    if (entry != null && (levelFilter == null || entry.level == levelFilter)) {
                        entries.add(entry)
                    }
                }
            } catch (_: Exception) {}
            if (entries.size >= limit) break
        }

        return entries
    }

    private fun parseLine(line: String): Entry? {
        try {
            // 格式: 2025-05-07 14:00:00.123 | INFO | Source | from:xxx | message
            val parts = line.split(" | ", limit = 5)
            if (parts.size < 4) return null

            val ts = dateFormat.parse(parts[0])?.time ?: return null
            val level = Level.values().find { it.label == parts[1].trim() } ?: Level.INFO
            val source = parts[2].trim()

            // parts[3] may be "from:xxx" or start of message
            var triggerFrom: String? = null
            var message: String

            val fromMatch = Regex("""from:(\w+)""").find(parts[3])
            if (fromMatch != null) {
                triggerFrom = fromMatch.groupValues[1]
                // message is the rest after "from:xxx |" or in parts[4]
                message = parts.getOrNull(4) ?: parts[3].replace(Regex("""from:\w+\s*\|?\s*"""), "").trim()
            } else {
                message = parts[3]
            }

            return Entry(timestamp = ts, level = level, source = source, message = message, triggerFrom = triggerFrom)
        } catch (_: Exception) {
            return null
        }
    }

    /** 获取日志文件总大小（字节） */
    fun getTotalLogSize(): Long {
        return logDir?.listFiles()
            ?.filter { it.name.endsWith(".log") }
            ?.sumOf { it.length() } ?: 0L
    }

    /** 获取日志条目总数 */
    fun getTotalCount(): Int {
        return logDir?.listFiles()
            ?.filter { it.name.endsWith(".log") }
            ?.sumOf { file -> file.useLines { it.count() } } ?: 0
    }

    /** 清空所有日志 */
    fun clearAllLogs() {
        logDir?.listFiles()
            ?.filter { it.name.endsWith(".log") }
            ?.forEach { it.delete() }
    }

    /** 导出全部日志为纯文本（用于分享） */
    fun exportAsText(): String {
        val entries = readEntries(limit = 2000)
        return buildString {
            appendLine("=== 码速达运行日志 ===")
            appendLine("导出时间: ${dateFormat.format(Date())}")
            appendLine("共 ${entries.size} 条记录")
            appendLine("=".repeat(50))
            entries.reversed().forEach { entry ->
                appendLine(formatEntry(entry))
            }
        }
    }

    // ── 清理旧日志 ──────────────────────────────────

    private fun cleanOldLogs() {
        val dir = logDir ?: return
        val cutoff = System.currentTimeMillis() - MAX_LOG_DAYS * 24L * 60 * 60 * 1000
        val cutoffStr = fileDateFormat.format(Date(cutoff))

        dir.listFiles()
            ?.filter { it.name.endsWith(".log") && it.name < cutoffStr }
            ?.forEach {
                it.delete()
                android.util.Log.d(TAG, "Cleaned old log: ${it.name}")
            }
    }
}
