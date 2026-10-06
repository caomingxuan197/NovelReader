package com.example.novelreader

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.URL
import java.security.MessageDigest

internal const val AAA_FONT = "/static/fonts/0d7cb51e834e3aedb5e9e6bc6d70394d3c2de6066840db5d.woff2"
internal data class AaaAddress(val book: String, val site: String)
internal fun parseAaaAddress(value: String): AaaAddress {
    val uri = URI(value.trim())
    require(uri.scheme == "https" && uri.host == "www.aaawz.cc" && uri.userInfo == null && uri.port == -1) { "请粘贴 https://www.aaawz.cc/ 的书籍或阅读链接。" }
    val match = Regex("^/(?:book/(\\d+)/(\\d+)|read/(\\d+)/(\\d+)/(\\d+))/?$").matchEntire(uri.fragment.orEmpty())
        ?: error("链接格式不正确，请复制书籍详情或阅读页的完整网址。")
    return if (match.groupValues[1].isNotEmpty()) AaaAddress(match.groupValues[1], match.groupValues[2])
        else AaaAddress(match.groupValues[3], match.groupValues[4])
}

// LZ-String's Base64 wire format: variable-width dictionary codes, little-endian bits.
internal fun aaaDecompress(input: String): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    val data = input.trim().trimEnd('=')
    require(data.isNotEmpty() && data.length <= 4_000_000 && data.all { it in alphabet }) { "正文压缩数据无效。" }
    var position = 0
    fun bits(count: Int): Int {
        require(count in 1..22 && position.toLong() + count <= data.length.toLong() * 6) { "正文数据不完整。" }
        var value = 0
        repeat(count) { bit ->
            val digit = alphabet.indexOf(data[position / 6])
            value = value or (((digit shr (5 - position % 6)) and 1) shl bit)
            position++
        }
        return value
    }
    val initial = when (bits(2)) { 0 -> bits(8).toChar().toString(); 1 -> bits(16).toChar().toString(); 2 -> return ""; else -> error("正文压缩格式不支持。") }
    val dictionary = arrayListOf("", "", "", initial)
    val output = StringBuilder(initial)
    var previous = initial
    var width = 3
    var remaining = 4
    while (true) {
        var code = bits(width)
        if (code == 2) return output.toString()
        if (code == 0 || code == 1) {
            dictionary.add(bits(if (code == 0) 8 else 16).toChar().toString())
            code = dictionary.lastIndex
            remaining--
        }
        if (remaining == 0) { remaining = 1 shl width; width++ }
        val entry = when { code in 3 until dictionary.size -> dictionary[code]; code == dictionary.size -> previous + previous[0]; else -> error("正文压缩内容损坏。") }
        require(output.length.toLong() + entry.length <= 2_000_000 && dictionary.size < 500_000) { "单章数据过大。" }
        output.append(entry)
        dictionary.add(previous + entry[0])
        previous = entry
        remaining--
        if (remaining == 0) { remaining = 1 shl width; width++ }
    }
}

internal fun restoreAaa(text: String, mapping: Map<Char, Char>): String {
    require(mapping.size == 6763 && mapping.values.toSet() == mapping.keys) { "字体字表不完整，已停止下载，避免保存错字。" }
    return buildString {
        // Characters absent from the verified font use the website's fallback font unchanged.
        text.forEach { c -> append(mapping[c] ?: c) }
    }
}

