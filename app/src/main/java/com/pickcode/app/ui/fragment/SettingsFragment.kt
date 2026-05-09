package com.pickcode.app.ui.fragment

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.pickcode.app.R
import com.pickcode.app.service.PickCodeAccessibilityService

class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        setupAccessibilityPreference()
    }

    private fun setupAccessibilityPreference() {
        val pref = findPreference<Preference>("accessibility_service") ?: return

        // 更新无障碍服务状态显示
        updateA11ySummary(pref)

        // 点击 → 跳转系统无障碍设置页面
        pref.setOnPreferenceClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            true
        }
    }

    private fun updateA11ySummary(pref: Preference) {
        val isAvailable = PickCodeAccessibilityService.isAvailable
        pref.summary = if (isAvailable) {
            getString(R.string.pref_a11y_summary_on)
        } else {
            getString(R.string.pref_a11y_summary_off)
        }
    }
}
