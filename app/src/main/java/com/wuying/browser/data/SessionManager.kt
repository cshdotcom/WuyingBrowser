package com.wuying.browser.data

import android.content.Context
import com.wuying.browser.data.AppDatabase
import com.wuying.browser.data.SessionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 会话持久化管理器
 *
 * 关键功能：
 * 1. 用户每次切换/关闭标签页前，把当前所有标签页的 URL + 标题写入 Room DB
 * 2. 下次启动 BrowserActivity 时，从 DB 恢复所有标签页
 * 3. 支持清空
 *
 * 即使 Activity 被销毁、最近任务被清除，只要 :core 进程的 Room DB 还在，
 * 下次打开就能继续上次的浏览。
 */
class SessionManager private constructor(private val context: Context) {

    companion object {
        @Volatile private var INSTANCE: SessionManager? = null
        fun get(context: Context): SessionManager =
            INSTANCE ?: synchronized(this) { INSTANCE ?: SessionManager(context.applicationContext).also { INSTANCE = it } }
    }

    private val dao get() = AppDatabase.get(context).sessionDao()

    /**
     * 保存当前所有标签页
     */
    suspend fun saveTabs(tabs: List<TabSnapshot>) = withContext(Dispatchers.IO) {
        dao.clear()
        tabs.forEachIndexed { index, tab ->
            dao.upsert(
                SessionEntity(
                    tabId = tab.id,
                    url = tab.url,
                    title = tab.title,
                    favicon = tab.favicon,
                    position = index,
                    savedTime = System.currentTimeMillis()
                )
            )
        }
    }

    /**
     * 加载上次的标签页（按 position 升序）
     */
    suspend fun loadTabs(): List<TabSnapshot> = withContext(Dispatchers.IO) {
        dao.all().map { e ->
            TabSnapshot(
                id = e.tabId,
                url = e.url,
                title = e.title,
                favicon = e.favicon
            )
        }
    }

    /**
     * 清空会话
     */
    suspend fun clear() = withContext(Dispatchers.IO) { dao.clear() }
}

/**
 * 内存中的标签页快照
 */
data class TabSnapshot(
    val id: String,
    val url: String,
    val title: String,
    val favicon: String? = null
)
