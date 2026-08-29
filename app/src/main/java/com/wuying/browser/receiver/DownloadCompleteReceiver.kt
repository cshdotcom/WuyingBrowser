package com.wuying.browser.receiver

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.wuying.browser.R
import com.wuying.browser.data.AppDatabase
import com.wuying.browser.data.DownloadEntity
import com.wuying.browser.util.WuyingLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 系统下载完成广播：更新数据库状态
 */
class DownloadCompleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        WuyingLog.i("Download", "下载完成 id=$id")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val query = DownloadManager.Query().setFilterById(id)
                dm.query(query)?.use { c ->
                    if (c.moveToFirst()) {
                        val localUri = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                        val mime = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE))
                        val title = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE))
                        val size = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        AppDatabase.get(context).downloadDao().insert(
                            DownloadEntity(
                                url = "", fileName = title,
                                localPath = localUri ?: "", mime = mime ?: "",
                                size = size, status = 1, createdTime = System.currentTimeMillis()
                            )
                        )
                    }
                }
            } catch (t: Throwable) {
                WuyingLog.e("Download", "处理完成广播失败", t)
            }
        }
        Toast.makeText(context, context.getString(R.string.download_complete), Toast.LENGTH_SHORT).show()
    }
}
