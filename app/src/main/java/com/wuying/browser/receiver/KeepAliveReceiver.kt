package com.wuying.browser.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.wuying.browser.service.CoreService
import com.wuying.browser.util.WuyingLog

/**
 * 多种系统事件的兜底保活：
 * - USER_PRESENT / SCREEN_ON：解锁 / 亮屏时 ping 一下 CoreService
 * - TIME_TICK：每分钟系统广播，顺便 ping
 * - CONNECTIVITY_CHANGE：网络变化时 ping
 * - com.wuying.browser.PING：自定义心跳
 */
class KeepAliveReceiver : BroadcastReceiver() {

    companion object {
        private const val ALARM_REQ = 0x1001
        private const val ALARM_INTERVAL_MS = 5 * 60 * 1000L

        /**
         * 注册一个 5 分钟周期的 AlarmManager 兜底心跳
         */
        fun scheduleAlarm(context: Context) {
            val intent = Intent(context, KeepAliveReceiver::class.java).apply {
                action = "com.wuying.browser.PING"
            }
            val pi = PendingIntent.getBroadcast(
                context, ALARM_REQ, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            // 即使休眠也触发（但 Android P+ 对 setExact 限制，setAndAllowWhileIdle 兜底）
            am.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + ALARM_INTERVAL_MS,
                ALARM_INTERVAL_MS,
                pi
            )
            WuyingLog.d("KeepAlive", "AlarmManager 心跳已注册，间隔 ${ALARM_INTERVAL_MS}ms")
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        WuyingLog.d("KeepAlive", "action=${intent.action}，ping CoreService")
        val i = Intent(context, CoreService::class.java).apply {
            action = CoreService.ACTION_PING_FROM_DAEMON
        }
        try {
            context.startService(i)
        } catch (t: Throwable) {
            // 如果连 startService 都不允许，尝试 startForegroundService
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(i)
                }
            } catch (_: Throwable) {}
        }
    }
}
