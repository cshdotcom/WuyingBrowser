package com.wuying.browser.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.wuying.browser.R
import com.wuying.browser.data.TabSnapshot
import com.wuying.browser.web.WuyingWebView
import java.util.UUID

/**
 * 多标签页管理器
 *
 * 每个标签页持有：
 *  - 一个 WuyingWebView 实例
 *  - 一个唯一 id
 *  - 当前 url / title / favicon
 *
 * 同时维护 tab 栏 UI 的渲染
 */
class TabsManager(private val context: Context, private val tabsContainer: ViewGroup) {

    data class Tab(
        val id: String = UUID.randomUUID().toString(),
        var url: String = "",
        var title: String = "",
        var favicon: android.graphics.Bitmap? = null,
        val webView: WuyingWebView,
        var loading: Boolean = false
    )

    private val tabs = mutableListOf<Tab>()
    private var currentIndex = -1
    var onTabSelected: ((Tab) -> Unit)? = null
    var onTabClosed: ((Tab) -> Unit)? = null
    var onTabListChanged: ((List<Tab>) -> Unit)? = null

    val size get() = tabs.size
    val currentTab get() = if (currentIndex in tabs.indices) tabs[currentIndex] else null
    val allTabs get() = tabs.toList()

    /**
     * 新建标签页
     */
    fun newTab(url: String = ""): Tab {
        val wv = WuyingWebView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            applySettings()
        }
        val tab = Tab(webView = wv, url = url)
        tabs.add(tab)
        currentIndex = tabs.size - 1
        renderTabs()
        onTabListChanged?.invoke(tabs.toList())
        if (url.isNotEmpty()) {
            tab.webView.loadUrl(url)
        }
        return tab
    }

    /**
     * 切换到指定 index
     */
    fun select(index: Int) {
        if (index !in tabs.indices) return
        currentIndex = index
        renderTabs()
        onTabSelected?.invoke(tabs[index])
    }

    /**
     * 关闭指定 index
     */
    fun close(index: Int) {
        if (index !in tabs.indices) return
        val tab = tabs.removeAt(index)
        tab.webView.destroy()
        if (currentIndex >= tabs.size) currentIndex = tabs.size - 1
        renderTabs()
        onTabClosed?.invoke(tab)
        onTabListChanged?.invoke(tabs.toList())
        if (currentIndex >= 0) {
            onTabSelected?.invoke(tabs[currentIndex])
        }
    }

    fun closeCurrent() {
        if (currentIndex >= 0) close(currentIndex)
    }

    /**
     * 转换为可持久化的快照列表
     */
    fun toSnapshots(): List<TabSnapshot> = tabs.map {
        TabSnapshot(id = it.id, url = it.url, title = it.title, favicon = null)
    }

    /**
     * 从快照恢复（不自动 loadUrl，由调用方控制）
     */
    fun restoreFromSnapshots(snaps: List<TabSnapshot>) {
        tabs.forEach { it.webView.destroy() }
        tabs.clear()
        snaps.forEach { snap ->
            val wv = WuyingWebView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                applySettings()
            }
            tabs.add(Tab(id = snap.id, url = snap.url, title = snap.title, webView = wv))
        }
        currentIndex = if (tabs.isEmpty()) -1 else 0
        renderTabs()
        onTabListChanged?.invoke(tabs.toList())
    }

    /**
     * 渲染底部 tab 栏 —— 简易实现，最多显示前 6 个 tab
     */
    private fun renderTabs() {
        tabsContainer.removeAllViews()
        if (tabs.isEmpty()) return
        val inflater = LayoutInflater.from(context)
        val maxShow = 6
        val showCount = minOf(tabs.size, maxShow)
        for (i in 0 until showCount) {
            val tab = tabs[i]
            val view = inflater.inflate(R.layout.item_tab_chip, tabsContainer, false) as TextView
            val title = if (tab.title.isBlank()) context.getString(R.string.new_tab) else tab.title
            view.text = if (title.length > 8) title.substring(0, 8) + "…" else title
            view.isSelected = (i == currentIndex)
            view.setOnClickListener { select(i) }
            view.setOnLongClickListener { close(i); true }
            tabsContainer.addView(view)
        }
        if (tabs.size > maxShow) {
            val more = TextView(context).apply {
                text = "+${tabs.size - maxShow}"
                setPadding(24, 12, 24, 12)
                setTextColor(context.getColor(R.color.text_secondary))
            }
            tabsContainer.addView(more)
        }
    }
}
