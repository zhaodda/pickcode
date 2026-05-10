package com.pickcode.app.ui.activity

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pickcode.app.R
import com.pickcode.app.databinding.ActivityLogViewerBinding
import com.pickcode.app.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 运行日志查看器
 *
 * 从主界面右上角菜单 → "运行日志" 进入
 * 展示 AppLog 记录的所有运行日志，帮助排查问题
 */
class LogViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogViewerBinding
    private lateinit var adapter: LogAdapter
    private var currentFilter: AppLog.Level? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.log_viewer_title)

        setupFilterChips()
        setupRecyclerView()
        loadLogs()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun setupFilterChips() {
        val chips = listOf(
            binding.chipAll to null,
            binding.chipError to AppLog.Level.ERROR,
            binding.chipWarn to AppLog.Level.WARN,
            binding.chipInfo to AppLog.Level.INFO,
            binding.chipDebug to AppLog.Level.DEBUG
        )

        chips.forEach { (chip, level) ->
            chip.setOnClickListener {
                chips.forEach { (c, _) -> c.isChecked = (c == chip) }
                currentFilter = level
                loadLogs()
            }
        }
        binding.chipAll.isChecked = true
    }

    private fun setupRecyclerView() {
        adapter = LogAdapter { entry -> copyToClipboard(entry) }
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@LogViewerActivity)
            adapter = this@LogViewerActivity.adapter
        }
    }

    private fun loadLogs() {
        lifecycleScope.launch(Dispatchers.IO) {
            val entries = AppLog.readEntries(limit = 1000, levelFilter = currentFilter)
            val totalCount = AppLog.getTotalCount()
            val totalSize = AppLog.getTotalLogSize()

            withContext(Dispatchers.Main) {
                adapter.submitList(entries)
                updateStats(entries.size, totalCount, totalSize)

                if (entries.isEmpty()) {
                    binding.emptyView.visibility = View.VISIBLE
                    binding.recyclerView.visibility = View.GONE
                } else {
                    binding.emptyView.visibility = View.GONE
                    binding.recyclerView.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun updateStats(filteredCount: Int, totalCount: Int, totalSizeBytes: Long) {
        val sizeStr = when {
            totalSizeBytes < 1024 -> "${totalSizeBytes}B"
            totalSizeBytes < 1024 * 1024 -> "${totalSizeBytes / 1024}KB"
            else -> String.format("%.1fMB", totalSizeBytes / (1024.0 * 1024.0))
        }

        binding.tvStats.text = if (currentFilter != null) {
            getString(R.string.log_stats_filtered_format, totalCount, filteredCount, sizeStr)
        } else {
            getString(R.string.log_stats_format, totalCount, sizeStr)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_log_viewer, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_share -> { shareLogs(); true }
            R.id.action_clear -> { confirmClearLogs(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun shareLogs() {
        val text = AppLog.exportAsText()
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.log_share_subject))
        }
        startActivity(Intent.createChooser(intent, getString(R.string.log_share_chooser)))
    }

    private fun confirmClearLogs() {
        AlertDialog.Builder(this)
            .setTitle(R.string.log_clear_title)
            .setMessage(R.string.log_clear_message)
            .setPositiveButton(R.string.log_clear_positive) { _, _ ->
                AppLog.clearAllLogs()
                adapter.submitList(emptyList())
                binding.emptyView.visibility = View.VISIBLE
                binding.recyclerView.visibility = View.GONE
                binding.tvStats.text = getString(R.string.log_stats_format, 0, "0B")
                Toast.makeText(this, R.string.log_cleared, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun copyToClipboard(entry: AppLog.Entry) {
        val text = buildString {
            append("[${entry.level.label}] ${entry.source}")
            entry.triggerFrom?.let { append(" | from:$it") }
            append("\n${entry.message}")
            entry.detail?.let { append("\n$it") }
        }
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.log_clipboard_label), text))
        Toast.makeText(this, R.string.log_copied, Toast.LENGTH_SHORT).show()
    }

    // ━━ Adapter ━━━━━━━━━━━━━━━━━━━━━━━━━━

    class LogAdapter(private val onItemClick: (AppLog.Entry) -> Unit) :
        ListAdapter<AppLog.Entry, LogAdapter.LogViewHolder>(DIFF) {

        private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        inner class LogViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val levelIndicator: View = itemView.findViewById(R.id.levelIndicator)
            private val tvLevel: TextView = itemView.findViewById(R.id.tvLogLevel)
            private val tvTime: TextView = itemView.findViewById(R.id.tvLogTime)
            private val tvSource: TextView = itemView.findViewById(R.id.tvLogSource)
            private val tvTrigger: TextView = itemView.findViewById(R.id.tvLogTrigger)
            private val tvMessage: TextView = itemView.findViewById(R.id.tvLogMessage)
            private val tvDetail: TextView = itemView.findViewById(R.id.tvLogDetail)

            fun bind(entry: AppLog.Entry) {
                tvLevel.text = entry.level.prefix
                tvLevel.setTextColor(when (entry.level) {
                    AppLog.Level.ERROR -> 0xFFE53935.toInt()
                    AppLog.Level.WARN  -> 0xFFF57F17.toInt()
                    AppLog.Level.INFO  -> 0xFF1976D2.toInt()
                    AppLog.Level.DEBUG -> 0xFF757575.toInt()
                })
                levelIndicator.setBackgroundColor(when (entry.level) {
                    AppLog.Level.ERROR -> 0xFFE53935.toInt()
                    AppLog.Level.WARN  -> 0xFFF57F17.toInt()
                    AppLog.Level.INFO  -> 0xFF1976D2.toInt()
                    AppLog.Level.DEBUG -> 0xFF757575.toInt()
                })

                tvTime.text = dateFormat.format(Date(entry.timestamp))
                tvSource.text = entry.source

                if (!entry.triggerFrom.isNullOrBlank()) {
                    tvTrigger.visibility = View.VISIBLE
                    tvTrigger.text = itemView.context.getString(
                        R.string.log_trigger_format,
                        formatTriggerLabel(entry.triggerFrom)
                    )
                    tvTrigger.setBackgroundResource(
                        when (entry.triggerFrom) {
                            "notification" -> R.drawable.bg_chip_notification
                            "tile"         -> R.drawable.bg_chip_tile
                            "fab"          -> R.drawable.bg_chip_fab
                            "manual"       -> R.drawable.bg_chip_manual
                            else           -> R.drawable.bg_chip_default
                        }
                    )
                } else {
                    tvTrigger.visibility = View.GONE
                }

                tvMessage.text = entry.message

                val detail = entry.detail
                if (!detail.isNullOrBlank()) {
                    tvDetail.visibility = View.VISIBLE
                    val lines = detail.lines()
                    tvDetail.text = if (lines.size > 3) {
                        lines.take(3).joinToString("\n") + "\n..."
                    } else {
                        detail
                    }
                } else {
                    tvDetail.visibility = View.GONE
                }

                itemView.setOnClickListener { onItemClick(entry) }
            }

            private fun formatTriggerLabel(trigger: String): String = when (trigger) {
                "notification" -> "\uD83D\uDCEC 通知栏"
                "tile"         -> "\u2699\uFE0F 磁贴"
                "fab"          -> "\uD83D\uDED2 主按钮"
                "manual"       -> "\u270F\uFE0F 手动输入"
                else           -> trigger
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_log_entry, parent, false)
            return LogViewHolder(view)
        }

        override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        companion object {
            private val DIFF = object : DiffUtil.ItemCallback<AppLog.Entry>() {
                override fun areItemsTheSame(oldItem: AppLog.Entry, newItem: AppLog.Entry): Boolean =
                    oldItem.timestamp == newItem.timestamp &&
                        oldItem.level == newItem.level &&
                        oldItem.source == newItem.source

                override fun areContentsTheSame(oldItem: AppLog.Entry, newItem: AppLog.Entry): Boolean =
                    oldItem == newItem
            }
        }
    }
}
