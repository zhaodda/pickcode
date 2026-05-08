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
import com.google.android.material.tabs.TabLayout
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
import com.pickcode.app.service.PickCodeService
import com.pickcode.app.ui.activity.LogViewerActivity
import com.pickcode.app.tile.PickCodeTileService
import com.pickcode.app.ui.adapter.CodeRecordAdapter
import com.pickcode.app.ui.viewmodel.MainViewModel
import com.pickcode.app.util.AppLog
import kotlinx.coroutines.launch
import android.util.Log

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var adapter: CodeRecordAdapter
    private val extractor = CodeExtractor()

    /** 当前选中的 Tab（0=未取件, 1=已取件） */
    private var currentTab = 0

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

        setupTabLayout()
        setupRecyclerView()
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

    private fun setupTabLayout() {
        binding.tabLayout.apply {
            addTab(newTab().setText("未取件 (0)"))
            addTab(newTab().setText("已取件 (0)"))
            setOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab?) {
                    tab?.let { currentTab = it.position }
                }
                override fun onTabUnselected(tab: TabLayout.Tab?) {}
                override fun onTabReselected(tab: TabLayout.Tab?) {}
            })
        }
    }

    private fun setupRecyclerView() {
        adapter = CodeRecordAdapter(
            onFavoriteClick = { viewModel.toggleFavorite(it) },
            onDeleteClick   = { viewModel.delete(it) },
            onPickedUpClick = { record ->
                viewModel.togglePickedUp(record)
                if (!record.isPickedUp)
                    Snackbar.make(binding.root, "✅ 已标记为已取件", Snackbar.LENGTH_SHORT).show()
                else
                    Snackbar.make(binding.root, "已恢复为未取件", Snackbar.LENGTH_SHORT).show()
            }
        )
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            this.adapter  = this@MainActivity.adapter
        }

        // 左滑删除（仅未取件页有效）
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val record = adapter.currentList[vh.adapterPosition]
                viewModel.delete(record)
                Snackbar.make(binding.root, "已删除 ${record.code}", Snackbar.LENGTH_SHORT).show()
            }

            override fun getSwipeDirs(recyclerView: RecyclerView, holder: RecyclerView.ViewHolder): Int {
                // 已取件的记录不允许左滑删除
                return super.getSwipeDirs(recyclerView, holder)
            }
        }).attachToRecyclerView(binding.recyclerView)
    }

    /**
     * 手动输入按钮（底部主操作）
     */
    private fun setupManualInputButton() {
        binding.btnManualInput.setOnClickListener {
            showManualInputDialog()
        }
    }

    private fun observeRecords() {
        // 观察未取件记录
        lifecycleScope.launch {
            viewModel.notPickedUpRecords.collect { list ->
                if (currentTab == 0) {
                    adapter.submitList(list)
                    binding.layoutEmpty.visibility =
                        if (list.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
                }
                // 更新 Tab 数量
                updateTabText(0, "未取件", list.size)
            }
        }

        // 观察已取件记录
        lifecycleScope.launch {
            viewModel.pickedUpRecords.collect { list ->
                if (currentTab == 1) {
                    adapter.submitList(list)
                    binding.layoutEmpty.visibility =
                        if (list.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
                }
                // 更新 Tab 数量
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
    private fun showManualInputDialog() {
        // 使用自定义布局的对话框
        val context = this
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 8)
        }

        // ── 模式切换（Segmented 样式）──
        var isPasteMode = true // 默认粘贴模式
        lateinit var inputEdit: TextInputEditText
        lateinit var typeSelector: LinearLayout

        val modeContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = ContextCompat.getDrawable(context, R.drawable.bg_mode_switch)
            setPadding(4f.toPx(), 4f.toPx(), 4f.toPx(), 4f.toPx())

            val rbPaste = RadioButton(context).apply {
                text = "📨 粘贴短信"
                isChecked = true
                setButtonDrawable(android.R.color.transparent)
                gravity = android.view.Gravity.CENTER
                setPadding(0, 10f.toPx(), 0, 10f.toPx())
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(ContextCompat.getColorStateList(context, R.color.text_mode_switch))
                minWidth = 120
            }
            val rbManual = RadioButton(context).apply {
                text = "✏️ 手动填写"
                setButtonDrawable(android.R.color.transparent)
                gravity = android.view.Gravity.CENTER
                setPadding(0, 10f.toPx(), 0, 10f.toPx())
                textSize = 14f
                setTextColor(ContextCompat.getColorStateList(context, R.color.text_mode_switch))
                minWidth = 120
            }
            val modeGroup = RadioGroup(context).apply {
                orientation = LinearLayout.HORIZONTAL
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
            addView(modeGroup)
        }
        layout.addView(modeContainer)

        // ── 输入框区域（带圆角背景）──
        val inputContainer = com.google.android.material.card.MaterialCardView(context).apply {
            useCompatPadding = false
            cardElevation = 0f
            radius = 16f
            strokeColor = ContextCompat.getColor(context, R.color.divider)
            strokeWidth = 1
            setContentPadding(16f.toPx(), 20f.toPx(), 16f.toPx(), 12f.toPx())
            setCardBackgroundColor(ContextCompat.getColor(context, R.color.bg_secondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 20f.toPx() }

            inputEdit = TextInputEditText(context).apply {
                hint = "在此粘贴整条取件码短信...\n例如：【丰巢】您有一个包裹，取件码：5-8-3-2"
                isSingleLine = false
                maxLines = 5
                minLines = 3
                setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
                background = null
                textSize = 15f
                setLineSpacing(6f, 1f)
            }
            addView(inputEdit)
        }
        layout.addView(inputContainer)

        // ── 类型选择（手动模式下显示）──
        typeSelector = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = android.view.View.GONE
            setPadding(4f.toPx(), 18f.toPx(), 4f.toPx(), 0)

            val labelTv = TextView(context).apply {
                text = "选择类型"
                textSize = 13f
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                setPadding(0, 0, 0, 10f.toPx())
            }
            addView(labelTv)

            val rg = RadioGroup(context).apply {
                orientation = LinearLayout.HORIZONTAL
                CodeType.values().forEach { type ->
                    addView(RadioButton(context).apply {
                        text = "${type.emoji} ${type.label}"
                        tag = type.ordinal
                        if (type == CodeType.EXPRESS) isChecked = true
                        setPadding(16f.toPx(), 8f.toPx(), 16f.toPx(), 8f.toPx())
                        buttonTintList = ContextCompat.getColorStateList(context, R.color.accent_primary)
                        textSize = 13f
                    })
                }
            }
            addView(rg)
        }
        layout.addView(typeSelector)

        MaterialAlertDialogBuilder(this)
            .setTitle("📮 手动输入取件码")
            .setView(layout)
            .setPositiveButton("确认提交") { _, _ ->
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
                // 解析成功 → 提交到 Service 展示
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
                    "添加「码住」快捷开关后，可在任意界面一键识别验证码！\n\n" +
                    "操作步骤：\n" +
                    "1. 从屏幕顶部向下滑出「通知栏」\n" +
                    "2. 点击右下角「编辑」按钮（⚙\uFE0F 铅笔图标）\n" +
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
     * v1.0.5: 无障碍服务是截屏识别的核心，必须引导用户开启
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
                    "码住需要「无障碍服务」权限来实现一键截图识别。\n\n" +
                    "开启后可以：\n" +
                    "• \u270F\uFE0F 点击即识别（无需每次选择录屏范围）\n" +
                    "• \uD83D\uDCBB 通知栏按钮直接可用\n" +
                    "• \uD83D\uDDD3 Tile 快捷开关稳定响应\n\n" +
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
            R.id.action_logs -> {
                AppLog.i("MainActivity", "用户打开了运行日志查看器")
                startActivity(Intent(this, LogViewerActivity::class.java))
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
