package com.wuying.browser.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.wuying.browser.R
import com.wuying.browser.util.WuyingLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * DaemonService - 守护进程
 *
 * 跑在 :daemon 进程。本进程的存在意义只有一个：
 * 周期性 ping 一下 :core 进程，如果 :core 没响应就拉起它。
 * 反之 :core 进程也会周期性 ping :daemon，互相保活。
 *
 * Android 系统同一时刻杀两个独立进程的概率远低于杀单进程，
 * 这就是"双进程守护"的本质。
 */
class DaemonService : Service() {

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /**
         * 在 :daemon 进程拉起本服务（不影响调用方进程）
         */
        fun start(context: Context) {
            val i = Intent(context, DaemonService::class.java)
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
            } catch (t: Throwable) {
                WuyingLog.e("Daemon", "拉起 DaemonService 失败", t)
            }
        }

        /**
         * ping :core 进程
         */
        fun ping(context: Context) {
            val i = Intent(context, CoreService::class.java).apply {
                action = CoreService.ACTION_PING_FROM_DAEMON
            }
            try { context.startService(i) } catch (_: Throwable) {}
        }

        /**
         * 重启 :core 进程
         */
        fun restartCore(context: Context) {
            val i = Intent(context, CoreService::class.java).apply {
                action = CoreService.ACTION_START
            }
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
            } catch (t: Throwable) {
                WuyingLog.e("Daemon", "重启 CoreService 失败", t)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        WuyingLog.i("Daemon", "DaemonService onCreate pid=${android.os.Process.myPid()}")
        // 守护进程前台通知：与 CoreService 一致的「系统更新」伪装风格，
        // 点击直接拉起悬浮窗浏览器面板（v1.3.0 起直达 FloatingBrowserActivity）
        val pi = android.app.PendingIntent.getActivity(
            this, 0x11,
            android.content.Intent(this, com.wuying.browser.ui.FloatingBrowserActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val notif = android.app.Notification.Builder(this, CoreService.CHANNEL_ID_DISGUISE)
            .setSmallIcon(R.drawable.ic_sys_update_notif)
            .setContentTitle(getString(R.string.notif_disguise_title))
            .setContentText(getString(R.string.notif_disguise_text))
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(android.app.Notification.PRIORITY_LOW)
            .build()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(0x77_78, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(0x77_78, notif)
        }
        startWatchdog()
    }

    private fun startWatchdog() {
        scope.launch {
            while (true) {
                delay(60 * 1000L) // 每分钟检查一次
                try {
                    if (!CoreService.isRunning) {
                        WuyingLog.w("Daemon", "CoreService 不在运行，拉起它")
                        restartCore(this@DaemonService)
                    } else {
                        // ping 一下保持活跃
                        ping(this@DaemonService)
                    }
                } catch (t: Throwable) {
                    WuyingLog.e("Daemon", "watchdog 异常", t)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 让系统杀掉后自动重启
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 用户清掉任务也重启
        restartCore(this)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        // 自己被杀前先尝试拉起 Core，让 Core 重新拉起自己
        restartCore(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
