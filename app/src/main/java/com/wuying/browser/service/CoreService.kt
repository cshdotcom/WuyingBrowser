package com.wuying.browser.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import com.wuying.browser.BrowserApplication
import com.wuying.browser.R
import com.wuying.browser.data.PreferenceManager
import com.wuying.browser.receiver.KeepAliveReceiver
import com.wuying.browser.ui.BrowserActivity
import com.wuying.browser.ui.FloatingLauncherActivity
import com.wuying.browser.util.WuyingLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * CoreService - 核心保活前台服务
 *
 * 实现机制：
 * 1. 前台服务 + 持久通知：系统正常情况下不会杀
 * 2. 双进程：本服务跑在 :core 进程，DaemonService 跑在 :daemon 进程
 *    两个进程互相绑定（ Binder 桥接），一方被杀另一方立刻拉起
 * 3. onTaskRemoved：用户从最近任务滑掉时，自动重启 Activity / Service
 * 4. AlarmManager 周期性心跳：每 5 分钟自检
 * 5. WorkManager 兜底：每 15 分钟被系统拉起一次
 * 6. CrashHandler 在崩溃时拉起本服务
 *
 * 通知样式：使用最小化通知（最低优先级），不响铃不震动，最大限度降低存在感
 */
class CoreService : Service() {

    companion object {
        const val ACTION_START = "com.wuying.browser.action.START"
        const val ACTION_RESTART_AFTER_CRASH = "com.wuying.browser.action.RESTART_AFTER_CRASH"
        const val ACTION_PING_FROM_DAEMON = "com.wuying.browser.action.PING"
        const val ACTION_HIDE_UI = "com.wuying.browser.action.HIDE"

        const val CHANNEL_ID_FOREGROUND = "wuying_foreground"
        const val CHANNEL_ID_DOWNLOAD = "wuying_download"
        const val CHANNEL_ID_DISGUISE = "wuying_sys_svc"
        const val NOTIFICATION_ID = 0x77_77
        const val NOTIFICATION_ID_DISGUISE = 0x77_79

        /**
         * 静态 flag，跨进程通信通过 SharedPreferences 而非内存，故此处仅本进程可见。
         * 真实存活状态以 [CoreService] 前台通知为准。
         */
        @Volatile var isRunning = false
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var heartbeatJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        WuyingLog.i("Core", "CoreService onCreate pid=${android.os.Process.myPid()}")
        createNotificationChannels()
        startForegroundCompat()
        isRunning = true

        // 心跳：每 5 分钟自检一次，被杀也能被 AlarmManager 拉起
        startHeartbeat()
        // 同时启动守护进程
        DaemonService.start(this)
        // 注册到 KeepAliveReceiver 的定时器
        KeepAliveReceiver.scheduleAlarm(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        WuyingLog.d("Core", "onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_PING_FROM_DAEMON -> {
                // 守护进程发来 ping，回应一次
                DaemonService.ping(this)
            }
            ACTION_RESTART_AFTER_CRASH -> {
                WuyingLog.w("Core", "主进程崩溃后拉起")
            }
            ACTION_HIDE_UI -> {
                // 隐藏 UI 不退出服务
                val pi = PendingIntent.getActivity(
                    this, 0,
                    Intent(this, BrowserActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                    PendingIntent.FLAG_IMMUTABLE
                )
                // 不做什么，仅仅是占位
            }
        }
        return START_STICKY  // 被杀后系统会重启
    }

    /**
     * 当用户从最近任务列表滑掉应用时触发
     * 此时主动拉起自己 + 浏览器 Activity（如果用户开启了"恢复会话"）
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        WuyingLog.w("Core", "onTaskRemoved —— 用户从最近任务滑掉，自动重启服务")
        val restart = Intent(applicationContext, CoreService::class.java).apply {
            action = ACTION_START
        }
        val pi = PendingIntent.getService(
            this, 1, restart,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarm = getSystemService(ALARM_SERVICE) as AlarmManager
        alarm[AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 1000] = pi

        // 如果启用了会话恢复，则 1.5 秒后拉起 BrowserActivity
        if (PreferenceManager.get(PreferenceManager.KEY_PERSIST_SESSION, true)) {
            scope.launch {
                delay(1500)
                try {
                    val i = Intent(applicationContext, BrowserActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    applicationContext.startActivity(i)
                } catch (_: Throwable) {}
            }
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        heartbeatJob?.cancel()
        WuyingLog.w("Core", "CoreService onDestroy —— 但守护进程会拉回")
        // 主动让 DaemonService 拉起自己
        DaemonService.restartCore(this)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            // 前台服务通道：最低优先级，无声音
            val foreground = NotificationChannel(
                CHANNEL_ID_FOREGROUND,
                getString(R.string.notif_channel_foreground),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = getString(R.string.notif_channel_foreground_desc)
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            // 下载通道：高优先级，会弹通知
            val download = NotificationChannel(
                CHANNEL_ID_DOWNLOAD,
                getString(R.string.notif_channel_download),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = getString(R.string.notif_channel_download_desc)
                setShowBadge(true)
            }
            // 伪装驻留通道：伪装成系统更新类系统服务，低优先级无声常驻
            val disguise = NotificationChannel(
                CHANNEL_ID_DISGUISE,
                getString(R.string.notif_channel_disguise),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_disguise_desc)
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            nm.createNotificationChannels(listOf(foreground, download, disguise))
        }
    }

    /**
     * 启动前台服务 —— 前台通知本身就是那条「伪装驻留通知」（三合一）
     *
     * - ongoing=true 随前台服务驻留通知栏，系统不会回收（驻留）
     * - 渠道/标题/文案/图标均为「系统更新」风格（伪装）
     * - 点击 -> FloatingLauncherActivity -> 拉起悬浮窗版浏览器（点击行为）
     *
     * 不再额外发第二条伪装通知：两条样式几乎一样的通知会让用户点错
     * （旧版就是因此点到了打开主界面的那条）。
     */
    private fun startForegroundCompat() {
        val pi = PendingIntent.getActivity(
            this, 0x10,
            Intent(this, FloatingLauncherActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = Notification.Builder(this, CHANNEL_ID_DISGUISE)
            .setContentTitle(getString(R.string.notif_disguise_title))
            .setContentText(getString(R.string.notif_disguise_text))
            .setSmallIcon(R.drawable.ic_sys_update_notif)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(Notification.PRIORITY_LOW)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }
    }

    private fun startHeartbeat() {
        heartbeatJob = scope.launch {
            while (true) {
                delay(5 * 60 * 1000L)
                WuyingLog.d("Core", "heartbeat tick pid=${android.os.Process.myPid()}")
                // ping 一下 daemon 进程
                DaemonService.ping(this@CoreService)
            }
        }
    }
}
