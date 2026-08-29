package com.wuying.browser.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.wuying.browser.BrowserApplication
import com.wuying.browser.R
import com.wuying.browser.data.HistoryManager
import com.wuying.browser.data.PreferenceManager
import com.wuying.browser.data.SessionManager
import com.wuying.browser.databinding.ActivityBrowserBinding
import com.wuying.browser.service.CoreService
import com.wuying.browser.util.WuyingLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 浏览器主界面
 *
 * 关键行为：
 * 1. **最近任务隐藏**：在 AndroidManifest 已配置 excludeFromRecents=true
 *    退出时 finish() 即可，进程仍在 :core 中跑
 * 2. **会话恢复**：onCreate 时从 SessionManager 加载上次的标签页 URL
 * 3. **退出隐藏而非销毁**：返回键调用 moveTaskToBack 让 Activity 隐藏到后台
 * 4. **全局快捷键唤出**：CoreService 通过通知或者广播唤起
 */
class BrowserActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBrowserBinding
    private lateinit var tabsManager: TabsManager
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    /**
     * 文件选择回调
     */
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val results: Array<Uri>? = if (result.resultCode == Activity.RESULT_OK) {
            result.data?.let { data ->
                if (data.clipData != null) {
                    val count = data.clipData!!.itemCount
                    Array(count) { i -> data.clipData!!.getItemAt(i).uri }
                } else if (data.data != null) {
                    arrayOf(data.data!!)
                } else null
            }
        } else null
        filePathCallback?.onReceiveValue(results)
        filePathCallback = null
    }

    /**
     * 权限请求
     */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> /* 结果由 WebView 自己处理 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 关键：在 setContentView 前请求无标题栏
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        binding = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 沉浸式
        WindowCompat.setDecorFitsSystemWindows(window, true)

        // 初始化标签页管理器
        tabsManager = TabsManager(this, binding.tabsContainer)

        // 请求关键权限
        requestRuntimePermissions()

        // 配置工具栏
        setupToolbar()

        // 加载上次会话
        lifecycleScope.launch {
            val snaps = if (PreferenceManager.get(PreferenceManager.KEY_PERSIST_SESSION, true)) {
                SessionManager.get(this@BrowserActivity).loadTabs()
            } else emptyList()

            if (snaps.isEmpty()) {
                val home = PreferenceManager.get(PreferenceManager.KEY_HOME_PAGE, "https://www.bing.com")
                tabsManager.newTab(home)
            } else {
                tabsManager.restoreFromSnapshots(snaps)
                // 恢复后让每个 tab 实际加载 URL
                tabsManager.allTabs.forEachIndexed { i, tab ->
                    if (tab.url.isNotBlank()) {
                        tab.webView.loadUrl(tab.url)
                    }
                }
                tabsManager.select(0)
                showTab(tabsManager.currentTab!!)
            }
        }

        // 注册返回键回调 —— 隐藏而非退出
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val tab = tabsManager.currentTab
                if (tab != null && tab.webView.canGoBack()) {
                    tab.webView.goBack()
                } else {
                    // 隐藏到后台，最近任务会自动消失
                    hideToBackground()
                }
            }
        })

        // 确保核心服务跑着
        (application as BrowserApplication).ensureCoreServiceRunning()

        // 同步无痕状态
        HistoryManager.get(this).setStealth(
            PreferenceManager.get(PreferenceManager.KEY_STEALTH_MODE, false)
        )
    }

    override fun onResume() {
        super.onResume()
        // 强制把当前 WebView 显示出来
        tabsManager.currentTab?.let { showTab(it) }
    }

    override fun onPause() {
        super.onPause()
        // 持久化会话
        if (PreferenceManager.get(PreferenceManager.KEY_PERSIST_SESSION, true)) {
            lifecycleScope.launch {
                SessionManager.get(this@BrowserActivity).saveTabs(tabsManager.toSnapshots())
                WuyingLog.d("Browser", "会话已保存")
            }
        }
    }

    override fun onDestroy() {
        // 真正销毁时也要保存
        if (PreferenceManager.get(PreferenceManager.KEY_PERSIST_SESSION, true)) {
            lifecycleScope.launch {
                SessionManager.get(this@BrowserActivity).saveTabs(tabsManager.toSnapshots())
            }
        }
        if (PreferenceManager.get(PreferenceManager.KEY_CLEAR_ON_EXIT, false)) {
            clearBrowserData()
        }
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupToolbar() {
        // 地址栏回车跳转
        binding.urlInput.setOnEditorActionListener { v, actionId, event ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO
                || (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                val input = binding.urlInput.text.toString().trim()
                if (input.isNotEmpty()) {
                    navigate(input)
                }
                true
            } else false
        }

        // 输入框聚焦时全选
        binding.urlInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) binding.urlInput.selectAll()
        }

        // 后退
        binding.btnBack.setOnClickListener {
            tabsManager.currentTab?.webView?.takeIf { it.canGoBack() }?.goBack()
        }
        // 前进
        binding.btnForward.setOnClickListener {
            tabsManager.currentTab?.webView?.takeIf { it.canGoForward() }?.goForward()
        }
        // 首页
        binding.btnHome.setOnClickListener {
            val home = PreferenceManager.get(PreferenceManager.KEY_HOME_PAGE, "https://www.bing.com")
            tabsManager.currentTab?.webView?.loadUrl(home)
        }
        // 刷新
        binding.btnRefresh.setOnClickListener {
            tabsManager.currentTab?.webView?.reload()
        }
        // 新标签
        binding.btnAddTab.setOnClickListener {
            val home = PreferenceManager.get(PreferenceManager.KEY_HOME_PAGE, "https://www.bing.com")
            tabsManager.newTab(home)
            tabsManager.currentTab?.let { showTab(it) }
        }
        // 关闭当前标签
        binding.btnCloseTab.setOnClickListener {
            if (tabsManager.size == 0) return@setOnClickListener
            tabsManager.closeCurrent()
            if (tabsManager.size == 0) {
                val home = PreferenceManager.get(PreferenceManager.KEY_HOME_PAGE, "https://www.bing.com")
                tabsManager.newTab(home)
            }
            tabsManager.currentTab?.let { showTab(it) }
        }
        // 设置
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        // 菜单：历史 / 下载 / 书签 / 退出
        binding.btnMenu.setOnClickListener {
            showMoreMenu()
        }
    }

    /**
     * 显示指定标签页 —— 把对应 WebView 加入内容容器
     */
    private fun showTab(tab: TabsManager.Tab) {
        binding.webContainer.removeAllViews()
        if (tab.webView.parent != null) {
            (tab.webView.parent as android.view.ViewGroup).removeView(tab.webView)
        }
        binding.webContainer.addView(tab.webView)
        binding.urlInput.setText(tab.url)
        binding.titleBar.text = if (tab.title.isBlank()) tab.url else tab.title

        // WebView 事件回调
        tab.webView.onUrlChanged = { url ->
            tab.url = url
            if (binding.urlInput.text.toString() != url) {
                binding.urlInput.setText(url)
            }
        }
        tab.webView.onTitleChanged = { title ->
            tab.title = title
            binding.titleBar.text = title
            // 写入历史
            if (!PreferenceManager.get(PreferenceManager.KEY_STEALTH_MODE, false)
                && tab.url.startsWith("http")
            ) {
                lifecycleScope.launch {
                    HistoryManager.get(this@BrowserActivity).record(tab.url, title)
                }
            }
        }
        tab.webView.onProgressChanged = { p ->
            binding.progressBar.progress = p
            binding.progressBar.visibility = if (p < 100) View.VISIBLE else View.GONE
            tab.loading = p < 100
        }
        tab.webView.onFaviconChanged = { /* 更新 favicon */ }
    }

    /**
     * 解析输入：URL 还是搜索关键字
     */
    private fun navigate(input: String) {
        val tab = tabsManager.currentTab ?: return
        val url = when {
            // 域名/IP
            input.matches(Regex("^[a-zA-Z0-9\\-._]+(:\\d+)?(/.*)?$"))
                && input.contains(".") -> {
                if (input.startsWith("http")) input else "https://$input"
            }
            // 完整 URL
            input.startsWith("http://") || input.startsWith("https://") -> input
            // 搜索引擎
            else -> {
                val engine = PreferenceManager.get(
                    PreferenceManager.KEY_SEARCH_ENGINE,
                    "https://www.bing.com/search?q=%s"
                )
                engine.replace("%s", Uri.encode(input))
            }
        }
        tab.url = url
        tab.webView.loadUrl(url)
    }

    /**
     * 显示更多菜单：历史 / 下载 / 书签 / 清数据 / 退出
     */
    private fun showMoreMenu() {
        val popup = android.widget.PopupMenu(this, binding.btnMenu)
        popup.menuInflater.inflate(R.menu.menu_more, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_history -> { startActivity(Intent(this, HistoryActivity::class.java)); true }
                R.id.menu_downloads -> { startActivity(Intent(this, DownloadsActivity::class.java)); true }
                R.id.menu_bookmarks -> { startActivity(Intent(this, BookmarksActivity::class.java)); true }
                R.id.menu_clear_data -> { clearBrowserData(); Toast.makeText(this, R.string.cleared, Toast.LENGTH_SHORT).show(); true }
                R.id.menu_exit -> { finishAffinity(); true }
                else -> false
            }
        }
        popup.show()
    }

    /**
     * 清除浏览器数据（Cookie / Cache / 表单 / 历史 / WebStorage）
     */
    private fun clearBrowserData() {
        android.webkit.WebStorage.getInstance().deleteAllData()
        android.webkit.CookieManager.getInstance().removeAllCookies(null)
        android.webkit.CookieManager.getInstance().flush()
        binding.webContainer.removeAllViews()
        tabsManager.allTabs.forEach { it.webView.clearHistory(); it.webView.clearCache(true); it.webView.clearFormData() }
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                HistoryManager.get(this@BrowserActivity).clear()
            }
        }
    }

    /**
     * 隐藏到后台，最近任务会自动消失
     */
    private fun hideToBackground() {
        moveTaskToBack(true)
        // 主动 finish 掉，让最近任务彻底无记录（excludeFromRecents 已生效）
        finish()
    }

    /**
     * 请求运行时权限（网页权限代理需要）
     */
    private fun requestRuntimePermissions() {
        val required = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            required.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            required.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        val toRequest = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        if (toRequest.isNotEmpty()) {
            permissionLauncher.launch(toRequest)
        }
    }

    /**
     * WebView 文件选择回调（由 WuyingWebView.WuyingChromeClient 委托调用）
     */
    fun onShowFileChooser(
        webView: WebView?,
        cb: ValueCallback<Array<Uri>>?,
        params: WebChromeClient.FileChooserParams?
    ): Boolean {
        filePathCallback?.onReceiveValue(null)
        filePathCallback = cb
        val intent = params?.createIntent() ?: return false
        try {
            fileChooserLauncher.launch(intent)
        } catch (t: Throwable) {
            filePathCallback = null
            return false
        }
        return true
    }

    /**
     * 网页请求权限时路由到系统
     */
    fun requestWebPermission(permission: String, requestCode: Int) {
        ActivityCompat.requestPermissions(this, arrayOf(permission), requestCode)
    }

    companion object {
        const val RC_LOCATION = 0x10
        const val RC_CAMERA = 0x11
        const val RC_MIC = 0x12
        const val RC_NOTIFICATION = 0x13
    }
}
