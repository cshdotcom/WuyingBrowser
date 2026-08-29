package com.wuying.browser.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import androidx.preference.ListPreference
import androidx.preference.EditTextPreference
import com.wuying.browser.R
import com.wuying.browser.data.HistoryManager
import com.wuying.browser.data.PreferenceManager
import com.wuying.browser.service.CoreService
import com.wuying.browser.service.DaemonService
import kotlinx.coroutines.launch

/**
 * 设置页 —— PreferenceFragment 标准实现
 *
 * 偏好实时写入 SharedPreferences，所有读取方都通过 PreferenceManager 单例读取
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            // 在偏好变更时即时触发某些动作
            findPreference<SwitchPreferenceCompat>(PreferenceManager.KEY_KEEP_ALIVE)?.setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                if (enabled) {
                    context?.let { DaemonService.start(it); it.startService(android.content.Intent(it, CoreService::class.java).apply { action = CoreService.ACTION_START }) }
                }
                true
            }

            findPreference<SwitchPreferenceCompat>(PreferenceManager.KEY_STEALTH_MODE)?.setOnPreferenceChangeListener { _, newValue ->
                HistoryManager.get(requireContext()).setStealth(newValue as Boolean)
                true
            }

            findPreference<Preference>("clear_data_now")?.setOnPreferenceClickListener {
                clearAllData()
                true
            }

            findPreference<SwitchPreferenceCompat>(PreferenceManager.KEY_HIDE_ICON)?.setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                toggleLauncherIcon(enabled)
                true
            }
        }

        private fun clearAllData() {
            android.webkit.WebStorage.getInstance().deleteAllData()
            android.webkit.CookieManager.getInstance().removeAllCookies(null)
            android.webkit.CookieManager.getInstance().flush()
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
                com.wuying.browser.data.AppDatabase.get(requireContext()).historyDao().clear()
            }
            android.widget.Toast.makeText(requireContext(), R.string.cleared, android.widget.Toast.LENGTH_SHORT).show()
        }

        /**
         * 切换桌面图标可见性
         *
         * 原理：PackageManager.setComponentEnabledSetting 启用/禁用
         * 对应 Activity 的 LAUNCHER alias
         */
        private fun toggleLauncherIcon(hide: Boolean) {
            val pm = requireContext().packageManager
            val pkg = requireContext().packageName
            // 主 Launcher
            val mainComp = android.content.ComponentName(pkg, "com.wuying.browser.ui.BrowserActivity")
            // 伪装 alias
            val aliasComp = android.content.ComponentName(pkg, "com.wuying.browser.alias.SystemHelper")
            val mainState = if (hide)
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            else
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            try {
                pm.setComponentEnabledSetting(
                    mainComp, mainState,
                    android.content.pm.PackageManager.DONT_KILL_APP
                )
                android.widget.Toast.makeText(
                    requireContext(),
                    if (hide) R.string.icon_hidden else R.string.icon_restored,
                    android.widget.Toast.LENGTH_LONG
                ).show()
            } catch (t: Throwable) {
                android.widget.Toast.makeText(requireContext(), t.message, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
}