private suspend fun aaaGet(path: String, fields: Map<String, String>? = null): ByteArray = withContext(Dispatchers.IO) {
    require(path.startsWith("/api-") || Regex("^/static/fonts/[a-f0-9]{48}\\.woff2$").matches(path))
    val connection = URL("https://www.aaawz.cc$path").openConnection() as javax.net.ssl.HttpsURLConnection
    try {
        connection.connectTimeout = 15000; connection.readTimeout = 20000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36")
        connection.setRequestProperty("Referer", "https://www.aaawz.cc/")
        if (fields != null) {
            val boundary = "NovelReader" + java.util.UUID.randomUUID().toString().replace("-", "")
            val body = buildString {
                fields.forEach { (name, value) ->
                    append("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
                }
                append("--$boundary--\r\n")
            }.toByteArray(Charsets.UTF_8)
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
        }
        require(connection.responseCode == 200) { "网站返回 ${connection.responseCode}，请确认当前网络能访问 AAA 小说。" }
        connection.inputStream.use { source ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16384)
            while (true) {
                ensureActive()
                val n = source.read(buffer); if (n < 0) break
                require(output.size() + n <= 8 * 1024 * 1024) { "接口返回的数据过大。" }
                output.write(buffer, 0, n)
            }
            output.toByteArray()
        }
    } finally { connection.disconnect() }
}

