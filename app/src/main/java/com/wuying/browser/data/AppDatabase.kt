package com.wuying.browser.data

import android.content.Context
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Delete

/**
 * Room 数据库：历史、下载、书签、标签页会话
 */
@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    val visitTime: Long,
    val favicon: String? = null
)

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val fileName: String,
    val localPath: String,
    val mime: String,
    val size: Long,
    val status: Int,    // 0 进行中 1 完成 2 失败
    val createdTime: Long
)

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    val favicon: String? = null,
    val createdTime: Long
)

@Entity(tableName = "session_tab")
data class SessionEntity(
    @PrimaryKey val tabId: String,
    val url: String,
    val title: String,
    val favicon: String? = null,
    val position: Int,
    val savedTime: Long
)

@Dao
interface HistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(e: HistoryEntity)
    @Query("SELECT * FROM history ORDER BY visitTime DESC LIMIT 500") suspend fun all(): List<HistoryEntity>
    @Query("DELETE FROM history") suspend fun clear()
    @Query("SELECT * FROM history WHERE url LIKE :kw OR title LIKE :kw ORDER BY visitTime DESC LIMIT 50")
    suspend fun search(kw: String): List<HistoryEntity>
    @Delete suspend fun delete(e: HistoryEntity)
}

@Dao
interface DownloadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(e: DownloadEntity): Long
    @Query("SELECT * FROM downloads ORDER BY createdTime DESC") suspend fun all(): List<DownloadEntity>
    @Query("DELETE FROM downloads") suspend fun clear()
    @Query("UPDATE downloads SET status = :s WHERE id = :id") suspend fun updateStatus(id: Long, s: Int)
}

@Dao
interface BookmarkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(e: BookmarkEntity): Long
    @Query("SELECT * FROM bookmarks ORDER BY createdTime DESC") suspend fun all(): List<BookmarkEntity>
    @Query("DELETE FROM bookmarks WHERE url = :url") suspend fun deleteByUrl(url: String)
    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE url = :url)") suspend fun has(url: String): Boolean
}

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(e: SessionEntity)
    @Query("SELECT * FROM session_tab ORDER BY position ASC") suspend fun all(): List<SessionEntity>
    @Query("DELETE FROM session_tab") suspend fun clear()
}

@Database(
    entities = [HistoryEntity::class, DownloadEntity::class, BookmarkEntity::class, SessionEntity::class],
    version = 1, exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
    abstract fun downloadDao(): DownloadDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun sessionDao(): SessionDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun init(context: Context) { get(context) }
        fun get(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "wuying.db"
            )
                .fallbackToDestructiveMigration()
                .build()
                .also { INSTANCE = it }
        }
    }
}
