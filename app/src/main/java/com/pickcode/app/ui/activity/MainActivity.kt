package com.pickcode.app.ui.activity

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.service.quicksettings.TileService
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
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.pickcode.app.R
import com.pickcode.app.data.model.CodeRecord
import com.pickcode.app.data.model.CodeType
import com.pickcode.app.databinding.ActivityMainBinding
import com.pickcode.app.ocr.CodeExtractor
import com.pickcode.app.overlay.IslandNotificationManager
import com.pickcode.app.service.PickCodeService
import com.pickcode.app.service.PickCodeAccessibilityService
import com.pickcode.app.tile.PickCodeTileService
import com.pickcode.app.ui.adapter.CodeRecordAdapter
import com.pickcode.app.ui.viewmodel.MainViewModel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var adapter: CodeRecordAdapter
    private val extractor = CodeExtractor()

    // Android 13+ 通知权限申请
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startPickCodeService()
                showTileGuideIfNeeded()
            } else {
                Snackbar.make(
                    binding.root,
                    "需要通知权限才能显示取件码提醒（超级岛/流体云/原子岛）",
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

        setupRecyclerView()
        setupFab()
        setupManualInputButton()
        observeRecords()

        // 请求 Tile 进入 listening 状态
        requestTileListeningState()

        // 申请权限 → 启动服务
        checkAndRequestPermissionsAndStartService()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // 每次回到前台刷新列表（可能有新的识别记录）
        // StateFlow + observeRecords 已自动处理
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  初始化 UI 组件
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private fun setupRecyclerView() {
        adapter = CodeRecordAdapter(
            onFavoriteClick = { viewModel.toggleFavorite(it) },
            onDeleteClick   = { viewModel.delete(it) }
        )
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            this.adapter  = this@MainActivity.adapter
        }

        // 左滑删除
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val record = adapter.currentList[vh.adapterPosition]
                viewModel.delete(record)
                Snackbar.make(binding.root, "已删除 ${record.code}", Snackbar.LENGTH_SHORT).show()
            }
        }).attachToRecyclerView(binding.recyclerView)
    }

    /**
     * FAB（悬浮按钮）：一键截屏识别
     */
    private fun setupFab() {
        binding.fabCapture.setOnClickListener {
            triggerCaptureFromMain()
        }
    }

    /**
     * 手动导入按钮（FAB 上方的小按钮）
     */
    private fun setupManualInputButton() {
        binding.btnManualInput.setOnClickListener {
            showManualInputDialog()
        }
    }

    private fun observeRecords() {
        lifecycleScope.launch {
            viewModel.records.collect { list ->
                adapter.submitList(list)
                binding.layoutEmpty.visibility =
                    if (list.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            }
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  截屏识别触发
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 从主界面触发截屏识别
     *
     * v1.0.5: 优先走 AccessibilityService 截屏（无需录屏弹窗）
     * 如果无障碍服务不可用，降级为启动 PermissionActivity（MediaProjection 方案）
     */
    private fun triggerCaptureFromMain() {
        // 优先：AccessibilityService 无障碍截屏
        if (PickCodeAccessibilityService.isAvailable && PickCodeAccessibilityService.triggerScreenshot()) {
            return  // 成功触发，直接返回
        }

        // 降级：启动 PermissionActivity（MediaProjection 录屏方案）
        startActivity(
            Intent(this, PermissionActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        )
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    //  手动导入功能
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * 显示手动导入对话框
     *
     * 提供两种模式：
     * 1. 粘贴短信模式 — 粘贴整条取件码短信，自动解析提取
     * 2. 手动填写模式 — 手动输入验证码 + 选择类型
     */
    private fun showManualInputDialog() {
        // 使用自定义布局的对话框
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }

        // 标题提示
        val titleView = TextView(this).apply {
            text = "请选择输入方式"
            setTextSize(14f)
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setPadding(0, 0, 0, 16)
        }
        layout.addView(titleView)

        // 输入框（先声明，后面初始化）
        lateinit var inputEdit: TextInputEditText
        // 类型选择（先声明，后面初始化）
        lateinit var typeSelector: LinearLayout

        // 输入方式切换
        var isPasteMode = true // 默认粘贴模式

        val modeSwitch = RadioGroup(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val rbPaste = RadioButton(context).apply {
                text = "\uD83D\uDCEB 粘贴短信"
                isChecked = true
                setTypeface(null, Typeface.BOLD)
                setPadding(24, 0, 24, 0)
            }
            val rbManual = RadioButton(context).apply {
                text = "\u270F\uFE0F 手动填写"
                setPadding(24, 0, 24, 0)
            }
            addView(rbPaste)
            addView(rbManual)
            setOnCheckedChangeListener { _, checkedId ->
                isPasteMode = (checkedId == rbPaste.id)
                inputEdit.hint = if (isPasteMode)
                    "在此粘贴整条取件码短信..."
                else
                    "输入取件码（如：6-8位数字/字母）"

                typeSelector.visibility = if (isPasteMode) android.view.View.GONE else android.view.View.VISIBLE
            }
        }
        layout.addView(modeSwitch)

        // 输入框
        inputEdit = TextInputEditText(this).apply {
            hint = "在此粘贴整条取件码短信...\n例如：【丰巢】您有一个包裹，取件码：5-8-3-2"
            isSingleLine = false
            maxLines = 5
            minLines = 3
            setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
            setPadding(16, 24, 16, 8)
        }
        layout.addView(inputEdit)

        // 类型选择（手动模式下显示）
        typeSelector = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = android.view.View.GONE
            setPadding(0, 16, 0, 0)

            val rg = RadioGroup(context).apply {
                orientation = LinearLayout.HORIZONTAL
                CodeType.values().forEach { type ->
                    addView(RadioButton(context).apply {
                        text = "${type.emoji} ${type.label}"
                        tag = type.ordinal
                        if (type == CodeType.EXPRESS) isChecked = true
                        setPadding(12, 0, 12, 0)
                        textSize = 13f
                    })
                }
            }
            addView(rg)
        }
        layout.addView(typeSelector)

        MaterialAlertDialogBuilder(this)
            .setTitle("\uD83D\uDCCE 手动输入取件码")
            .setView(layout)
            .setPositiveButton("提交到灵动岛") { _, _ ->
                val inputText = inputEdit.text.toString().trim()
                if (inputText.isBlank()) {
                    Snackbar.make(binding.root, "内容不能为空", Snackbar.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                submitManualInput(inputText, isPasteMode, typeSelector)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 提交手动输入的内容
     *
     * @param inputText 用户输入的文本
     * @param isPasteMode true=粘贴短信模式（自动解析），false=手动填写模式
     * @param typeSelector 类型选择器容器（手动模式下读取选中的类型）
     */
    private fun submitManualInput(inputText: String, isPasteMode: Boolean, typeSelector: LinearLayout) {
        if (isPasteMode) {
            // 粘贴短信模式：用 CodeExtractor 自动解析
            val record = extractor.parseCode(inputText)
            if (record != null) {
                // 解析成功 → 提交到 Service 展示到灵动岛
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
            val rg = typeSelector.getChildAt(0) as RadioGroup
            val checkedId = rg.checkedRadioButtonId
            val selectedOrdinal = findViewById<RadioButton>(checkedId)?.tag as? Int ?: CodeType.OTHER.ordinal

            PickCodeService.submitManualCode(
                this, inputText, selectedOrdinal, "(手动输入)"
            )
            val typeLabel = CodeType.values().getOrElse(selectedOrdinal) { CodeType.OTHER }
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
                    ComponentName(this, PickCodeTileService::class.java)
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
                    "添加「码速达」快捷开关后，可在任意界面一键识别验证码！\n\n" +
                    "操作步骤：\n" +
                    "1. 从屏幕顶部向下滑出「通知栏」\n" +
                    "2. 点击右下角「编辑」按钮（⚙\uFE0F 铅笔图标）\n" +
                    "3. 找到「码速达」图标\n" +
                    "4. 按住拖动到上方快捷区域\n\n" +
                    "\uD83D\uDCA1 添加成功后，下拉通知栏点击「码速达」即可立即识别！" +
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
     * v1.0.5: 无障碍服务是截屏识别的核心，必须引导用户开启
     */
    private fun showA11yGuideIfNeeded() {
        val prefs = getSharedPreferences("pickcode_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("a11y_guide_shown", false)) {
            showIslandSupportHint()
            return
        }

        // 延迟弹出，等 Tile 引导消失后再弹
        binding.root.postDelayed({
            AlertDialog.Builder(this)
                .setTitle("\uD83C\uDF1F 开启无障碍服务")
                .setMessage(
                    "码速达需要「无障碍服务」权限来实现一键截图识别。\n\n" +
                    "开启后可以：\n" +
                    "• \u270F\uFE0F 点击即识别（无需每次选择录屏范围）\n" +
                    "• \uD83D\uDCBB 通知栏按钮直接可用\n" +
                    "• \uD83D\uDDD3 Tile 快捷开关稳定响应\n\n" +
                    "\u26A1 开启步骤：\n" +
                    "1. 点击下方「去设置」\n" +
                    "2. 找到「码速达」\n" +
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
                    showIslandSupportHint()
                }
                .setCancelable(false)
                .show()
        }, 800)
    }

    private fun showIslandSupportHint() {
        val description = IslandNotificationManager.getIslandTypeDescription(this)
        val protocolVersion = IslandNotificationManager.getFocusProtocolVersion(this)
        val islandProperty  = IslandNotificationManager.isSupportIslandProperty()

        when {
            protocolVersion >= 3 && islandProperty -> {
                Snackbar.make(
                    binding.root,
                    "\uD83C\uDFA3 检测到小米超级岛，建议在设置 → 通知 → 焦点通知 中开启权限",
                    Snackbar.LENGTH_LONG
                ).setAction("去设置") {
                    try {
                        startActivity(Intent("miui.intent.action.NOTIFICATION_FOCUS_SETTINGS").apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    } catch (_: Exception) {
                        startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
                        })
                    }
                }.show()
            }
            Build.MANUFACTURER?.lowercase() in listOf("oppo", "oneplus") -> {
                Snackbar.make(
                    binding.root,
                    "\uD83C\uDF0A 检测到 OPPO 流体云，码速达已为您自动适配",
                    Snackbar.LENGTH_SHORT
                ).show()
            }
            Build.MANUFACTURER?.lowercase() == "vivo" -> {
                Snackbar.make(
                    binding.root,
                    "\u269B\uFE0F 检测到 vivo 原子岛（需开发者白名单，当前使用标准通知）",
                    Snackbar.LENGTH_SHORT
                ).show()
            }
            else -> { /* 无需提示 */ }
        }
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

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_clear -> {
                AlertDialog.Builder(this)
                    .setTitle("清空历史")
                    .setMessage("确定要删除全部识别记录吗？")
                    .setPositiveButton("确定") { _, _ -> viewModel.clearAll() }
                    .setNegativeButton("取消", null)
                    .show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
