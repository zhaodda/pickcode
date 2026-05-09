package com.pickcode.app.ui.fragment

import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.pickcode.app.R
import com.pickcode.app.service.PickCodeAccessibilityService
import com.pickcode.app.ui.activity.AboutActivity
import com.pickcode.app.ui.activity.LogViewerActivity
import com.pickcode.app.util.AppLog

class SettingsFragment :
    androidx.fragment.app.Fragment(R.layout.fragment_settings),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var prefs: SharedPreferences

    // 开关项
    private lateinit var switchAutoStart: androidx.appcompat.widget.SwitchCompat
    private lateinit var switchAutoCopy: androidx.appcompat.widget.SwitchCompat
    private lateinit var switchVibrate: androidx.appcompat.widget.SwitchCompat

    // 点击项
    private lateinit var itemAccessibility: View
    private lateinit var itemAbout: View
    private lateinit var itemVersion: View
    private lateinit var itemLogRetention: View
    private lateinit var itemLogViewer: View

    // 日志保留天数选项（单位：天）
    private val retentionOptions = listOf(3, 7, 14, 30, -1) // -1 表示不自动删除
    private val retentionLabels by lazy {
        retentionOptions.map {
            when (it) {
                -1  -> "不自动删除"
                else -> "$it 天"
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())

        setupHeader(view)
        setupSwitchReferences(view)
        setupClickReferences(view)
        setupListeners()
        updateAccessibilitySummary()
        updateLogRetentionSummary()
    }

    private fun setupHeader(view: View) {
        val pm = requireContext().packageManager
        val info = pm.getPackageInfo(requireContext().packageName, 0)
        view.findViewById<TextView>(R.id.header_version).text = "PickCode v${info.versionName}"
    }

    private fun setupSwitchReferences(root: View) {
        val itemAutoStart = root.findViewById<View>(R.id.item_auto_start)
        itemAutoStart.findViewById<ImageView>(R.id.icon).setImageResource(R.drawable.ic_settings_boot)
        itemAutoStart.findViewById<TextView>(R.id.title).setText(R.string.pref_auto_start_title)
        itemAutoStart.findViewById<TextView>(R.id.summary).setText(R.string.pref_auto_start_summary)
        switchAutoStart = itemAutoStart.findViewById(R.id.switch_btn)

        val itemAutoCopy = root.findViewById<View>(R.id.item_auto_copy)
        itemAutoCopy.findViewById<ImageView>(R.id.icon).setImageResource(R.drawable.ic_settings_copy)
        itemAutoCopy.findViewById<TextView>(R.id.title).setText(R.string.pref_auto_copy_title)
        itemAutoCopy.findViewById<TextView>(R.id.summary).setText(R.string.pref_auto_copy_summary)
        switchAutoCopy = itemAutoCopy.findViewById(R.id.switch_btn)

        val itemVibrate = root.findViewById<View>(R.id.item_vibrate)
        itemVibrate.findViewById<ImageView>(R.id.icon).setImageResource(R.drawable.ic_settings_vibrate)
        itemVibrate.findViewById<TextView>(R.id.title).setText(R.string.pref_vibrate_title)
        itemVibrate.findViewById<TextView>(R.id.summary).setText(R.string.pref_vibrate_summary)
        switchVibrate = itemVibrate.findViewById(R.id.switch_btn)
    }

    private fun setupClickReferences(root: View) {
        // 无障碍服务
        itemAccessibility = root.findViewById(R.id.item_accessibility)
        itemAccessibility.findViewById<ImageView>(R.id.icon).setImageResource(R.drawable.ic_settings_accessibility)
        itemAccessibility.findViewById<TextView>(R.id.title).setText(R.string.pref_a11y_title)

        // 关于
        itemAbout = root.findViewById(R.id.item_about)
        itemAbout.findViewById<ImageView>(R.id.icon).setImageResource(R.drawable.ic_settings_about)
        itemAbout.findViewById<TextView>(R.id.title).setText(R.string.pref_about_title)
        itemAbout.findViewById<TextView>(R.id.summary).text = "版本信息、隐私政策等"

        // 版本号（只读，隐藏图标和右箭头）
        itemVersion = root.findViewById(R.id.item_version)
        itemVersion.findViewById<ImageView>(R.id.icon).visibility = View.GONE
        itemVersion.findViewById<ImageView>(R.id.chevron).visibility = View.GONE
        itemVersion.findViewById<TextView>(R.id.title).setText(R.string.pref_version_title)
        val pm = requireContext().packageManager
        val ver = pm.getPackageInfo(requireContext().packageName, 0).versionName
        itemVersion.findViewById<TextView>(R.id.summary).text = "v$ver"

        // 自动删除日志
        itemLogRetention = root.findViewById(R.id.item_log_retention)
        itemLogRetention.findViewById<ImageView>(R.id.icon).setImageResource(R.drawable.ic_settings_log)
        itemLogRetention.findViewById<TextView>(R.id.title).setText(R.string.pref_log_retention_title)

        // 运行日志
        itemLogViewer = root.findViewById(R.id.item_log_viewer)
        itemLogViewer.findViewById<ImageView>(R.id.icon).setImageResource(R.drawable.ic_settings_log)
        itemLogViewer.findViewById<TextView>(R.id.title).text = getString(R.string.action_logs)
        itemLogViewer.findViewById<TextView>(R.id.summary).text = "查看历史运行记录"
    }
    private fun setupListeners() {
        switchAutoStart.isChecked = prefs.getBoolean("auto_start_on_boot", false)
        switchAutoStart.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("auto_start_on_boot", isChecked).apply()
            AppLog.i("Settings", "开机自启: $isChecked")
        }

        switchAutoCopy.isChecked = prefs.getBoolean("auto_copy", true)
        switchAutoCopy.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("auto_copy", isChecked).apply()
            AppLog.i("Settings", "自动复制: $isChecked")
        }

        switchVibrate.isChecked = prefs.getBoolean("vibrate_on_success", true)
        switchVibrate.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("vibrate_on_success", isChecked).apply()
            AppLog.i("Settings", "震动提示: $isChecked")
        }

        itemAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        itemAbout.setOnClickListener {
            startActivity(Intent(requireContext(), AboutActivity::class.java))
        }

        itemLogRetention.setOnClickListener {
            showRetentionDialog()
        }

        itemLogViewer.setOnClickListener {
            startActivity(Intent(requireContext(), LogViewerActivity::class.java))
        }
    }

    private fun updateAccessibilitySummary() {
        val isAvailable = PickCodeAccessibilityService.isAvailable
        val summary = if (isAvailable)
            getString(R.string.pref_a11y_summary_on)
        else
            getString(R.string.pref_a11y_summary_off)
        itemAccessibility.findViewById<TextView>(R.id.summary).text = summary
    }

    private fun updateLogRetentionSummary() {
        val days = AppLog.getRetentionDays(requireContext())
        val summary = if (days < 0)
            "不自动删除"
        else
            getString(R.string.pref_log_retention_summary, days)
        itemLogRetention.findViewById<TextView>(R.id.summary).text = summary
    }

    private fun showRetentionDialog() {
        val currentDays = AppLog.getRetentionDays(requireContext())
        val checkedItem = retentionOptions.indexOf(currentDays).coerceAtLeast(0)

        AlertDialog.Builder(requireContext())
            .setTitle("自动删除日志")
            .setSingleChoiceItems(retentionLabels.toTypedArray(), checkedItem) { dialog, which ->
                val days = retentionOptions[which]
                AppLog.setRetentionDays(days, requireContext())
                updateLogRetentionSummary()
                dialog.dismiss()
                AppLog.i("Settings", "日志保留天数设置为: ${if (days < 0) "不自动删除" else "${days}天"}")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        prefs.registerOnSharedPreferenceChangeListener(this)
        updateAccessibilitySummary()
    }

    override fun onPause() {
        super.onPause()
        prefs.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        // 偏好变更时自动刷新
    }

    override fun onDestroyView() {
        super.onDestroyView()
    }
}
