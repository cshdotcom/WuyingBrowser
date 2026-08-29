package com.wuying.browser.ui

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.widget.FrameLayout

/**
 * FloatingRootView - 悬浮面板根布局
 *
 * 独立顶层类（供 XML 反射实例化），由 [FloatingBrowserActivity] 的
 * floating_browser.xml 引用（com.wuying.browser.ui.FloatingRootView）。
 *
 * 面板挂在全屏透明 Activity 里：根容器负责「点击面板外 = 最小化」，
 * 面板自身必须消费触摸事件防止冒泡到根容器。
 * 面板可聚焦，有几率收到 BACK 键 —— 在这里统一拦截并回调给 Activity。
 */
class FloatingRootView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    init {
        // 按背景的圆角 outline 裁剪子视图，防止标题栏直角溢出圆角面板
        clipToOutline = true
        // 消费触摸：面板内点击不触发外层容器（根容器点击 = 最小化）
        isClickable = true
        isFocusable = true
        isFocusableInTouchMode = true
    }

    var onBackRequested: (() -> Unit)? = null

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK &&
            event.action == KeyEvent.ACTION_DOWN &&
            event.repeatCount == 0
        ) {
            val handled = onBackRequested
            if (handled != null) {
                handled()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
