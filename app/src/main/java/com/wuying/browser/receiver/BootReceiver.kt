package com.wuying.browser.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.wuying.browser.data.PreferenceManager
import com.wuying.browser.service.CoreService
import com.wuying.browser.util.WuyingLog

/**
 * 开机自启 Receiver
 *
 * 接收多种"系统就绪"广播：BOOT_COMPLETED / LOCKED_BOOT_COMPLETED / QUICKBOOT_POWERON 等
 * 并根据用户偏好决定是否拉起 CoreService
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        WuyingLog.i("Boot", "收到广播 action=${intent.action}")
        if (!PreferenceManager.get(PreferenceManager.KEY_AUTO_START, true)) {
            WuyingLog.d("Boot", "用户关闭了开机自启，跳过")
            return
        }
        startCoreService(context)
    }

    private fun startCoreService(context: Context) {
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
            WuyingLog.e("Boot", "拉起 CoreService 失败", t)
        }
    }
}
