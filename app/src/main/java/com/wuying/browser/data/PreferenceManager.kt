package com.wuying.browser.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * 全局偏好设置（SettingsActivity 双向绑定）
 *
 * 用私有 SharedPreferences，文件名 wuying_prefs
 */
object PreferenceManager {

    private const val PREFS = "wuying_prefs"

    // ===== 隐身 / 持久化 =====
    const val KEY_STEALTH_MODE       = "stealth_mode"        // 无痕模式（不记录历史/Cookie 内存态）
    const val KEY_PERSIST_SESSION    = "persist_session"     // 关闭后是否保留上次标签页
    const val KEY_CLEAR_ON_EXIT      = "clear_on_exit"       // 退出时清数据
    const val KEY_HIDE_ICON          = "hide_icon"           // 桌面图标伪装为系统应用

    // ===== 内核 =====
    const val KEY_JAVASCRIPT_ENABLED = "js_enabled"
    const val KEY_IMAGE_LOAD         = "image_load"
    const val KEY_USER_AGENT         = "user_agent"
    const val KEY_DARK_FORCE         = "dark_force"          // 强制暗黑网页
    const val KEY_COOKIE_ENABLED     = "cookie_enabled"
    const val KEY_DOM_STORAGE        = "dom_storage"
    const val KEY_LOCATION_ENABLED   = "location_enabled"    // 网页定位总开关

    // ===== 广告拦截 =====
    const val KEY_AD_BLOCK           = "ad_block"
    const val KEY_AD_BLOCK_LIST      = "ad_block_list_url"

    // ===== 保活 =====
    const val KEY_KEEP_ALIVE         = "keep_alive"
    const val KEY_AUTO_START         = "auto_start"
    const val KEY_FOREGROUND_NOTICE  = "foreground_notice"

    // ===== 安全 =====
    const val KEY_HTTPS_ONLY         = "https_only"
    const val KEY_DO_NOT_TRACK       = "dnt"

    // ===== 首页 =====
    const val KEY_HOME_PAGE          = "home_page"
    const val KEY_SEARCH_ENGINE      = "search_engine"

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        ensureDefaults()
    }

    private fun ensureDefaults() {
        val needInit = !sp.contains(KEY_JAVASCRIPT_ENABLED)
        if (needInit) {
            sp.edit {
                putBoolean(KEY_STEALTH_MODE, false)
                putBoolean(KEY_PERSIST_SESSION, true)
                putBoolean(KEY_CLEAR_ON_EXIT, false)
                putBoolean(KEY_HIDE_ICON, false)
                putBoolean(KEY_JAVASCRIPT_ENABLED, true)
                putBoolean(KEY_IMAGE_LOAD, true)
                putBoolean(KEY_DARK_FORCE, true)
                putBoolean(KEY_COOKIE_ENABLED, true)
                putBoolean(KEY_DOM_STORAGE, true)
                putBoolean(KEY_LOCATION_ENABLED, true)
                putBoolean(KEY_AD_BLOCK, true)
                putString(KEY_AD_BLOCK_LIST, "https://easylist-downloads.adblockplus.org/easylistchina.txt")
                putBoolean(KEY_KEEP_ALIVE, true)
                putBoolean(KEY_AUTO_START, true)
                putBoolean(KEY_FOREGROUND_NOTICE, true)
                putBoolean(KEY_HTTPS_ONLY, false)
                putBoolean(KEY_DO_NOT_TRACK, true)
                putString(KEY_HOME_PAGE, "https://www.bing.com")
                putString(KEY_SEARCH_ENGINE, "https://www.bing.com/search?q=%s")
            }
        }
    }

    val spRef: SharedPreferences get() = sp

    fun get(key: String, default: Boolean): Boolean = sp.getBoolean(key, default)
    fun set(key: String, value: Boolean) = sp.edit { putBoolean(key, value) }
    fun get(key: String, default: String): String = sp.getString(key, default) ?: default
    fun set(key: String, value: String) = sp.edit { putString(key, value) }
}