private data class AaaChapter(val id: String, val title: String, val paid: Boolean)
private data class AaaBook(val address: AaaAddress, val chapters: List<AaaChapter>)
internal data class AaaSearchHit(val book: String, val site: String, val title: String, val author: String)
internal data class AaaSearchPage(val books: List<AaaSearchHit>, val pages: Int)
internal fun parseAaaSearch(response: String): AaaSearchPage {
    val raw = response.trim()
    val compressed = if (raw.startsWith('"')) org.json.JSONTokener(raw).nextValue() as? String
        ?: error("搜索返回格式异常。") else raw
    val json = JSONObject(aaaDecompress(compressed))
    require(json.getInt("code") == 0) { json.optString("msg", "搜索失败，请稍后重试。") }
    val data = json.getJSONObject("data")
    val array = data.getJSONArray("books")
    require(array.length() <= 100) { "搜索结果数量异常。" }
    fun plain(value: String) = android.text.Html.fromHtml(value, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()
    val hits = (0 until array.length()).map { i ->
        val book = array.getJSONObject(i)
        val id = book.getString("tid"); val site = book.getString("siteid")
        require(id.matches(Regex("\\d+")) && site.matches(Regex("\\d+"))) { "搜索结果编号异常。" }
        AaaSearchHit(id, site, plain(book.getString("articlename")), plain(book.optString("author")))
    }.distinctBy { "${it.book}-${it.site}" }
    return AaaSearchPage(hits, data.getInt("total_pages").coerceIn(0, 1000))
}
private class AaaClient(private val context: Context) {
    suspend fun search(keyword: String, page: Int): AaaSearchPage {
        require(keyword.trim().length in 1..100 && page in 1..1000) { "请输入 1～100 个字的书名或作者。" }
        val bytes = aaaGet("/api-search", mapOf("keyword" to keyword.trim(), "page" to page.toString(), "size" to "10"))
        return withContext(Dispatchers.Default) { parseAaaSearch(bytes.toString(Charsets.UTF_8)) }
    }
    private val fontMaps = linkedMapOf<String, Map<Char, Char>>()
    private val fontLock = Mutex()
    private suspend fun mappingFor(json: JSONObject): Map<Char, Char> = fontLock.withLock {
        val path = json.getJSONObject("font").getString("url")
        require(Regex("^/static/fonts/[a-f0-9]{48}\\.woff2$").matches(path)) { "字体地址格式发生变化。" }
        fontMaps[path]?.let { return@withLock it }
        val font = aaaGet(path)
        val mapping = withContext(Dispatchers.Default) {
            val glyphs = context.assets.open("aaa-glyph-map.txt").bufferedReader(Charsets.UTF_8).use { it.readText() }
            decodeAaaFont(font, glyphs)
        }
        fontMaps[path] = mapping
        while (fontMaps.size > 8) fontMaps.remove(fontMaps.keys.first())
        mapping
    }
    suspend fun load(link: String): AaaBook {
        val address = parseAaaAddress(link)
        val json = JSONObject(aaaGet("/api-chapterlist-${address.book}-${address.site}?format=g2").toString(Charsets.UTF_8))
        require(json.optString("version") == "g2-list") { "网站目录格式发生变化，暂时无法下载。" }
        val mapping = mappingFor(json)
        return withContext(Dispatchers.Default) {
            val array = json.getJSONArray("chapters")
            require(array.length() in 1..20000) { "目录为空或过大。" }
            val chapters = (0 until array.length()).map { i ->
                val chapter = array.getJSONObject(i)
                val id = chapter.getString("cid")
                require(id.matches(Regex("\\d+"))) { "章节编号异常。" }
                AaaChapter(id, restoreAaa(chapter.getString("title"), mapping), chapter.optInt("vip", 0) != 0)
            }
            require(chapters.map { it.id }.distinct().size == chapters.size) { "目录中存在重复章节编号。" }
            AaaBook(address, chapters)
        }
    }
    suspend fun download(book: AaaBook, limit: Int, status: (String) -> Unit, publish: suspend (String) -> Unit): File {
        val chapters = book.chapters.take(limit)
        require(chapters.none { it.paid }) { "所选范围包含付费章节，下载已停止。" }
        val folder = File(context.cacheDir, "aaa-v3/${book.address.book}-${book.address.site}").apply { mkdirs() }
        val assembled = File(folder, "download.txt")
        withContext(Dispatchers.IO) { assembled.writeText("") }
        try {
            var completed = 0
            for (batch in chapters.chunked(2)) {
                currentCoroutineContext().ensureActive()
                status("正在下载 ${completed + 1}～${completed + batch.size}/${chapters.size}")
                val downloaded = coroutineScope { batch.map { chapter -> async {
                val cached = File(folder, "${chapter.id}.txt")
                val body = withContext(Dispatchers.IO) { if (cached.isFile && cached.length() in 1..8_000_000) cached.readText() else null }
                    ?: run {
                        val json = JSONObject(aaaGet("/api-chapter-${book.address.book}-${book.address.site}-${chapter.id}?format=g2").toString(Charsets.UTF_8))
                        require(json.optString("version") == "g2") { "章节格式不支持，下载已停止。" }
                        val mapping = mappingFor(json)
                        val text = withContext(Dispatchers.Default) { restoreAaa(aaaDecompress(json.getString("content")), mapping) }
                        require(text.isNotBlank() && !text.contains('\u0000')) { "章节内容为空或异常。" }
                        withContext(Dispatchers.IO) {
                            val temporary = File(folder, "${chapter.id}.tmp")
                            temporary.writeText(text)
                            check(temporary.renameTo(cached)) { "无法保存章节缓存。" }
                        }
                        text
                    }
                chapter to body
                } }.awaitAll() }
                for ((chapter, body) in downloaded) withContext(Dispatchers.IO) {
                    val addition = "${chapter.title}\n\n$body\n\n"
                    require(assembled.length() + addition.toByteArray(Charsets.UTF_8).size <= 100L * 1024 * 1024) { "小说超过 100 MB，下载已停止。" }
                    assembled.appendText(addition)
                }
                publish(downloaded.joinToString("") { (chapter, body) -> "${chapter.title}\n\n$body\n\n" })
                completed += batch.size
            }
            return assembled
        } catch (e: Exception) { withContext(NonCancellable + Dispatchers.IO) { assembled.delete() }; throw e }
    }
}

@Composable
internal fun AaaLibrary(model: ReaderModel) {
    val context = LocalContext.current
    val client = remember { AaaClient(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var keyword by rememberSaveable { mutableStateOf(model.aaaQuery) }
    var searched by remember { mutableStateOf("") }
    var page by remember { mutableStateOf(1) }
    var results by remember { mutableStateOf<AaaSearchPage?>(null) }
    var selectedHit by remember { mutableStateOf<AaaSearchHit?>(null) }
    var book by remember { mutableStateOf<AaaBook?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var status by remember { mutableStateOf("输入书名或作者，搜索后选择书籍下载。") }
    val running = job?.isActive == true || model.downloading
    fun start(action: suspend () -> Unit) {
        job = scope.launch {
            try { action() }
            catch (e: CancellationException) { status = "已停止，已完成的章节可在重试时复用。"; throw e }
            catch (e: Exception) { status = e.message ?: "下载失败，请重试。" }
            finally { job = null }
        }
    }
    fun back() { job?.cancel(); if (selectedHit != null) { selectedHit = null; book = null; status = "请选择书籍。" } else model.aaaOnline = false }
    fun search(target: Int, query: String) { start {
        status = "正在搜索…"
        val found = client.search(query, target)
        results = found; page = target; searched = query
        status = if (found.books.isEmpty()) "没有找到相关书籍，试试其他关键词。" else "请选择书籍查看目录。"
    } }
    LaunchedEffect(Unit) {
        if (keyword.isNotBlank()) search(1, keyword.trim())
    }
    BackHandler { back() }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row { TextButton(onClick = { back() }) { Text(if (selectedHit == null) "返回书架" else "返回搜索") }; Text("AAA 小说搜书", modifier = Modifier.padding(12.dp)) }
        if (selectedHit == null) {
            OutlinedTextField(value = keyword, onValueChange = { keyword = it }, enabled = !running, label = { Text("书名或作者") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Button(enabled = !running && keyword.isNotBlank(), onClick = { results = null; search(1, keyword.trim()) }) { Text("搜索") }
        } else {
            Text(selectedHit!!.title, style = MaterialTheme.typography.titleLarge)
            Text(selectedHit!!.author)
            if (book == null && !running) Button(onClick = { val hit = selectedHit!!; start { status = "正在获取目录并验证字体…"; book = client.load("https://www.aaawz.cc/#/book/${hit.book}/${hit.site}"); status = "已获取 ${book!!.chapters.size} 个章节条目。" } }) { Text("重试获取目录") }
        }
        Text(status)
        if (model.downloadStatus.isNotBlank()) Text(model.downloadStatus)
        model.downloadingBook?.let { readyBook ->
            Button(onClick = { model.aaaOnline = false; model.open(readyBook) }, enabled = !model.busy) { Text("立即阅读已下载内容") }
        }
        if (running) { LinearProgressIndicator(Modifier.fillMaxWidth()); TextButton(onClick = { job?.cancel(); model.stopDownload() }) { Text("停止") } }
        if (selectedHit == null) results?.let { found ->
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(found.books, key = { "${it.book}-${it.site}" }) { hit ->
                    Card(onClick = { selectedHit = hit; book = null; start { status = "正在获取目录并验证字体…"; book = client.load("https://www.aaawz.cc/#/book/${hit.book}/${hit.site}"); status = "已获取 ${book!!.chapters.size} 个章节条目。" } }, enabled = !running, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) { Text(hit.title, style = MaterialTheme.typography.titleMedium); Text(hit.author); Text("查看目录并下载", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(enabled = !running && page > 1, onClick = { search(page - 1, searched) }) { Text("上一页") }
                Text("第 $page 页", modifier = Modifier.padding(12.dp))
                TextButton(enabled = !running && page < found.pages, onClick = { search(page + 1, searched) }) { Text("下一页") }
            }
        }
        book?.let { selected ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                fun download(count: Int) {
                    val title = selectedHit!!.title
                    model.startStreaming(title) { publish, progress -> client.download(selected, count, progress, publish) }
                    status = "首批章节下载完成后即可开始阅读，后续章节继续下载。"
                }
                Button(enabled = !running && !model.busy, onClick = { download(minOf(10, selected.chapters.size)) }) { Text("先下载 10 章") }
                OutlinedButton(enabled = !running && !model.busy, onClick = { download(selected.chapters.size) }) { Text("下载全部") }
            }
            LazyColumn(Modifier.weight(1f)) { items(selected.chapters, key = { it.id }) { chapter -> Text(chapter.title + if (chapter.paid) "（付费）" else "", modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) } }
        }
    }
}
