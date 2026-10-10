package com.example.novelreader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONTokener
import java.net.URL

internal data class AaaStoreBook(val hit: AaaSearchHit, val intro: String, val chapters: Int)

internal fun parseAaaStore(response: String): List<AaaStoreBook> {
    val raw = response.trim()
    val compressed = if (raw.startsWith('"')) JSONTokener(raw).nextValue() as? String
        ?: error("书城返回格式异常。") else raw
    val array = JSONArray(aaaDecompress(compressed))
    require(array.length() <= 300) { "书城返回数量异常。" }
    fun plain(text: String) = android.text.Html.fromHtml(text, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()
    return (0 until array.length()).map { i ->
        val item = array.getJSONObject(i)
        val id = item.getString("tid")
        val site = item.getString("siteid")
        require(id.matches(Regex("[0-9]{1,15}")) && site.matches(Regex("[0-9]{1,10}"))) { "书城编号异常。" }
        val title = plain(item.getString("articlename")).take(200)
        require(title.isNotBlank()) { "书城书名为空。" }
        AaaStoreBook(AaaSearchHit(id, site, title, plain(item.optString("author")).take(100)),
            plain(item.optString("intro")).take(1000), item.optInt("chapters").coerceAtLeast(0))
    }.distinctBy { "${it.hit.site}-${it.hit.book}" }
}

internal class AaaStoreState {
    var books by mutableStateOf(emptyList<AaaStoreBook>())
    var page by mutableStateOf(0)
    var loading by mutableStateOf(false)
    var error by mutableStateOf("")
    var end by mutableStateOf(false)
    var retryRefresh = false
    val grid = LazyGridState()

    fun load(scope: CoroutineScope, refresh: Boolean = false) {
        if (loading || (end && !refresh)) return
        retryRefresh = refresh
        scope.launch {
            loading = true; error = ""
            try {
                val target = if (refresh) 1 else page + 1
                require(target in 1..10)
                val bytes = aaaGet("/api-list-$target")
                val next = withContext(Dispatchers.Default) { parseAaaStore(bytes.toString(Charsets.UTF_8)) }
                books = ((if (refresh) emptyList() else books) + next).distinctBy { "${it.hit.site}-${it.hit.book}" }
                page = target; end = next.isEmpty() || target == 10
                if (refresh) grid.scrollToItem(0)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "加载失败，请重试。" }
            finally { loading = false }
        }
    }
}

@Composable
internal fun AaaStorePane(state: AaaStoreState, scope: CoroutineScope, modifier: Modifier, select: (AaaSearchHit) -> Unit) {
    LaunchedEffect(Unit) { if (state.page == 0 && state.error.isEmpty()) state.load(scope) }
    LaunchedEffect(state) {
        snapshotFlow { Triple(state.grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1, state.books.size, state.loading) }
            .collect { (last, count, loading) ->
                if (count > 0 && last >= count - 6 && !loading && !state.end && state.error.isBlank()) state.load(scope)
            }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.error.isNotBlank()) {
            Text(state.error, color = MaterialTheme.colorScheme.error, maxLines = 3)
            TextButton(onClick = { state.load(scope, state.retryRefresh) }, enabled = !state.loading) { Text("重试") }
        }
        LazyVerticalGrid(columns = GridCells.Fixed(3), state = state.grid, modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(state.books, key = { "${it.hit.site}-${it.hit.book}" }) { book ->
                Card(onClick = { select(book.hit) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        AaaStoreCover(book.hit)
                        Text(book.hit.title, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        Text(book.hit.author, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(book.intro, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private object StoreCovers {
    val slots = Semaphore(3)
    val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    suspend fun get(hit: AaaSearchHit): Bitmap? = withContext(Dispatchers.IO) {
        val key = "${hit.site}-${hit.book}"
        cache.get(key)?.let { return@withContext it }
        slots.withPermit {
            cache.get(key)?.let { return@withPermit it }
            val connection = URL("https://www.aaawz.cc/bookimg/${hit.site}/${hit.book.toLong() % 100}/${hit.book}.jpg").openConnection() as javax.net.ssl.HttpsURLConnection
            try {
                connection.connectTimeout = 10000; connection.readTimeout = 10000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Referer", "https://www.aaawz.cc/")
                if (connection.responseCode != 200) return@withPermit null
                val bytes = connection.inputStream.use { stream ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        ensureActive()
                        val n = stream.read(buffer); if (n < 0) break
                        require(out.size() + n <= 2 * 1024 * 1024)
                        out.write(buffer, 0, n)
                    }
                    out.toByteArray()
                }
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                if (options.outWidth <= 0 || options.outHeight <= 0) return@withPermit null
                options.inJustDecodeBounds = false
                options.inSampleSize = 1
                while (options.outWidth / options.inSampleSize > 480 || options.outHeight / options.inSampleSize > 720) options.inSampleSize *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.also { cache.put(key, it) }
            } finally { connection.disconnect() }
        }
    }
}

private val savedCoverLock = kotlinx.coroutines.sync.Mutex()

internal suspend fun savedBookCover(context: android.content.Context, id: String): Bitmap? = withContext(Dispatchers.IO) {
    require(id.matches(Regex("[A-Za-z0-9-]+")))
    val destination = java.io.File(context.filesDir, "book-covers/$id.jpg")
    // Final files are atomically replaced; offline reads never wait for other downloads.
    val existing = runCatching { BitmapFactory.decodeFile(destination.path) }.getOrNull()
    if (existing != null) return@withContext existing
    savedCoverLock.lock()
    try {
        val atomic = android.util.AtomicFile(destination)
        val local = runCatching { atomic.openRead().use { BitmapFactory.decodeStream(it) } }.getOrNull()
        if (local != null) return@withContext local
        val request = DownloadRequest.read(context, id)
        val address = request?.book?.address ?: Regex("aaa-([0-9]{1,15})-([0-9]{1,10})").matchEntire(id)?.let {
            AaaAddress(it.groupValues[1], it.groupValues[2])
        } ?: return@withContext null
        if (address.site in listOf("qishu", "baoshu")) return@withContext null
        val bitmap = StoreCovers.get(AaaSearchHit(address.book, address.site, "", "")) ?: return@withContext null
        // A removed book must not be recreated by a late cover response.
        if (!java.io.File(context.filesDir, "books/$id.json").exists()) return@withContext null
        destination.parentFile?.mkdirs()
        val output = atomic.startWrite()
        try {
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
            atomic.finishWrite(output)
        } catch (e: Exception) { atomic.failWrite(output); throw e }
        bitmap
    } finally { savedCoverLock.unlock() }
}

@Composable
internal fun SavedBookCover(id: String, title: String, placeholder: @Composable () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val cover by produceState<Bitmap?>(null, id) {
        value = try { savedBookCover(context, id) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }
    if (cover == null) placeholder()
    else Image(cover!!.asImageBitmap(), contentDescription = "${title}封面", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
}

@Composable
private fun AaaStoreCover(hit: AaaSearchHit) {
    val cover by produceState<Bitmap?>(null, hit.site, hit.book) {
        value = try { StoreCovers.get(hit) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }
    Surface(modifier = Modifier.fillMaxWidth().aspectRatio(0.72f), color = MaterialTheme.colorScheme.surfaceVariant) {
        Box(contentAlignment = Alignment.Center) {
            if (cover != null) Image(cover!!.asImageBitmap(), contentDescription = "${hit.title}封面", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Text(hit.title, Modifier.padding(12.dp), maxLines = 4)
        }
    }
}

