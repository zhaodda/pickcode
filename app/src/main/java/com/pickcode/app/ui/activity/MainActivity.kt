package com.pickcode.app.ui.activity

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.TileService
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.pickcode.app.R
import com.pickcode.app.data.model.CodeType
import com.pickcode.app.databinding.ActivityMainBinding
import com.pickcode.app.ocr.CodeExtractor
import com.pickcode.app.service.PickCodeService
import com.pickcode.app.ui.adapter.MainPagerAdapter
import com.pickcode.app.ui.viewmodel.MainViewModel
import com.pickcode.app.util.AppLog
import kotlinx.coroutines.launch
import android.util.Log

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private val extractor = CodeExtractor()

    /** 将 dp 值转换为 px */
    private fun Float.toPx(): Int = (this * resources.displayMetrics.density).toInt()
    private fun Int.toPx(): Int = (this * resources.displayMetrics.density).toInt()

    // Android 13+ 通知权限申请
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startPickCodeService()
                showTileGuideIfNeeded()
            } else {
                Snackbar.make(
                    binding.root,
                    "需要通知权限才能显示取件码提醒（超级岛/通知横幅）",
                    Snackbar.LENGTH_LONG
                ).setAction("去设置") {
                    startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
                    })
                }.show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        // 初始化运行日志
        AppLog.init(this)

        setupViewPager()
        setupManualInputButton()

        // 观察列表数量，更新 Tab 文字
        observeRecordCounts()

        // 请求 Tile 进入 listening 状态
        requestTileListeningState()

        // 申请权限 → 启动服务
        checkAndRequestPermissionsAndStartService()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  ViewPager2 + TabLayout
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private fun setupViewPager() {
        val pager = binding.viewPager
        val tabLayout = binding.tabLayout

        pager.adapter = MainPagerAdapter(this)

        // TabLayout + ViewPager2 联动（左右滑动切换 Tab）
        TabLayoutMediator(tabLayout, pager) { tab, position ->
            // 文字由 observeRecordCounts() 动态更新，这里先设初始值
            tab.text = if (position == 0) "未取件" else "已取件"
        }.attach()

        // Tab 点击时切换到对应页（ViewPager2 会自动处理，这里可选）
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                tab?.let { pager.currentItem = it.position }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        // ViewPager2 页面切换时同步 Tab 选中状态
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                tabLayout.getTabAt(position)?.select()
            }
        })
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  观察列表数量，更新 Tab 文字
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private fun observeRecordCounts() {
        lifecycleScope.launch {
            viewModel.notPickedUpRecords.collect { list ->
                updateTabText(0, "未取件", list.size)
            }
        }
        lifecycleScope.launch {
            viewModel.pickedUpRecords.collect { list ->
                updateTabText(1, "已取件", list.size)
            }
        }
    }

    /** 更新指定 Tab 的文字（含数量） */
    private fun updateTabText(index: Int, label: String, count: Int) {
        val tab = binding.tabLayout.getTabAt(index) ?: return
        tab.text = "$label ($count)"
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  手动导入功能
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 显示手动输入对话框
     *
     * 提供两种模式：
     * 1. 粘贴短信模式 — 粘贴整条取件码短信，自动解析提取
     * 2. 手动填写模式 — 手动输入验证码 + 选择类型
     */
    private fun setupManualInputButton() {
        binding.btnManualInput.setOnClickListener {
            showManualInputDialog()
        }
    }

    private fun showManualInputDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_manual_input, null)

        val rgInputMode = dialogView.findViewById<RadioGroup>(R.id.rg_input_mode)
        val etInput     = dialogView.findViewById<EditText>(R.id.et_manual_input)
        val layoutType  = dialogView.findViewById<LinearLayout>(R.id.layout_type_selector)
        val rgCodeType = dialogView.findViewById<RadioGroup>(R.id.rg_code_type)

        // 默认：粘贴短信模式
        var isPasteMode = true

        rgInputMode.setOnCheckedChangeListener { _, checkedId ->
            isPasteMode = (checkedId == R.id.rb_paste_mode)
            etInput.hint = if (isPasteMode)
                "在此粘贴整条取件码短信..."
            else
                "输入取件码（如：6-8-3-2）"
            layoutType.visibility = if (isPasteMode) View.GONE else View.VISIBLE
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("\uD83C\uDFE6 \u624B\u52A8\u8F93\u5165\u53D6\u4EF6\u7801")
            .setView(dialogView)
            .setPositiveButton("\u786E\u8BA4\u63D0\u4EA4") { _, _ ->
                val inputText = etInput.text.toString().trim()
                if (inputText.isBlank()) {
                    Snackbar.make(binding.root, "\u5185\u5BB9\u4E0D\u80FD\u4E3A\u7A7A", Snackbar.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val selectedOrdinal = when (rgCodeType.checkedRadioButtonId) {
                    R.id.rb_type_meal    -> 1
                    R.id.rb_type_parking  -> 2
                    R.id.rb_type_other   -> 3
                    else                    -> 0
                }
                submitManualInput(inputText, isPasteMode, selectedOrdinal)
            }
            .setNegativeButton("\u53D6\u6D88", null)
            .show()
    }

    /**
     * 提交手动输入的内容
     *
     * @param inputText 用户输入的文本
     * @param isPasteMode true=粘贴短信模式（自动解析），false=手动填写模式
     * @param selectedTypeOrdinal 手动模式下选中的类型 ordinal
     */
    private fun submitManualInput(inputText: String, isPasteMode: Boolean, selectedTypeOrdinal: Int) {
        if (isPasteMode) {
            // 粘贴短信模式：用 CodeExtractor 自动解析
            val record = extractor.parseCode(inputText)
            if (record != null) {
                PickCodeService.submitManualCode(
                    this, record.code, record.codeType.ordinal, record.rawText
                )
                Snackbar.make(
                    binding.root,
                    "✅ 识别成功！${record.codeType.emoji} ${record.code}",
                    Snackbar.LENGTH_LONG
                ).show()
            } else {
                // 无法自动解析 → 提示用户切换到手动填写
                MaterialAlertDialogBuilder(this)
                    .setMessage("未能自动识别出取件码。\n\n可能原因：\n• 短信格式不标准\n• 不包含\"取件码\"等关键词\n\n是否切换到手动填写模式？")
                    .setPositiveButton("手动填写") { _, _ ->
                        showManualInputDialog()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        } else {
            // 手动填写模式：直接使用用户输入作为 code
            PickCodeService.submitManualCode(
                this, inputText, selectedTypeOrdinal, "(手动输入)"
            )
            val typeLabel = CodeType.values().getOrElse(selectedTypeOrdinal) { CodeType.OTHER }
            Snackbar.make(
                binding.root,
                "✅ 已添加！${typeLabel.emoji} $inputText",
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  权限 & 服务 & 引导
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private fun checkAndRequestPermissionsAndStartService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }

        startPickCodeService()
        showTileGuideIfNeeded()
    }

    private fun requestTileListeningState() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                TileService.requestListeningState(
                    this,
                    ComponentName(this, com.pickcode.app.tile.PickCodeTileService::class.java)
                )
            } catch (_: Exception) { /* 忽略 */ }
        }
    }

    private fun showTileGuideIfNeeded() {
        val prefs = getSharedPreferences("pickcode_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("tile_guide_shown", false)) return

        binding.root.postDelayed({
            AlertDialog.Builder(this)
                .setTitle("\uD83D\uDE80 添加快捷开关")
                .setMessage(
                    "添加「码住」快捷开关后，可在任意界面一键识别验证码！\n\n" +
                    "操作步骤：\n" +
                    "1. 从屏幕顶部向下滑出「通知栏」\n" +
                    "2. 点击右下角「编辑」按钮（⚙️ 铅笔图标）\n" +
                    "3. 找到「码住」图标\n" +
                    "4. 按住拖动到上方快捷区域\n\n" +
                    "\uD83D\uDCA1 添加成功后，下拉通知栏点击「码住」即可立即识别！" +
                    "\n\n\uD83D\uDCDD 也支持在主页手动输入取件码哦~"
                )
                .setPositiveButton("我知道了") { _, _ ->
                    prefs.edit().putBoolean("tile_guide_shown", true).apply()
                    showA11yGuideIfNeeded()
                }
                .setCancelable(false)
                .show()
        }, 500)
    }

    /**
     * 检查并显示无障碍服务引导
     * 无障碍服务用于在用户主动触发时读取当前屏幕节点文字。
     */
    private fun showA11yGuideIfNeeded() {
        val prefs = getSharedPreferences("pickcode_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("a11y_guide_shown", false)) {
            return
        }

        // 延迟弹出，等 Tile 引导消失后再弹
        binding.root.postDelayed({
            AlertDialog.Builder(this)
                .setTitle("\uD83C\uDF1F 开启无障碍服务")
                .setMessage(
                    "码住需要「无障碍服务」权限，在你主动点击识别时读取当前屏幕可见文字并在本地解析验证码。\n\n" +
                    "说明：\n" +
                    "• 不截图、不录屏\n" +
                    "• 不联网上传\n" +
                    "• 不在后台持续读取屏幕内容\n\n" +
                    "\u26A1 开启步骤：\n" +
                    "1. 点击下方「去设置」\n" +
                    "2. 找到「码住」\n" +
                    "3. 开启开关"
                )
                .setPositiveButton("去设置") { _, _ ->
                    prefs.edit().putBoolean("a11y_guide_shown", true).apply()
                    try {
                        startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    } catch (_: Exception) {}
                }
                .setNegativeButton("稍后") { _, _ ->
                    prefs.edit().putBoolean("a11y_guide_shown", true).apply()
                }
                .setCancelable(false)
                .show()
        }, 800)
    }

    private fun startPickCodeService() {
        binding.root.postDelayed({
            try {
                val i = Intent(this, PickCodeService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(i)
                } else {
                    startService(i)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Snackbar.make(
                    binding.root,
                    "后台服务启动失败，可点击下方按钮手动识别",
                    Snackbar.LENGTH_SHORT
                ).show()
            }
        }, 500)
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  菜单
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_about -> {
                startActivity(Intent(this, AboutActivity::class.java))
                true
            }
            R.id.action_clear -> {
                MaterialAlertDialogBuilder(this)
                    .setTitle("清空历史")
                    .setMessage("确定要删除全部识别记录吗？此操作不可撤销。")
                    .setPositiveButton("确认删除") { _, _ ->
                        viewModel.clearAll()
                        Snackbar.make(binding.root, "已清空所有历史记录", Snackbar.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("取消", null)
                    .show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
