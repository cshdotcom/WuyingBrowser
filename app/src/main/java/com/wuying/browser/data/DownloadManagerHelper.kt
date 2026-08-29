package com.wuying.browser.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.core.net.toUri
import com.wuying.browser.data.AppDatabase
import com.wuying.browser.data.DownloadEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 下载管理器：使用系统 DownloadManager + 自有数据库双轨记录
 */
class DownloadManagerHelper private constructor(private val context: Context) {

    companion object {
        @Volatile private var INSTANCE: DownloadManagerHelper? = null
        fun get(context: Context): DownloadManagerHelper =
            INSTANCE ?: synchronized(this) { INSTANCE ?: DownloadManagerHelper(context.applicationContext).also { INSTANCE = it } }
    }

    private val dao get() = AppDatabase.get(context).downloadDao()
    private val dm get() = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    /**
     * 启动一个下载
     */
    suspend fun startDownload(url: String, contentDisposition: String?, mimeType: String?): Long = withContext(Dispatchers.IO) {
        val fileName = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)
        val req = DownloadManager.Request(Uri.parse(url)).apply {
            setMimeType(mimeType ?: "*/*")
            setTitle(fileName)
            setDescription("无影浏览器下载")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
        }
        val id = dm.enqueue(req)
        // 同时写入数据库
        dao.insert(
            DownloadEntity(
                url = url, fileName = fileName,
                localPath = "content://${com.wuying.browser.BuildConfig.APPLICATION_ID}.fileprovider/Download/$fileName",
                mime = mimeType ?: "", size = 0L, status = 0,
                createdTime = System.currentTimeMillis()
            )
        )
        id
    }

    suspend fun all(): List<DownloadEntity> = withContext(Dispatchers.IO) { dao.all() }
    suspend fun clear() = withContext(Dispatchers.IO) { dao.clear() }
}
