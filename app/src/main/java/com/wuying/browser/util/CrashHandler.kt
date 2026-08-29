package com.wuying.browser.util

import android.app.Application
import android.content.Intent
import android.os.Process
import com.wuying.browser.BrowserApplication
import com.wuying.browser.service.CoreService
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局 Crash 处理器：
 * 1. 记录崩溃堆栈到文件
 * 2. 拉起 CoreService（保活的核心 —— 即使主进程崩溃，也能被守护进程拉起）
 * 3. 自杀进程避免系统弹框
 */
object CrashHandler : Thread.UncaughtExceptionHandler {

    private const val TAG = "CrashHandler"
    private var app: Application? = null
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null

    fun install(application: Application) {
        app = application
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(t: Thread, e: Throwable) {
        // 1. 写入崩溃日志
        try {
            val sw = StringWriter()
            PrintWriter(sw).use { e.printStackTrace(it) }
            val time = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())
            val logDir = File(app!!.filesDir, "crash").apply { mkdirs() }
            File(logDir, "crash_$time.txt").writeText(
                "Time: ${Date()}\nThread: ${t.name}\nPID: ${Process.myPid()}\n\n$sw"
            )
        } catch (_: Throwable) {}

        // 2. 尝试拉起核心服务（即使主进程要挂）
        try {
            val ctx = app ?: return
            val intent = Intent(ctx, CoreService::class.java).apply {
                action = CoreService.ACTION_RESTART_AFTER_CRASH
            }
            android.content.Context::class.java
                .getMethod("startForegroundService", Intent::class.java)
                .invoke(ctx, intent)
        } catch (_: Throwable) {}

        // 3. 交给默认处理器
        defaultHandler?.uncaughtException(t, e)
    }
}
