package com.wuying.browser.data

import android.content.Context
import com.wuying.browser.data.AppDatabase
import com.wuying.browser.data.HistoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 历史记录管理器
 *
 * 无痕模式时本类不写入。
 */
class HistoryManager private constructor(private val context: Context) {

    companion object {
        @Volatile private var INSTANCE: HistoryManager? = null
        fun get(context: Context): HistoryManager =
            INSTANCE ?: synchronized(this) { INSTANCE ?: HistoryManager(context.applicationContext).also { INSTANCE = it } }
    }

    private val dao get() = AppDatabase.get(context).historyDao()
    @Volatile private var stealth = false
    fun setStealth(s: Boolean) { stealth = s }

    suspend fun record(url: String, title: String, favicon: String? = null) = withContext(Dispatchers.IO) {
        if (stealth || url.isBlank()) return@withContext
        dao.insert(HistoryEntity(url = url, title = title, visitTime = System.currentTimeMillis(), favicon = favicon))
    }

    suspend fun all(): List<HistoryEntity> = withContext(Dispatchers.IO) { dao.all() }
    suspend fun search(kw: String): List<HistoryEntity> = withContext(Dispatchers.IO) { dao.search("%$kw%") }
    suspend fun clear() = withContext(Dispatchers.IO) { dao.clear() }
    suspend fun delete(e: HistoryEntity) = withContext(Dispatchers.IO) { dao.delete(e) }
}
