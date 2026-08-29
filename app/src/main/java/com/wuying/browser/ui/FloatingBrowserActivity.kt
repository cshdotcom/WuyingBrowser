package com.wuying.browser.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.wuying.browser.R
import com.wuying.browser.data.DownloadManagerHelper
import com.wuying.browser.data.PreferenceManager
import com.wuying.browser.data.SessionManager
import com.wuying.browser.service.FloatingBrowserService
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
 * FloatingBrowserActivity - 悬浮窗浏览器面板（全屏透明 Activity 实现）
 *
 * 为什么 v1.3.0 放弃 WindowManager overlay 改用透明 Activity？
 * - v1.1.0：可聚焦 TYPE_APPLICATION_OVERLAY 窗口，实测网页输入框无法唤起输入法
 * - v1.2.0：加 FLAG_ALT_FOCUSABLE_IM（用法有误，可聚焦窗口加该标志反而禁止 IME），
 *   实测依旧无法唤起。TYPE_APPLICATION_OVERLAY 在大量国产 ROM 上即使窗口可聚焦，
 *   系统也不会为其弹出输入法 —— 属于 ROM 级限制，无解。
 * - 透明 Activity 是标准应用窗口，输入法交互与主浏览器完全一致，100% 可输入。
 *   业界同类"悬浮浏览器"产品普遍采用此方案。
 *
 * 悬浮体验的保留方式：
 * - 全屏透明背景 + 居中悬浮面板，视觉与 overlay 版几乎一致
 * - 面板可拖动 / 两档大小 / 最小化为悬浮球（悬浮球仍由 FloatingBrowserService
 *   以 overlay 实现 —— 悬浮球无输入需求，不存在 IME 问题）
 * - 点击面板外空白区域 = 最小化
 * - 按 Home / 切到其他应用（onUserLeaveHint）时自动收成悬浮球，"常驻漂浮"不丢失
 * - 悬浮球点击后通过 startActivity 恢复面板：应用持有 SYSTEM_ALERT_WINDOW，
 *   依据官方文档豁免后台 Activity 启动限制（BAL）
 *
 * 通知点击直达本 Activity（无跳板、无 Service 中转），任何 ROM 上都是
 * 标准启动路径 —— 根治"点通知打开应用/没反应"的问题。
 */
class FloatingBrowserActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "com.wuying.browser.floating.EXTRA_URL"

        /** 大窗尺寸（占屏幕比例） */
        private const val BIG_W = 0.92f
        private const val BIG_H = 0.62f
        /** 小窗尺寸 */
        private const val SMALL_W = 0.60f
        private const val SMALL_H = 0.42f
        /** 自由缩放下限（dp，v1.3.1 右下角缩放把手） */
        private const val MIN_W_DP = 220f
        private const val MIN_H_DP = 170f

        fun start(context: Context, url: String? = null) {
            val i = Intent(context, FloatingBrowserActivity::class.java).apply {
                // Service / 通知等非 Activity 场景必须带 NEW_TASK
                if (context !is android.app.Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                putExtra(EXTRA_URL, url)
            }
            try {
                context.startActivity(i)
            } catch (t: Throwable) {
                WuyingLog.e("FloatingAct", "拉起悬浮面板失败", t)
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var rootContainer: FrameLayout
    private var panel: FloatingRootView? = null
    private var webView: WuyingWebView? = null
    private var urlInput: EditText? = null
    private var pageTitle: TextView? = null
    private var progressBar: ProgressBar? = null
    private var panelLp: FrameLayout.LayoutParams? = null

    private var isBigSize = true
    /** 键盘避让前的面板位置/高度（键盘收起时恢复到用户拖出来的位置） */
    private var baseTopMargin = 0
    private var baseHeight = 0
    private var imeAvoiding = false

    private val screenWidth: Int get() = resources.displayMetrics.widthPixels
    private val screenHeight: Int get() = resources.displayMetrics.heightPixels
    private val density: Float get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WuyingLog.i("FloatingAct", "onCreate url=${intent?.getStringExtra(EXTRA_URL)}")
        rootContainer = FrameLayout(this)
        setContentView(rootContainer)

        setupPanel()

        // 点击面板外空白 = 最小化（面板自身 isClickable=true 已消费内部点击）
        rootContainer.setOnClickListener { minimize() }

        setupImeAvoidance()
        registerBackHandler()

        restoreSession()

        // 面板显示期间收掉悬浮球（若挂着）
        FloatingBrowserService.hideBubble(this)
    }

    // androidx.activity 1.8+ 的 ComponentActivity 是 Kotlin 类，onNewIntent 签名为非空 Intent，
    // override 必须用 Intent（可空签名会导致 "parameter is not a subtype" 编译错误）
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // singleTask 复用实例：通知/悬浮球再次点击时带上新 URL
        val url = intent.getStringExtra(EXTRA_URL)
        if (!url.isNullOrBlank()) loadInWebView(url)
    }

    // ============================== 面板搭建 ==============================

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun setupPanel() {
        val p = layoutInflater.inflate(
            R.layout.floating_browser, rootContainer, false
        ) as FloatingRootView
        panel = p

        webView = WuyingWebView(this).apply {
            applySettings()
            setBackgroundColor(resources.getColor(R.color.bg_primary, theme))
            onProgressChanged = { pr ->
                progressBar?.let { bar ->
                    if (pr in 1..99) { bar.visibility = View.VISIBLE; bar.progress = pr }
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
                        DownloadManagerHelper.get(applicationContext)
                            .startDownload(u, cd, mime)
                        Toast.makeText(applicationContext, R.string.download_started, Toast.LENGTH_SHORT).show()
                    } catch (t: Throwable) {
                        WuyingLog.e("FloatingAct", "悬浮窗下载失败", t)
                    }
                }
            }
        }

        // WebView 加入内容区（weight=1 撑满）
        p.findViewById<LinearLayout>(R.id.floating_web_container).addView(
            webView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        )

        urlInput = p.findViewById(R.id.floating_url_input)
        pageTitle = p.findViewById(R.id.floating_page_title)
        progressBar = p.findViewById(R.id.floating_progress)

        // 地址栏：键盘 GO 键触发
        urlInput?.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                navigateTo(v.text.toString())
                true
            } else false
        }

        // 工具栏按钮
        p.findViewById<ImageButton>(R.id.btn_f_back).setOnClickListener { webView?.goBack() }
        p.findViewById<ImageButton>(R.id.btn_f_forward).setOnClickListener { webView?.goForward() }
        p.findViewById<ImageButton>(R.id.btn_f_refresh).setOnClickListener { webView?.reload() }
        p.findViewById<ImageButton>(R.id.btn_f_ime).setOnClickListener { toggleIme() }
        p.findViewById<ImageButton>(R.id.btn_f_size).setOnClickListener { toggleSize() }
        p.findViewById<ImageButton>(R.id.btn_f_min).setOnClickListener { minimize() }
        p.findViewById<ImageButton>(R.id.btn_f_close).setOnClickListener { closeAll() }

        // BACK 键（FloatingRootView 拦截回调）：网页能后退就后退，否则最小化
        p.onBackRequested = {
            val wv = webView
            if (wv != null && wv.canGoBack()) wv.goBack() else minimize()
        }

        // 标题栏拖动
        p.findViewById<View>(R.id.floating_drag_handle).setOnTouchListener(dragListener())

        // 右下角缩放把手：按住拖拽任意调整面板大小（v1.3.1）
        p.findViewById<View>(R.id.floating_resize_grip).setOnTouchListener(resizeListener())

        // 面板定位：大窗，初始位置在屏幕上部偏左
        panelLp = FrameLayout.LayoutParams(
            (screenWidth * BIG_W).toInt(),
            (screenHeight * BIG_H).toInt(),
            Gravity.TOP or Gravity.START
        ).apply {
            leftMargin = (screenWidth * 0.04f).toInt()
            topMargin = (screenHeight * 0.12f).toInt()
        }
        baseTopMargin = panelLp!!.topMargin
        baseHeight = panelLp!!.height

        rootContainer.addView(p, panelLp)
    }

    /** 标题栏拖动：修改面板 margin（与 overlay 版手感一致） */
    private fun dragListener() = View.OnTouchListener { v, event ->
        val lp = panelLp ?: return@OnTouchListener false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                v.tag = floatArrayOf(
                    event.rawX, event.rawY,
                    lp.leftMargin.toFloat(), lp.topMargin.toFloat(), 0f
                )
                // DOWN 必须返回 true 占住触摸流，否则后续 MOVE 永远收不到（v1.3.0 拖动失效的根因）
                v.parent?.requestDisallowInterceptTouchEvent(true)
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val st = v.tag as? FloatArray ?: return@OnTouchListener false
                val dx = event.rawX - st[0]
                val dy = event.rawY - st[1]
                st[4] = max(st[4], abs(dx) + abs(dy))
                lp.leftMargin = (st[2] + dx).toInt()
                lp.topMargin = (st[3] + dy).toInt()
                clampPanel(lp)
                if (!imeAvoiding) baseTopMargin = lp.topMargin
                panel?.requestLayout()
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.parent?.requestDisallowInterceptTouchEvent(false)
                true
            }
            else -> false
        }
    }

    /**
     * 右下角缩放把手（v1.3.1）：以面板左上角为锚点，手指位置即新的右下角。
     * 与拖动同理：DOWN 必须返回 true 占住触摸流，后续 MOVE 才会送达。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun resizeListener() = View.OnTouchListener { v, event ->
        val lp = panelLp ?: return@OnTouchListener false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val loc = IntArray(2)
                panel?.getLocationOnScreen(loc)
                // 缩放过程只改宽高，左上角不动 —— DOWN 时记下面板位置即可作全程锚点
                v.tag = floatArrayOf(loc[0].toFloat(), loc[1].toFloat())
                v.parent?.requestDisallowInterceptTouchEvent(true)
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val st = v.tag as? FloatArray ?: return@OnTouchListener false
                val minW = (MIN_W_DP * density).toInt()
                val minH = (MIN_H_DP * density).toInt()
                val maxW = screenWidth - (8 * density).toInt()
                val maxH = screenHeight - (72 * density).toInt()
                lp.width = (event.rawX - st[0]).toInt().coerceIn(minW, maxW)
                lp.height = (event.rawY - st[1]).toInt().coerceIn(minH, maxH)
                clampPanel(lp)
                panel?.requestLayout()
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.parent?.requestDisallowInterceptTouchEvent(false)
                if (!imeAvoiding) {
                    baseTopMargin = lp.topMargin
                    baseHeight = lp.height
                }
                // 让两档切换按钮与实际大小保持一致语义
                isBigSize = lp.width >= screenWidth * ((BIG_W + SMALL_W) / 2)
                true
            }
            else -> false
        }
    }

    /** 把面板位置限制在屏幕范围内 */
    private fun clampPanel(lp: FrameLayout.LayoutParams) {
        lp.leftMargin = min(max(lp.leftMargin, -lp.width / 4), screenWidth - lp.width / 4)
        lp.topMargin = min(max(lp.topMargin, 0), screenHeight - lp.height * 2 / 5)
    }

    /** 大小两档切换 */
    private fun toggleSize() {
        val lp = panelLp ?: return
        isBigSize = !isBigSize
        val wRatio = if (isBigSize) BIG_W else SMALL_W
        val hRatio = if (isBigSize) BIG_H else SMALL_H
        lp.width = (screenWidth * wRatio).toInt()
        lp.height = (screenHeight * hRatio).toInt()
        clampPanel(lp)
        baseTopMargin = lp.topMargin
        baseHeight = lp.height
        panel?.requestLayout()
    }

    /** 手动唤起/收起输入法（工具栏 ⌨ 按钮兜底） */
    private fun toggleIme() {
        val target = currentFocus ?: webView ?: urlInput ?: return
        try {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.toggleSoftInputFromWindow(target.applicationWindowToken, 0, 0)
        } catch (t: Throwable) {
            WuyingLog.e("FloatingAct", "切换输入法失败", t)
        }
    }

    // ============================== 键盘避让 ==============================

    /**
     * 输入法弹出时把面板整体上移（必要时压缩高度），
     * 让网页输入框 / 地址栏始终可见 —— WebView 的 scrollIntoView 也因此生效。
     */
    private fun setupImeAvoidance() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // API 30+：系统级 IME insets，精确可靠
            rootContainer.setOnApplyWindowInsetsListener { v, insets ->
                val ime = insets.getInsets(android.view.WindowInsets.Type.ime())
                applyImeAvoid(ime.bottom)
                insets
            }
        } else {
            // API 21-29：可见显示区高度变化推断键盘（透明窗口同样适用）
            rootContainer.viewTreeObserver.addOnGlobalLayoutListener {
                val r = Rect()
                rootContainer.getWindowVisibleDisplayFrame(r)
                val keyboardH = screenHeight - r.bottom
                applyImeAvoid(if (keyboardH > screenHeight / 4) keyboardH else 0)
            }
        }
    }

    private fun applyImeAvoid(imeBottom: Int) {
        val lp = panelLp ?: return
        if (imeBottom <= screenHeight / 4) {
            // 键盘不可见：恢复用户拖出来的基础位置
            if (imeAvoiding) {
                imeAvoiding = false
                lp.topMargin = baseTopMargin
                lp.height = baseHeight
                panel?.requestLayout()
            }
            return
        }
        val availBottom = screenHeight - imeBottom - (8 * density).toInt()
        val panelBottom = lp.topMargin + lp.height
        if (panelBottom > availBottom) {
            imeAvoiding = true
            var newTop = availBottom - lp.height
            val minTop = (40 * density).toInt()
            if (newTop < minTop) {
                // 面板比可用区还高：压缩到小窗下限
                lp.height = max(availBottom - minTop, (screenHeight * SMALL_H).toInt())
                newTop = availBottom - lp.height
            }
            lp.topMargin = max(newTop, minTop)
            panel?.requestLayout()
        }
    }

    // ============================== 会话与网页 ==============================

    private fun restoreSession() {
        val fromIntent = intent?.getStringExtra(EXTRA_URL)
        if (!fromIntent.isNullOrBlank()) {
            loadInWebView(fromIntent)
            return
        }
        // 无显式 URL：恢复上次会话的第一个标签页，否则首页
        scope.launch {
            val url = try {
                SessionManager.get(this@FloatingBrowserActivity).loadTabs()
                    .firstOrNull { it.url.isNotBlank() }?.url
            } catch (_: Throwable) { null }
            val target = url
                ?: PreferenceManager.get(PreferenceManager.KEY_HOME_PAGE, "https://www.bing.com")
            loadInWebView(target)
        }
    }

    private fun loadInWebView(url: String) {
        if (url.isBlank()) return
        webView?.loadUrl(url)
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

    // ============================== 最小化 / 关闭 ==============================

    private fun hideKeyboard() {
        try {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            currentFocus?.windowToken?.let { imm.hideSoftInputFromWindow(it, 0) }
        } catch (_: Throwable) {}
    }

    /**
     * 最小化：收成悬浮球（overlay，无输入需求）后退出透明 Activity。
     * 无悬浮窗权限时球加不出来，提示用户点通知重新打开。
     */
    private fun minimize() {
        hideKeyboard()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            android.provider.Settings.canDrawOverlays(this)
        ) {
            FloatingBrowserService.showBubble(this)
        } else {
            Toast.makeText(this, R.string.floating_permission_needed, Toast.LENGTH_SHORT).show()
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    /** 彻底关闭：悬浮球 + 面板一起退出 */
    private fun closeAll() {
        hideKeyboard()
        FloatingBrowserService.stop(this)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    /**
     * 用户按 Home / 最近任务离开时自动收成悬浮球，
     * 保持 v1.1/v1.2 overlay 版「Home 后悬浮窗仍在」的体验；
     * 回到应用时点球或点通知即可恢复。跳转其他 Activity（分享/下载选择器）
     * 不触发此回调，面板不会被误收。
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!isFinishing) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                android.provider.Settings.canDrawOverlays(this)
            ) {
                FloatingBrowserService.showBubble(this)
            }
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    /** 返回键：网页能后退就后退，否则最小化（API 33+ predictive back 由 androidx 桥接） */
    private fun registerBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val wv = webView
                if (wv != null && wv.canGoBack()) wv.goBack() else minimize()
            }
        })
    }

    // ============================== 生命周期 ==============================

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        webView?.destroy()
        webView = null
        panel = null
        urlInput = null
        pageTitle = null
        progressBar = null
        WuyingLog.i("FloatingAct", "onDestroy")
    }
}
