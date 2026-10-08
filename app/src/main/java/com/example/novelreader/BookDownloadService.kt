package com.example.novelreader

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray
import java.io.File

internal object ReaderRuntime {
    private var model: ReaderModel? = null
    fun get(app: Application): ReaderModel = model ?: ReaderModel(app).also { model = it }
}

internal fun writeDownloadJson(file: File, json: JSONObject) {
    val atomic = android.util.AtomicFile(file)
    val out = atomic.startWrite()
    try { out.write(json.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(out) }
    catch (e: Exception) { atomic.failWrite(out); throw e }
}

internal data class DownloadRequest(val book: AaaBook, val title: String, val author: String, val count: Int) {
    fun save(context: Context, id: String) {
        val chapters = JSONArray()
        book.chapters.forEach { chapters.put(JSONObject().put("id", it.id).put("title", it.title)) }
        writeDownloadJson(path(context, id), JSONObject().put("book", book.address.book).put("site", book.address.site)
            .put("chapters", chapters).put("title", title).put("author", author).put("count", count))
    }
    companion object {
        private fun path(context: Context, id: String): File {
            require(Regex("(?:aaa-\\d+-\\d+|[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})").matches(id))
            return File(File(context.filesDir, "download-requests").apply { mkdirs() }, "$id.json")
        }
        fun read(context: Context, id: String): DownloadRequest? {
            if (id.isBlank()) return null
            val file = path(context, id)
            if (!file.exists() && !File(file.path + ".bak").exists()) return null
            val data = JSONObject(android.util.AtomicFile(file).openRead().bufferedReader().use { it.readText() })
            val array = data.getJSONArray("chapters")
            val book = AaaBook(AaaAddress(data.getString("book"), data.getString("site")), (0 until array.length()).map {
                val item = array.getJSONObject(it); AaaChapter(item.getString("id"), item.getString("title"))
            })
            return DownloadRequest(book, data.getString("title"), data.optString("author"), data.getInt("count"))
        }
    }
}

class BookDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var monitor: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val model get() = ReaderRuntime.get(application)
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("book-download", "书籍下载", NotificationManager.IMPORTANCE_LOW))
    }
    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getService(this, 1, Intent(this, BookDownloadService::class.java).setAction("pause"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "book-download").setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("悦读 · ${model.activeDownloads} 本正在下载")
            .setContentText(model.downloads.values.filter { it.downloading }.joinToString("；") { "${it.downloadTitle} ${it.cachedChapters}/${it.totalChapters}" }).setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true)
            .setStyle(NotificationCompat.BigTextStyle().bigText(model.downloads.values.filter { it.downloading }
                .joinToString("\n") { "${it.downloadTitle}：${it.cachedChapters}/${it.totalChapters} 章" }))
            .setProgress(model.downloads.values.filter { it.downloading }.sumOf { it.totalChapters }.coerceAtLeast(1),
                model.downloads.values.filter { it.downloading }.sumOf { it.cachedChapters },
                model.downloads.values.any { it.downloading && it.totalChapters == 0 })
            .addAction(0, "全部暂停", pause).build()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "pause") { model.stopDownload(); stopSelf(); return START_NOT_STICKY }
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(7301, notification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(7301, notification())
            val id = intent?.getStringExtra("bookId") ?: run { stopSelf(); return START_NOT_STICKY }
            model.runDownload(id)
            if (wakeLock == null) {
                wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "yuedu:download").apply { acquire(6 * 60 * 60 * 1000L) }
            }
            monitor?.cancel()
            monitor = scope.launch {
                while (model.downloading) {
                    getSystemService(NotificationManager::class.java).notify(7301, notification())
                    delay(1500)
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        } catch (e: Exception) {
            model.stopDownload()
            model.error = "无法启动后台下载：${e.message}"
            stopSelf()
        }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        model.stopDownload()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() {
        model.stopDownload()
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }
    companion object {
        fun request(context: Context, id: String) {
            try { ContextCompat.startForegroundService(context, Intent(context, BookDownloadService::class.java).putExtra("bookId", id)) }
            catch (e: Exception) { ReaderRuntime.get(context.applicationContext as Application).error = "无法启动下载：${e.message}" }
        }
    }
}
