package com.pickcode.app.ui.activity

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.pickcode.app.BuildConfig
import com.pickcode.app.R
import com.pickcode.app.databinding.ActivityAboutBinding

class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 沉浸式状态栏：让内容延伸到状态栏区域
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT

        // 深色 Hero 背景 → 状态栏图标用浅色（白色）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.decorView.systemUiVisibility = 0
        }

        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupInsets()
        displayVersion()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = ""
    }

    /**
     * 处理窗口 Insets
     * - Hero 内容顶部避开状态栏
     * - NestedScrollView 底部避开导航栏
     */
    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.heroContent) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            view.setPadding(
                view.paddingLeft,
                bars.top + 16,
                view.paddingRight,
                view.paddingBottom
            )
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.nestedScrollView) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.setPadding(
                view.paddingLeft,
                view.paddingTop,
                view.paddingRight,
                bars.bottom + 20
            )
            insets
        }
    }

    private fun displayVersion() {
        val versionText = getString(R.string.about_version_format, "v${BuildConfig.VERSION_NAME}")
        binding.tvVersionHero.text = versionText
        binding.tvVersionBottom.text = getString(R.string.about_version_format, "v${BuildConfig.VERSION_NAME}")
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
