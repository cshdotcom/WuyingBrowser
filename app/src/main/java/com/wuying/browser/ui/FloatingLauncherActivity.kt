package com.wuying.browser.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import com.wuying.browser.R
import com.wuying.browser.service.FloatingBrowserService
import com.wuying.browser.util.WuyingLog

/**
 * FloatingLauncherActivity - 通知点击跳板（无 UI 透明页）
 *
 * 驻留通知被点击后进入这里：
 * 1. 已有悬浮窗权限（SYSTEM_ALERT_WINDOW）-> 直接拉起 FloatingBrowserService
 * 2. 没有权限 -> 跳转系统「显示在其他应用上层」授权页，
 *    授权成功返回后自动继续拉起；未授权则提示后退出
 *
 * 透明主题 + excludeFromRecents + noHistory：
 * 用户全程无感知，不会出现在最近任务里。
 */
class FloatingLauncherActivity : Activity() {

    companion object {
        private const val REQ_OVERLAY = 0xF1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WuyingLog.i("FloatingLauncher", "通知点击进入，canDrawOverlays=${Settings.canDrawOverlays(this)}")
        if (Settings.canDrawOverlays(this)) {
            launchFloating()
        } else {
            Toast.makeText(this, R.string.floating_permission_needed, Toast.LENGTH_LONG).show()
            try {
                startActivityForResult(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    ),
                    REQ_OVERLAY
                )
            } catch (t: Throwable) {
                // 个别魔改 ROM 没有这个页面
                WuyingLog.e("FloatingLauncher", "跳转授权页失败", t)
                try {
                    startActivityForResult(Intent(Settings.ACTION_SETTINGS), REQ_OVERLAY)
                } catch (_: Throwable) {
                    finish()
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OVERLAY) {
            if (Settings.canDrawOverlays(this)) {
                Toast.makeText(this, R.string.floating_permission_granted, Toast.LENGTH_SHORT).show()
                launchFloating()
            } else {
                Toast.makeText(this, R.string.floating_permission_denied, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun launchFloating() {
        FloatingBrowserService.start(this, intent?.getStringExtra("url"))
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        WuyingLog.d("FloatingLauncher", "跳板退出")
    }
}
