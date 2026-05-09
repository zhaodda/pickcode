package com.pickcode.app.ui.activity

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.pickcode.app.BuildConfig
import com.pickcode.app.R
import com.pickcode.app.databinding.ActivityAboutBinding

class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = ""

        // 版本号
        binding.tvVersion.text = getString(R.string.about_version_format, "v${BuildConfig.VERSION_NAME}")
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
