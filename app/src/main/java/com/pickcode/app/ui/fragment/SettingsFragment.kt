package com.pickcode.app.ui.fragment

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat
import com.pickcode.app.BuildConfig
import com.pickcode.app.R

class SettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        // 动态显示版本号（跟随 build.gradle 中的 versionName）
        findPreference<androidx.preference.Preference>("version")?.summary =
            "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
    }
}
