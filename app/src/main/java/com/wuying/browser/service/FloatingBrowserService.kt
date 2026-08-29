package com.wuying.browser.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import com.wuying.browser.R
import com.wuying.browser.ui.FloatingBrowserActivity
import com.wuying.browser.util.WuyingLog
import kotlin.math.abs
import kotlin.math.max

/**
 * FloatingBrowserService - 悬浮球服务
 *
 * v1.3.0 重构：浏览器面板已迁移到 [FloatingBrowserActivity]
 * （全屏透明 Activity —— 标准应用窗口，输入法交互正常）。
 * 本服务只负责「最小化悬浮球」：
 * - 悬浮球是 overlay 窗口（FLAG_NOT_FOCUSABLE），没有文本输入需求，
 *   不存在 overlay 窗口唤不起输入法的问题
 * - 点悬浮球 -> 重新拉起 FloatingBrowserActivity 恢复面板；
 *   应用持有 SYSTEM_ALERT_WINDOW，按官方文档豁免后台 Activity 启动限制
 * - 面板显示期间悬浮球收起（由面板 onCreate / 最小化流程驱动）
 */
class FloatingBrowserService : Service() {

    companion object {
        const val ACTION_SHOW_BUBBLE = "com.wuying.browser.floating.SHOW_BUBBLE"
        const val ACTION_HIDE_BUBBLE = "com.wuying.browser.floating.HIDE_BUBBLE"

        /** 拖动超过该距离(px)视为移动而非点击 */
        private const val TOUCH_SLOP = 12

        fun showBubble(context: Context) {
            val i = Intent(context, FloatingBrowserService::class.java).apply {
                action = ACTION_SHOW_BUBBLE
            }
            try {
                context.startService(i)
            } catch (t: Throwable) {
                WuyingLog.e("Floating", "拉起悬浮球服务失败", t)
            }
        }

        fun hideBubble(context: Context) {
            val i = Intent(context, FloatingBrowserService::class.java).apply {
                action = ACTION_HIDE_BUBBLE
            }
            try {
                context.startService(i)
            } catch (_: Throwable) {}
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, FloatingBrowserService::class.java))
            } catch (_: Throwable) {}
        }
    }

    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    private val screenWidth: Int get() = resources.displayMetrics.widthPixels
    private val screenHeight: Int get() = resources.displayMetrics.heightPixels

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        WuyingLog.i("Floating", "FloatingBrowserService onCreate")
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_BUBBLE -> addBubble()
            ACTION_HIDE_BUBBLE -> {
                removeBubble()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    // ============================== 悬浮球 ==============================

    @SuppressLint("ClickableViewAccessibility")
    private fun addBubble() {
        if (!Settings.canDrawOverlays(this)) {
            // 无悬浮窗权限，球加不出来
            WuyingLog.w("Floating", "无悬浮窗权限，悬浮球退出")
            stopSelf()
            return
        }
        if (bubbleView == null) {
            val bubble = ImageView(this).apply {
                setImageResource(R.drawable.ic_wuying_notif)
                setBackgroundResource(R.drawable.bg_floating_bubble)
                imageAlpha = 235
                contentDescription = getString(R.string.floating_open)
            }
            bubbleView = bubble
            bubble.setOnTouchListener(bubbleTouchListener())
        }
        val b = bubbleView ?: return
        if (b.parent != null) return // 已显示

        if (bubbleParams == null) {
            val size = (48 * resources.displayMetrics.density).toInt()
            bubbleParams = WindowManager.LayoutParams(
                size, size,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = screenWidth - size
                y = screenHeight / 3
            }
        }
        try {
            windowManager?.addView(b, bubbleParams)
        } catch (t: Throwable) {
            WuyingLog.e("Floating", "addBubble 失败", t)
        }
    }

    private fun removeBubble() {
        val b = bubbleView ?: return
        try {
            if (b.parent != null) windowManager?.removeView(b)
        } catch (_: Throwable) {}
    }

    /** Android O 以下用 TYPE_PHONE，O 及以上用 TYPE_APPLICATION_OVERLAY */
    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    /**
     * 悬浮球触摸：拖动移动 + 松手贴边；单击（位移小于 slop）恢复面板。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun bubbleTouchListener() = View.OnTouchListener { v, event ->
        val params = bubbleParams ?: return@OnTouchListener false
        val wm = windowManager ?: return@OnTouchListener false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                v.tag = floatArrayOf(
                    event.rawX, event.rawY,
                    params.x.toFloat(), params.y.toFloat(), 0f
                )
                false
            }
            MotionEvent.ACTION_MOVE -> {
                val st = v.tag as? FloatArray ?: return@OnTouchListener false
                val dx = event.rawX - st[0]
                val dy = event.rawY - st[1]
                st[4] = max(st[4], abs(dx) + abs(dy))
                params.x = (st[2] + dx).toInt()
                params.y = (st[3] + dy).toInt()
                val w = v.layoutParams.width
                val h = v.layoutParams.height
                // 把悬浮球坐标限制在屏幕范围内
                params.x = max(min(params.x, screenWidth - w / 4), -w * 3 / 4)
                params.y = max(min(params.y, screenHeight - h / 4), -h / 4)
                try { wm.updateViewLayout(v, params) } catch (_: Throwable) {}
                true
            }
            MotionEvent.ACTION_UP -> {
                val st = v.tag as? FloatArray
                val moved = st?.get(4)?.toInt() ?: 0
                // 松手贴边
                val size = v.layoutParams.width
                params.x = if (params.x + size / 2 > screenWidth / 2)
                    screenWidth - size else 0
                try { wm.updateViewLayout(v, params) } catch (_: Throwable) {}
                // 单击恢复面板
                if (moved < TOUCH_SLOP) {
                    removeBubble()
                    // 应用持有 SYSTEM_ALERT_WINDOW -> 豁免后台 Activity 启动限制
                    FloatingBrowserActivity.start(this@FloatingBrowserService, null)
                    stopSelf()
                }
                true
            }
            else -> false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        removeBubble()
        bubbleView = null
        bubbleParams = null
        WuyingLog.w("Floating", "FloatingBrowserService onDestroy")
    }
}
