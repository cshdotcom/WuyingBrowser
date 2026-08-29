package com.wuying.browser.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.wuying.browser.R
import com.wuying.browser.data.DownloadManagerHelper
import com.wuying.browser.data.PreferenceManager
import com.wuying.browser.data.SessionManager
import com.wuying.browser.util.WuyingLog
import com.wuying.browser.web.WuyingWebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * FloatingRootView - 悬浮窗根布局
 *
 * 独立顶层类（供 XML 反射实例化）。
 * 悬浮窗窗口是 focusable 的（否则地址栏/网页无法输入），
 * 因而有几率收到 BACK 键 —— 在这里统一拦截并回调给 Service。
 */
class FloatingRootView @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null
) : FrameLayout(context, attrs) {

    init {
        // 按背景的圆角 outline 裁剪子视图，防止标题栏直角溢出圆角面板
        clipToOutline = true
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

/**
 * FloatingBrowserService - 悬浮窗版浏览器
 *
 * 通过 WindowManager 在所有应用之上绘制一个可拖动的小窗：
 * 1. 窗口内是完整的 WebView（复用 WuyingWebView：广告拦截 / 偏好设置全生效）
 * 2. 标题栏可拖动；支持放大/缩小两档尺寸
 * 3. 可收起为一个小圆点（气泡），点小圆点恢复窗口
 * 4. 返回键：网页能后退就后退，否则收起为小圆点
 * 5. 与主浏览器共用同一进程数据目录：Cookie / 缓存 / 历史完全互通
 *
 * 权限：需要 SYSTEM_ALERT_WINDOW（由 FloatingLauncherActivity 引导授权）
 */
class FloatingBrowserService : android.app.Service() {

    companion object {
        const val ACTION_SHOW = "com.wuying.browser.floating.SHOW"
        const val EXTRA_URL = "com.wuying.browser.floating.EXTRA_URL"

        /** 大窗尺寸（占屏幕比例） */
        private const val BIG_W = 0.92f
        private const val BIG_H = 0.62f
        /** 小窗尺寸 */
        private const val SMALL_W = 0.60f
        private const val SMALL_H = 0.42f
        /** 拖动超过该距离(px)视为移动而非点击 */
        private const val TOUCH_SLOP = 12

        fun start(context: Context, url: String? = null) {
            val i = Intent(context, FloatingBrowserService::class.java).apply {
                action = ACTION_SHOW
                url?.let { putExtra(EXTRA_URL, it) }
            }
            try {
                context.startService(i)
            } catch (t: Throwable) {
                WuyingLog.e("Floating", "拉起悬浮窗服务失败", t)
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var windowManager: WindowManager? = null
    private var rootView: FloatingRootView? = null
    private var bubbleView: View? = null
    private var webView: WuyingWebView? = null
    private var urlInput: EditText? = null
    private var pageTitle: TextView? = null
    private var progressBar: ProgressBar? = null

    private var panelParams: WindowManager.LayoutParams? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    private var isPanelShown = false
    private var isBigSize = true
    private var pendingUrl: String? = null

    private val screenWidth: Int
        get() = resources.displayMetrics.widthPixels
    private val screenHeight: Int
        get() = resources.displayMetrics.heightPixels

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        WuyingLog.i("Floating", "FloatingBrowserService onCreate")
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        setupPanel()
        initialUrl { url -> loadInWebView(url) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            // 无悬浮窗权限，直接结束，避免闪退
            WuyingLog.w("Floating", "无悬浮窗权限，服务退出")
            stopSelf()
            return START_NOT_STICKY
        }
        val url = intent?.getStringExtra(EXTRA_URL)
        when (intent?.action) {
            ACTION_SHOW -> {
                if (!isPanelShown) {
                    showPanel()
                } else if (url != null) {
                    loadInWebView(url)
                }
            }
        }
        return START_NOT_STICKY
    }

    // ============================== 面板 ==============================

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun setupPanel() {
        val root = LayoutInflater.from(this)
            .inflate(R.layout.floating_browser, null) as FloatingRootView
        rootView = root

        webView = WuyingWebView(this).apply {
            applySettings()
            setBackgroundColor(resources.getColor(R.color.bg_primary, theme))
            // 进度 / 地址 / 标题回调
            onProgressChanged = { p ->
                progressBar?.let { bar ->
                    if (p in 1..99) { bar.visibility = View.VISIBLE; bar.progress = p }
                    else bar.visibility = View.GONE
                }
            }
            onUrlChanged = { url ->
                urlInput?.takeIf { !it.hasFocus() }?.setText(url)
            }
            onTitleChanged = { t -> pageTitle?.text = t }
            setDownloadListener { u, cd, mime, _, _ ->
                scope.launch {
                    try {
                        DownloadManagerHelper.get(this@FloatingBrowserService)
                            .startDownload(u, cd, mime)
                        Toast.makeText(applicationContext, R.string.download_started, Toast.LENGTH_SHORT).show()
                    } catch (t: Throwable) {
                        WuyingLog.e("Floating", "悬浮窗下载失败", t)
                    }
                }
            }
        }

        // WebView 加入内容区（weight=1 撑满）
        root.findViewById<LinearLayout>(R.id.floating_web_container).addView(
            webView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        )

        urlInput = root.findViewById(R.id.floating_url_input)
        pageTitle = root.findViewById(R.id.floating_page_title)
        progressBar = root.findViewById(R.id.floating_progress)

        // 地址栏：键盘 GO 键触发
        urlInput?.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                navigateTo(v.text.toString())
                true
            } else false
        }

        // 工具栏按钮
        root.findViewById<ImageButton>(R.id.btn_f_back).setOnClickListener { webView?.goBack() }
        root.findViewById<ImageButton>(R.id.btn_f_forward).setOnClickListener { webView?.goForward() }
        root.findViewById<ImageButton>(R.id.btn_f_refresh).setOnClickListener { webView?.reload() }
        root.findViewById<ImageButton>(R.id.btn_f_size).setOnClickListener { toggleSize() }
        root.findViewById<ImageButton>(R.id.btn_f_min).setOnClickListener { showBubble() }
        root.findViewById<ImageButton>(R.id.btn_f_close).setOnClickListener { stopSelf() }

        // 返回键
        root.onBackRequested = {
            val wv = webView
            when {
                wv != null && wv.canGoBack() -> wv.goBack()
                else -> showBubble()
            }
        }

        // 标题栏拖动
        val handle = root.findViewById<View>(R.id.floating_drag_handle)
        handle.setOnTouchListener(dragListener(isBubble = false))

        // 创建布局参数（大窗）
        panelParams = buildPanelParams()
    }

    private fun buildPanelParams(): WindowManager.LayoutParams {
        val w = (screenWidth * BIG_W).toInt()
        val h = (screenHeight * BIG_H).toInt()
        return WindowManager.LayoutParams(
            w, h,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenWidth * 0.04f).toInt()
            y = (screenHeight * 0.18f).toInt()
        }
    }

    /** Android O 以下用 TYPE_PHONE，O 及以上用 TYPE_APPLICATION_OVERLAY */
    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun showPanel() {
        val wm = windowManager ?: return
        val root = rootView ?: return
        try {
            if (root.parent == null) wm.addView(root, panelParams)
            isPanelShown = true
            pendingUrl?.let { loadInWebView(it); pendingUrl = null }
        } catch (t: Throwable) {
            WuyingLog.e("Floating", "showPanel 失败", t)
        }
    }

    /** 大小两档切换 */
    private fun toggleSize() {
        val wm = windowManager ?: return
        val root = rootView ?: return
        val params = panelParams ?: return
        isBigSize = !isBigSize
        val wRatio = if (isBigSize) BIG_W else SMALL_W
        val hRatio = if (isBigSize) BIG_H else SMALL_H
        params.width = (screenWidth * wRatio).toInt()
        params.height = (screenHeight * hRatio).toInt()
        clamp(params, params.width, params.height)
        try { wm.updateViewLayout(root, params) } catch (_: Throwable) {}
    }

    // ============================== 气泡 ==============================

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        val wm = windowManager ?: return
        val root = rootView ?: return
        try {
            if (root.parent != null) wm.removeView(root)
            isPanelShown = false
        } catch (_: Throwable) {}

        if (bubbleView == null) {
            val bubble = ImageView(this).apply {
                setImageResource(R.drawable.ic_wuying_notif)
                setBackgroundResource(R.drawable.bg_floating_bubble)
                imageAlpha = 235
                contentDescription = getString(R.string.floating_open)
            }
            bubbleView = bubble
            bubble.setOnTouchListener(dragListener(isBubble = true))
        }
        val b = bubbleView ?: return
        val size = (48 * resources.displayMetrics.density).toInt()
        if (bubbleParams == null) {
            bubbleParams = WindowManager.LayoutParams(
                size, size,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = screenWidth - size
                y = screenHeight / 3
            }
        }
        try {
            if (b.parent == null) wm.addView(b, bubbleParams)
        } catch (t: Throwable) {
            WuyingLog.e("Floating", "showBubble 失败", t)
        }
    }

    // ============================== 拖动 ==============================

    /**
     * 统一的拖动/点击监听：
     * - 面板：拖动标题栏移动窗口
     * - 气泡：拖动移动 + 松手贴边；单击恢复面板
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun dragListener(isBubble: Boolean) = View.OnTouchListener { v, event ->
        val params = (if (isBubble) bubbleParams else panelParams) ?: return@OnTouchListener false
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
                val w = if (isBubble) v.layoutParams.width else (panelParams?.width ?: 0)
                val h = if (isBubble) v.layoutParams.height else (panelParams?.height ?: 0)
                clamp(params, w, h)
                try { wm.updateViewLayout(if (isBubble) v else rootView, params) } catch (_: Throwable) {}
                true
            }
            MotionEvent.ACTION_UP -> {
                val st = v.tag as? FloatArray
                val moved = st?.get(4)?.toInt() ?: 0
                if (isBubble) {
                    // 松手贴边
                    val size = v.layoutParams.width
                    params.x = if (params.x + size / 2 > screenWidth / 2)
                        screenWidth - size else 0
                    try { wm.updateViewLayout(v, params) } catch (_: Throwable) {}
                    // 单击恢复
                    if (moved < TOUCH_SLOP) {
                        try { wm.removeView(v) } catch (_: Throwable) {}
                        showPanel()
                    }
                }
                true
            }
            else -> false
        }
    }

    /** 把窗口坐标限制在屏幕范围内 */
    private fun clamp(params: WindowManager.LayoutParams, w: Int, h: Int) {
        params.x = min(max(params.x, -w / 4), screenWidth - w / 4)
        params.y = min(max(params.y, 0), screenHeight - (h * 2 / 5))
    }

    // ============================== 网页加载 ==============================

    private fun initialUrl(onReady: (String) -> Unit) {
        // Service 没有 intent 属性；URL 由 onStartCommand(ACTION_SHOW) 通过 pendingUrl 传递
        val fromIntent = pendingUrl
        if (fromIntent != null) {
            pendingUrl = fromIntent
            onReady(fromIntent)
            return
        }
        // 无显式 URL：恢复上次会话的第一个标签页，否则首页
        scope.launch {
            val url = try {
                SessionManager.get(this@FloatingBrowserService).loadTabs()
                    .firstOrNull { it.url.isNotBlank() }?.url
            } catch (_: Throwable) { null }
            val target = url
                ?: PreferenceManager.get(PreferenceManager.KEY_HOME_PAGE, "https://www.bing.com")
            onReady(target)
        }
    }

    private fun loadInWebView(url: String) {
        pendingUrl = url
        val wv = webView ?: return
        if (!isPanelShown) return // 面板显示时会通过 pendingUrl 补加载
        wv.loadUrl(url)
        urlInput?.setText(url)
    }

    /** 地址栏输入 -> 规范化 URL / 搜索 */
    private fun navigateTo(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        val url = normalizeUrl(text)
        hideKeyboard()
        loadInWebView(url)
    }

    private fun normalizeUrl(text: String): String {
        if (text.startsWith("http://") || text.startsWith("https://")) return text
        // 看起来像域名就直接访问，否则走搜索引擎
        return if (!text.contains(" ") && text.contains(".")) {
            "https://$text"
        } else {
            val pattern = PreferenceManager.get(
                PreferenceManager.KEY_SEARCH_ENGINE, "https://www.bing.com/search?q=%s"
            )
            pattern.replace("%s", URLEncoder.encode(text, "UTF-8"))
        }
    }

    private fun hideKeyboard() {
        val input = urlInput ?: return
        try {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(input.windowToken, 0)
            input.clearFocus()
        } catch (_: Throwable) {}
    }

    // ============================== 生命周期 ==============================

    override fun onDestroy() {
        super.onDestroy()
        try {
            CookieManager.getInstance().flush()
        } catch (_: Throwable) {}
        val wm = windowManager
        rootView?.let { try { wm?.removeView(it) } catch (_: Throwable) {} }
        bubbleView?.let { try { wm?.removeView(it) } catch (_: Throwable) {} }
        webView?.destroy()
        webView = null
        rootView = null
        bubbleView = null
        isPanelShown = false
        scope.cancel()
        WuyingLog.w("Floating", "FloatingBrowserService onDestroy")
    }
}
