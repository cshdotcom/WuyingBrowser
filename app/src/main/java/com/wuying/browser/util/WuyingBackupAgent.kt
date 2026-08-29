package com.wuying.browser.util

import android.app.backup.BackupAgentHelper
import android.app.backup.SharedPreferencesBackupHelper

/**
 * 备份代理：把 SessionManager、Settings 等关键 SharedPreferences 备份到 Google 云，
 * 即使换机也能恢复上次浏览状态。
 */
class WuyingBackupAgent : BackupAgentHelper() {
    override fun onCreate() {
        addHelper("prefs", SharedPreferencesBackupHelper(this, "wuying_prefs"))
        addHelper("session", SharedPreferencesBackupHelper(this, "wuying_session"))
    }
}
