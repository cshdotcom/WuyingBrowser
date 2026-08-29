package com.wuying.browser

import android.app.Application
import android.content.Intent
import android.os.Build
import android.webkit.WebView
import androidx.appcompat.app.AppCompatDelegate
import androidx.work.Configuration
import androidx.work.WorkManager
import com.wuying.browser.data.AppDatabase
import com.wuying.browser.data.PreferenceManager
import com.wuying.browser.service.CoreService
import com.wuying.browser.util.CrashHandler
import com.wuying.browser.util.WuyingLog

/**
 * 无影浏览器 Application 入口
 *
 * 负责初始化：
 * 1. 暗黑主题强制开启
 * 2. WebView 单例数据目录（Android P+ 要求）
 * 3. 数据库 / 偏好设置
 * 4. 全局 CrashHandler
 * 5. WorkManager 自定义配置
 * 6. 启动 CoreService 进行保活
 *
 * 关键点：本 Application 即使 Activity 都被销毁，仍在主进程持续运行，
 * 数据库连接、Cookie、WebView 缓存全部保留，下次启动直接复用。
 */
class BrowserApplication : Application(), Configuration.Provider {

    companion object {
        @JvmStatic
        lateinit var instance: BrowserApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 强制暗黑模式（暗黑极客主题）
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)

        // Android P+ 多进程 WebView 数据目录隔离
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WebView.setDataDirectorySuffix("wuying")
        }

        // 全局未捕获异常处理 —— 异常时重启 CoreService
        CrashHandler.install(this)

        // 初始化数据库
        AppDatabase.init(this)

        // 初始化偏好
        PreferenceManager.init(this)

        // WorkManager 初始化（自定义 Configuration）
        WorkManager.initialize(this, workManagerConfiguration)

        // 启动核心保活服务
        ensureCoreServiceRunning()

        WuyingLog.i("BrowserApplication", "无影浏览器 Application onCreate 完成，pid=${android.os.Process.myPid()}")
    }

    /**
     * 拉起核心前台服务。
     * - 已运行则不动
     * - 未运行则 startForegroundService
     */
    fun ensureCoreServiceRunning() {
        val intent = Intent(this, CoreService::class.java).apply {
            action = CoreService.ACTION_START
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (t: Throwable) {
            WuyingLog.e("BrowserApplication", "拉起 CoreService 失败", t)
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
}
