package com.pickcode.app.ui.activity

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.pickcode.app.R
import com.pickcode.app.databinding.ActivityMainBinding
import com.pickcode.app.overlay.IslandNotificationManager
import com.pickcode.app.service.PickCodeService
import com.pickcode.app.ui.adapter.CodeRecordAdapter
import com.pickcode.app.ui.viewmodel.MainViewModel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var adapter: CodeRecordAdapter

    // Android 13+ 通知权限申请
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                // 权限通过 → 启动常驻服务 + 显示引导
                startPickCodeService()
                showIslandSupportHint()
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
        observeRecords()

        // 先申请权限，权限通过后再启动服务和显示引导
        checkAndRequestPermissionsAndStartService()
    }

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

    private fun setupFab() {
        binding.fabCapture.setOnClickListener {
            PickCodeService.triggerCapture(this)
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

    /**
     * 权限检查与申请 → 通过后启动常驻服务
     *
     * 新方案（小米超级岛）：
     * - ✅ 不再需要 SYSTEM_ALERT_WINDOW（悬浮窗权限）
     * - 仅需 POST_NOTIFICATIONS（Android 13+ 通知权限）
     * - 小米设备额外需要：在设置中手动开启「焦点通知」权限
     *
     * ✅ 权限通过后会启动 PickCodeService 前台服务，提供：
     *   1. 通知栏常驻通知（点击可触发识别）
     *   2. Quick Settings Tile 可用
     */
    private fun checkAndRequestPermissionsAndStartService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ 需要动态申请通知权限
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }

        // 权限已满足 → 启动服务 + 显示引导
        startPickCodeService()
        showIslandSupportHint()
        showTileGuideIfNeeded()
    }

    /**
     * 提示用户当前设备的灵动岛支持情况
     *
     * ╔════════════════════════════════════════════════════╗
     * ║  小米澎湃OS3   → 超级岛（引导开启焦点通知权限）       ║
     * ║  OPPO ColorOS  → 流体云（提示已支持，无需额外权限）   ║
     * ║  vivo OriginOS → 原子岛（提示需申请白名单）          ║
     * ║  其他设备      → 无需提示                           ║
     * ╚════════════════════════════════════════════════════╝
     */
    private fun showIslandSupportHint() {
        val description = IslandNotificationManager.getIslandTypeDescription(this)
        val protocolVersion = IslandNotificationManager.getFocusProtocolVersion(this)
        val islandProperty  = IslandNotificationManager.isSupportIslandProperty()

        when {
            // ── 小米超级岛 ──
            protocolVersion >= 3 && islandProperty -> {
                Snackbar.make(
                    binding.root,
                    "🏝️ 检测到小米超级岛，建议在设置 → 通知 → 焦点通知 中开启权限",
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
            // ── OPPO 流体云 ──
            android.os.Build.MANUFACTURER?.lowercase() == "oppo" ||
            android.os.Build.MANUFACTURER?.lowercase() == "oneplus" -> {
                Snackbar.make(
                    binding.root,
                    "🌊 检测到 OPPO 流体云，码速达已为您自动适配",
                    Snackbar.LENGTH_SHORT
                ).show()
            }
            // ── vivo 原子岛 ──
            android.os.Build.MANUFACTURER?.lowercase() == "vivo" -> {
                Snackbar.make(
                    binding.root,
                    "⚛️ 检测到 vivo 原子岛（需开发者白名单，当前使用标准通知）",
                    Snackbar.LENGTH_SHORT
                ).show()
            }
            // ── 其他设备：无需提示 ──
            else -> { /* 使用标准通知横幅，不打扰用户 */ }
        }
    }

    /**
     * 首次使用引导：教用户添加 Quick Settings Tile（快捷开关）
     *
     * 引导条件（仅首次）：
     * - SharedPreferences 中未记录 "tile_guide_shown"
     *
     * 引导方式：Snackbar + 跳转 Quick Settings 编辑页面
     */
    private fun showTileGuideIfNeeded() {
        val prefs = getSharedPreferences("pickcode_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("tile_guide_shown", false)) return

        // 延迟 1.5 秒显示，避免和超级岛提示 Snackbar 冲突
        binding.root.postDelayed({
            Snackbar.make(
                binding.root,
                "💡 下拉通知栏 → 点「编辑」→ 找到「码速达」并添加，即可在任意屏幕一键识别",
                Snackbar.LENGTH_LONG
            ).setAction("去编辑") {
                try {
                    // 尝试直接打开 QS 编辑页面（部分 ROM 支持）
                    startActivity(Intent("android.quicksettings.action.QS_EDIT").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                } catch (_: Exception) {
                    // 降级提示：告诉用户手动操作
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("添加快捷开关")
                        .setMessage(
                            "1. 从屏幕顶部向下滑出通知栏\n" +
                            "2. 点击右下角「编辑」按钮（铅笔图标 ⚡）\n" +
                            "3. 找到「码速达」图标\n" +
                            "4. 按住拖动到上方快捷区域即可\n\n" +
                            "添加后即可在任意界面下拉通知栏快速触发识别！"
                        )
                        .setPositiveButton("知道了", null)
                        .show()
                }
            }.show()

            // 标记已显示过引导
            prefs.edit().putBoolean("tile_guide_shown", true).apply()
        }, 1500)
    }

    /**
     * 安全启动 PickCodeService（前台服务）
     *
     * 使用 try-catch 保护，防止 Android 14+ FGS 限制导致崩溃
     * 同时延迟 500ms 启动，确保 Activity 完全进入 resumed 状态
     */
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
                // 极端情况：FGS 启动失败，不崩溃，用户仍可用 FAB 和 Tile
                e.printStackTrace()
                Snackbar.make(
                    binding.root,
                    "后台服务启动失败，可点击下方按钮手动识别",
                    Snackbar.LENGTH_SHORT
                ).show()
            }
        }, 500)
    }

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
