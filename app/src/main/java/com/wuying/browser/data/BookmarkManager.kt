package com.wuying.browser.data

import android.content.Context
import com.wuying.browser.data.AppDatabase
import com.wuying.browser.data.BookmarkEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BookmarkManager private constructor(private val context: Context) {

    companion object {
        @Volatile private var INSTANCE: BookmarkManager? = null
        fun get(context: Context): BookmarkManager =
            INSTANCE ?: synchronized(this) { INSTANCE ?: BookmarkManager(context.applicationContext).also { INSTANCE = it } }
    }

    private val dao get() = AppDatabase.get(context).bookmarkDao()

    suspend fun add(url: String, title: String, favicon: String? = null) = withContext(Dispatchers.IO) {
        dao.insert(BookmarkEntity(url = url, title = title, favicon = favicon, createdTime = System.currentTimeMillis()))
    }

    suspend fun remove(url: String) = withContext(Dispatchers.IO) { dao.deleteByUrl(url) }
    suspend fun has(url: String): Boolean = withContext(Dispatchers.IO) { dao.has(url) }
    suspend fun all(): List<BookmarkEntity> = withContext(Dispatchers.IO) { dao.all() }
}
