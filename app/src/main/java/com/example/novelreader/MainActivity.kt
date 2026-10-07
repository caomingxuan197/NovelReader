package com.example.novelreader

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

private const val MAX_BYTES = 100 * 1024 * 1024
data class Book(val id: String, val title: String, val added: Long, val author: String = "", val cached: Int = 0, val total: Int = 0)
data class Chapter(val title: String, val paragraphIndex: Int)
data class Reading(val book: Book, val paragraphs: List<String>, val index: Int, val offset: Int,
    val chapters: List<Chapter>, val character: Int = 0, val bytes: Long = 0)

private val chapterHeading = Regex("^(?:第[零〇一二三四五六七八九十百千万两壹贰叁肆伍陆柒捌玖拾佰仟0-9０-９]+[章卷回部节集季篇]|chapter\\s+[0-9ivxlcdm]+\\b|(?:序章|楔子|引子|序言|前言|后记|尾声|终章|番外)(?:$|[\\s：:、（(]|[一二三四五六七八九十0-9]))", RegexOption.IGNORE_CASE)

internal fun duplicateChapterTitles(paragraphs: List<String>): Set<Int> {
    val duplicates = hashSetOf<Int>()
    var previous = ""
    paragraphs.forEachIndexed { index, paragraph ->
        val title = paragraph.trim().removePrefix("\uFEFF").trim()
        if (title.isNotBlank()) {
            val heading = title.length in 2..80 && chapterHeading.containsMatchIn(title) && title.none { it in "。！？!?；;" }
            val normalized = title.filterNot { it.isWhitespace() || Character.isSpaceChar(it) || it == '\u200B' || it == '\uFEFF' }
            if (heading && previous == normalized) duplicates.add(index)
            previous = if (heading) normalized else ""
        }
    }
    return duplicates
}

internal fun findChapters(paragraphs: List<String>): List<Chapter> {
    val duplicates = duplicateChapterTitles(paragraphs)
    return paragraphs.mapIndexedNotNull { index, paragraph ->
        val title = paragraph.trim().removePrefix("\uFEFF").trim()
        if (index !in duplicates && title.length in 2..80 && chapterHeading.containsMatchIn(title) &&
            title.none { it in "。！？!?；;" }) Chapter(title, index) else null
    }
}

internal fun chapterAt(chapters: List<Chapter>, paragraph: Int): Int {
    var low = 0
    var high = chapters.lastIndex
    var result = -1
    while (low <= high) {
        val middle = (low + high) ushr 1
        if (chapters[middle].paragraphIndex <= paragraph) { result = middle; low = middle + 1 }
        else high = middle - 1
    }
    return result
}

class ReaderModel(app: Application) : AndroidViewModel(app) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val folder = File(app.filesDir, "books").apply { mkdirs() }
    private val prefs = app.getSharedPreferences("reader", 0)
    private val pageStore = PageCacheStore(File(app.filesDir, "pagination"))
    private val memoryPages = linkedMapOf<String, List<ReadingPage>>()
    private val bookLock = Mutex()
    var downloadStatus by mutableStateOf(""); private set
    var downloading by mutableStateOf(false); private set
    var downloadingBook by mutableStateOf<Book?>(null); private set
    var downloadTitle by mutableStateOf(""); private set
    var cachedChapters by mutableStateOf(0); private set
    var totalChapters by mutableStateOf(0); private set
    fun readingLabel(book: Book): String = prefs.getString("${book.id}.chapterLabel", null) ?: "尚未开始阅读"
    private fun rememberChapter(id: String, paragraph: Int) {
        val current = reading?.takeIf { it.book.id == id } ?: return
        val position = chapterAt(current.chapters, paragraph)
        val label = if (position >= 0) current.chapters[position].title else "正文开头"
        prefs.edit().putString("$id.chapterLabel", label).apply()
    }
    val availableBytes = mutableStateMapOf<String, Long>()
    private var downloadJob: Job? = null
    fun stopDownload() { downloadJob?.cancel() }
    fun startStreaming(title: String, author: String = "", total: Int = 0, action: suspend (suspend (String, Int) -> Unit, (String) -> Unit) -> Unit) {
        if (downloading) { notice = "已有一本书正在下载，请先完成或停止。"; return }
        downloading = true; downloadingBook = null; downloadStatus = "正在准备下载…"
        downloadTitle = title; cachedChapters = 0; totalChapters = total
        downloadJob = scope.launch {
            val book = Book(UUID.randomUUID().toString(), title.take(100), System.currentTimeMillis(), author, total = total)
            val file = File(folder, "${book.id}.txt")
            try {
                action({ addition, addedChapters ->
                    val length = bookLock.withLock { withContext(Dispatchers.IO) {
                        require(file.length() + addition.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "小说超过 100 MB。" }
                        val original = file.length()
                        try { file.appendText(addition) }
                        catch (e: Exception) { java.io.RandomAccessFile(file, "rw").use { it.setLength(original) }; throw e }
                        val metadata = File(folder, "${book.id}.json")
                        val json = if (metadata.exists()) JSONObject(metadata.readText()) else JSONObject().put("title", book.title).put("added", book.added)
                        val count = cachedChapters + addedChapters
                        json.put("author", author).put("cached", count).put("total", total)
                        val atomic = android.util.AtomicFile(metadata)
                        val stream = atomic.startWrite()
                        try { stream.write(json.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
                        catch (e: Exception) { atomic.failWrite(stream); throw e }
                        file.length()
                    } }
                    availableBytes[book.id] = length
                    cachedChapters += addedChapters
                    if (downloadingBook?.id != book.id) downloadingBook = book
                    refresh()
                }, { downloadStatus = it })
                downloadStatus = "下载完成：${downloadingBook?.title ?: book.title}"
            } catch (e: CancellationException) { downloadStatus = "下载已停止，已下载内容仍可阅读。"; throw e }
            catch (e: Exception) { downloadStatus = "下载中断：${e.message}。已下载内容已保留。" }
            finally { downloading = false; downloadJob = null }
        }
    }
    suspend fun paginated(reading: Reading, layout: String, build: suspend () -> List<ReadingPage>): List<ReadingPage> {
        val file = File(folder, "${reading.book.id}.txt")
        val key = "${reading.book.id}|${reading.bytes}|$layout"
        memoryPages[key]?.let { return it }
        val cached = withContext(Dispatchers.IO) { pageStore.read(key) }
        val result = cached ?: build().also { pages -> withContext(Dispatchers.IO) { pageStore.write(key, pages) } }
        memoryPages[key] = result
        while (memoryPages.size > 2) memoryPages.remove(memoryPages.keys.first())
        return result
    }
    var books by mutableStateOf<List<Book>>(emptyList()); private set
    var reading by mutableStateOf<Reading?>(null); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    var online by mutableStateOf(false)
    var aaaOnline by mutableStateOf(false)
    var aaaQuery by mutableStateOf("")
    var notice by mutableStateOf<String?>(null)
    var night by mutableStateOf(prefs.getBoolean("night", false)); private set
    var fontFace by mutableStateOf(prefs.getInt("fontFaceSystemDefault", 4).coerceIn(0, 4)); private set
    fun changeFontFace(value: Int) { fontFace = value.coerceIn(0, 4); prefs.edit().putInt("fontFaceSystemDefault", fontFace).apply() }
    var fontSize by mutableStateOf(prefs.getInt("font", 20).coerceIn(14, 32)); private set
    var paper by mutableStateOf(prefs.getInt("paper", 0).coerceIn(0, 4)); private set
    fun changePaper(value: Int) { paper = value.coerceIn(0, 4); prefs.edit().putInt("paper", paper).apply(); changeNight(false) }

    init { task { refresh() } }
    private fun task(action: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "操作失败，请重试。" }
            finally { busy = false }
        }
    }
    private suspend fun refresh() {
        books = withContext(Dispatchers.IO) {
            folder.listFiles().orEmpty().filter { it.extension == "json" }.mapNotNull { file ->
                runCatching {
                    val json = JSONObject(file.readText())
                    Book(file.nameWithoutExtension, json.getString("title"), json.getLong("added"), json.optString("author"), json.optInt("cached"), json.optInt("total"))
                }.getOrNull()
            }.sortedByDescending { it.added }
        }
    }
    fun importFile(uri: Uri) = task {
        withContext(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: "导入的小说.txt"
            require(name.endsWith(".txt", true)) { "请选择 TXT 文本文件。" }
            val staged = File.createTempFile("novel-import-", ".tmp", getApplication<Application>().cacheDir)
            val id = UUID.randomUUID().toString()
            val destination = File(folder, "$id.txt")
            val metadata = File(folder, "$id.json")
            try {
                resolver.openInputStream(uri)?.use { input ->
                    staged.outputStream().buffered().use { output ->
                        val buffer = ByteArray(65536)
                        var total = 0L
                        while (true) {
                            ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= MAX_BYTES) { "文件超过 100 MB，已停止导入。" }
                            output.write(buffer, 0, count)
                        }
                    }
                } ?: error("无法打开这个文件，请先下载到手机。")
                transcodeNovelFile(staged, destination)
                ensureActive()
                metadata.writeText(JSONObject().put("title", name.dropLast(4).take(100))
                    .put("added", System.currentTimeMillis()).toString())
            } catch (e: Exception) {
                destination.delete(); metadata.delete(); throw e
            } finally {
                staged.delete()
                }
        }
        refresh()
    }
    fun importDownloaded(name: String, bytes: ByteArray) = task {
        val text = withContext(Dispatchers.Default) { validateDownloadedText(name, bytes) }
        saveBook(name.dropLast(4), text)
        online = false
        notice = "已加入书架：${name.dropLast(4)}"
    }
    private suspend fun saveBook(title: String, content: String) {
        withContext(Dispatchers.IO) {
            require(content.isNotBlank()) { "小说内容不能为空。" }
            require(!content.contains('\u0000')) { "内容包含异常字符，请确认是 TXT 文本文件。" }
            val id = UUID.randomUUID().toString()
            val textFile = File(folder, "$id.txt")
            val metadata = File(folder, "$id.json")
            try {
                textFile.writeText(content.replace("\r\n", "\n").replace('\r', '\n').removePrefix("\uFEFF"))
                metadata.writeText(JSONObject().put("title", title.trim().take(100).ifEmpty { "未命名小说" })
                    .put("added", System.currentTimeMillis()).toString())
            } catch (e: Exception) { textFile.delete(); metadata.delete(); throw e }
        }
        refresh()
    }
    fun open(book: Book) = task {
        val snapshot = bookLock.withLock { withContext(Dispatchers.IO) {
            val file = File(folder, "${book.id}.txt")
            val paragraphs = file.useLines { lines ->
                lines.filter { it.isNotBlank() }.flatMap { it.trim().chunked(1000).asSequence() }.toList()
            }
            paragraphs to file.length()
        } }
        val paragraphs = snapshot.first
        require(paragraphs.isNotEmpty()) { "这本书没有可阅读的内容。" }
        val chapters = withContext(Dispatchers.Default) { findChapters(paragraphs) }
        reading = Reading(book, paragraphs, prefs.getInt("${book.id}.index", 0).coerceIn(0, paragraphs.lastIndex),
            prefs.getInt("${book.id}.offset", 0).coerceAtLeast(0), chapters,
            prefs.getInt("${book.id}.character", 0).coerceAtLeast(0), snapshot.second)
    }
    fun progress(id: String, index: Int, offset: Int) {
        rememberChapter(id, index)
        prefs.edit().putInt("$id.index", index).putInt("$id.offset", offset).apply()
    }
    fun pageProgress(id: String, paragraph: Int, character: Int) {
        rememberChapter(id, paragraph)
        prefs.edit().putInt("$id.index", paragraph).putInt("$id.offset", 0).putInt("$id.character", character).apply()
    }
    fun close() { reading = null }
    fun rename(book: Book, newTitle: String) = task {
        val title = newTitle.trim()
        require(title.isNotEmpty() && title.length <= 100) { "书名请输入 1～100 个字。" }
        bookLock.withLock { withContext(Dispatchers.IO) {
            val file = File(folder, "${book.id}.json")
            val metadata = JSONObject(file.readText()).put("title", title)
            val atomic = android.util.AtomicFile(file)
            val output = atomic.startWrite()
            try {
                output.write(metadata.toString().toByteArray(Charsets.UTF_8))
                atomic.finishWrite(output)
            } catch (e: Exception) { atomic.failWrite(output); throw e }
        } }
        val updated = book.copy(title = title)
        if (downloadingBook?.id == book.id) downloadingBook = updated
        reading?.takeIf { it.book.id == book.id }?.let { reading = it.copy(book = updated) }
        refresh()
    }
    fun delete(book: Book) = task {
        require(!downloading || downloadingBook?.id != book.id) { "请先停止这本书的下载，再删除。" }
        withContext(Dispatchers.IO) {
            val metadata = File(folder, "${book.id}.json")
            check(!metadata.exists() || metadata.delete()) { "删除失败，请重试。" }
            File(folder, "${book.id}.txt").delete()
            pageStore.removeBook(book.id)
            prefs.edit().remove("${book.id}.index").remove("${book.id}.offset").remove("${book.id}.character").remove("${book.id}.chapterLabel").apply()
        }
        memoryPages.keys.filter { it.startsWith("${book.id}|") }.forEach { memoryPages.remove(it) }
        refresh()
    }
    fun changeNight(value: Boolean) { night = value; prefs.edit().putBoolean("night", value).apply() }
    fun setFont(value: Int) { fontSize = value.coerceIn(14, 32); prefs.edit().putInt("font", fontSize).apply() }
    override fun onCleared() { scope.cancel() }
}

internal fun decodeNovel(bytes: ByteArray): String {
    if (bytes.size >= 2) {
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, Charsets.UTF_16LE).removePrefix("\uFEFF")
        if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, Charsets.UTF_16BE).removePrefix("\uFEFF")
    }
    return try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
    } catch (_: java.nio.charset.CharacterCodingException) {
        charset("GB18030").newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val model = ViewModelProvider(this)[ReaderModel::class.java]
        setContent { ReaderApp(model) }
    }
}

@Composable
private fun WelcomeScreen() {
    val paper = Color(0xFFF6F3EA)
    val ink = Color(0xFF40554C)
    Surface(Modifier.fillMaxSize(), color = paper) {
        Box(Modifier.fillMaxSize()) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val w = size.width; val h = size.height
                fun mountain(points: List<Pair<Float, Float>>, color: Color) {
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(0f, h)
                        points.forEach { (x,y) -> lineTo(x*w, y*h) }
                        lineTo(w,h); close()
                    }
                    drawPath(path, color)
                }
                mountain(listOf(0f to .78f,.12f to .75f,.23f to .77f,.38f to .69f,.48f to .76f,.61f to .70f,.74f to .77f,.90f to .72f,1f to .78f), Color(0xFFDDE3DE))
                mountain(listOf(0f to .91f,.13f to .84f,.20f to .88f,.34f to .77f,.40f to .85f,.46f to .81f,.55f to .90f,.72f to .85f,.86f to .88f,1f to .83f), Color(0xFFBCCBC3).copy(alpha=.60f))
                mountain(listOf(0f to .96f,.10f to .93f,.16f to .88f,.23f to .95f,.40f to .96f,.57f to .93f,.68f to .88f,.77f to .94f,.89f to .90f,1f to .98f), Color(0xFF829A8D).copy(alpha=.40f))
                drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(paper.copy(alpha=0f),paper.copy(alpha=.78f),paper.copy(alpha=0f)), startY=h*.8f, endY=h), topLeft=androidx.compose.ui.geometry.Offset(0f,h*.8f), size=androidx.compose.ui.geometry.Size(w,h*.2f))
                val stem = androidx.compose.ui.graphics.Path().apply { moveTo(-w*.03f,h*.09f); quadraticBezierTo(w*.15f,h*.13f,w*.39f,h*.25f) }
                drawPath(stem,ink.copy(alpha=.36f),style=androidx.compose.ui.graphics.drawscope.Stroke(width=2.dp.toPx()))
                for (i in 0..7) {
                    val x=w*(.025f+i*.041f);val y=h*(.112f+i*.016f)
                    for (side in listOf(-1f,1f)) {
                        val leaf=androidx.compose.ui.graphics.Path().apply {
                            moveTo(x,y)
                            quadraticBezierTo(x+w*.04f,y+side*h*.004f,x+w*.065f,y+side*h*.031f)
                            quadraticBezierTo(x+w*.025f,y+side*h*.020f,x,y)
                            close()
                        }
                        drawPath(leaf,ink.copy(alpha=.20f+(i%3)*.035f))
                    }
                }
            }
            Column(Modifier.align(Alignment.Center).padding(bottom = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("悦\n读", color = ink, fontSize = 54.sp, lineHeight = 76.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Serif)
                Spacer(Modifier.height(22.dp))
                Text("把故事留在身边", color = ink.copy(alpha=.7f), fontSize = 15.sp, letterSpacing = 4.sp)
            }
            Text("悦读 · 1.1\n制作者：草莓熊", Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom=24.dp), color=ink.copy(alpha=.7f), fontSize=12.sp, lineHeight=24.sp, textAlign=androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

@Composable
private fun ReaderApp(model: ReaderModel) {
    var welcome by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(Unit) { delay(1500); welcome = false }
    if (welcome) { WelcomeScreen(); return }
    val colors = if (model.night) darkColorScheme(primary = Color(0xFFADCDB8), background = Color(0xFF171C19))
        else lightColorScheme(primary = Color(0xFF365F49), background = Color(0xFFF7F3E9), surface = Color(0xFFFFFBF2))
    MaterialTheme(colorScheme = colors) {
        Surface(Modifier.fillMaxSize(), color = colors.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                val reading = model.reading
                if (model.aaaOnline) AaaLibrary(model)
                else if (model.online) OnlineBooks(model)
                else if (reading == null) Shelf(model) else key(reading.book.id) { Reader(model, reading) }
            }
            if (model.busy) AlertDialog(onDismissRequest = {}, confirmButton = {},
                title = { Text("正在处理") }, text = { Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp)); Text("请稍候…")
                } })
            model.error?.let { message -> AlertDialog(onDismissRequest = { model.error = null },
                title = { Text("提示") }, text = { Text(message) },
                confirmButton = { TextButton(onClick = { model.error = null }) { Text("知道了") } }) }
            model.notice?.let { message -> AlertDialog(onDismissRequest = { model.notice = null },
                text = { Text(message) }, confirmButton = { TextButton(onClick = { model.notice = null }) { Text("好的") } }) }
        }
    }
}

@Composable
private fun Shelf(model: ReaderModel) {
    var deleting by remember { mutableStateOf<Book?>(null) }
    var renaming by remember { mutableStateOf<Book?>(null) }
    var newTitle by rememberSaveable { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.importFile(uri)
    }
    var about by remember { mutableStateOf(false) }
    val homeContext = LocalContext.current
    val titleFont = remember(homeContext) {
        val file = "fonts/SanJiXingKai-Bold.ttf"
        FontFamily(android.graphics.Typeface.createFromAsset(homeContext.assets, file))
    }
    var searchFocused by remember { mutableStateOf(false) }
    var information by remember { mutableStateOf<Book?>(null) }
    val green = if (model.night) Color(0xFFADCDB8) else Color(0xFF3F6F5A)
    val ink = if (model.night) Color(0xFFE3E7DF) else Color(0xFF2F302D)
    val muted = if (model.night) Color(0xFFB0B8AA) else Color(0xFF737B70)
    val paper = if (model.night) Color(0xFF171C19) else Color(0xFFF7F4E8)
    val card = if (model.night) Color(0xFF252D26) else Color(0xFFFFFCF5)
    val soft = if (model.night) Color(0xFF303C32) else Color(0xFFEEF1E8)
    val round = RoundedCornerShape(22.dp)
    ProvideTextStyle(MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.SansSerif, color = ink)) {
      BoxWithConstraints(Modifier.fillMaxSize().background(paper)) {
        val headerHeight = (maxHeight * .21f).coerceAtLeast(144.dp)
        ShelfPaperBackdrop(model.night)
        LazyColumn(Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Row(Modifier.fillMaxWidth().heightIn(min = headerHeight).padding(top = 32.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("悦读", fontSize = 48.sp, fontFamily = titleFont, fontWeight = FontWeight.Normal, color = green)
                        Text("把故事留在身边", fontSize = 14.sp, color = muted, modifier = Modifier.padding(top = 10.dp))
                    }
                }
            }
            item {
                OutlinedTextField(value = model.aaaQuery, onValueChange = { model.aaaQuery = it },
                    modifier = Modifier.fillMaxWidth().onFocusChanged { searchFocused = it.isFocused }, singleLine = true, shape = round,
                    textStyle = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, color = ink),
                    placeholder = { Text("搜索书名或作者", color = muted) },
                    leadingIcon = { ShelfSearchIcon(green) },
                    trailingIcon = if (searchFocused) {
                        { TextButton(enabled = model.aaaQuery.isNotBlank() && !model.busy,
                            onClick = { model.aaaOnline = true }) { Text("搜索", color = green) } }
                    } else null,
                    colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = soft, focusedContainerColor = card,
                        unfocusedBorderColor = Color.Transparent, focusedBorderColor = green),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = {
                        if (model.aaaQuery.isNotBlank() && !model.busy) model.aaaOnline = true
                    }))
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick = { model.online = true }, enabled = !model.busy,
                        modifier = Modifier.weight(1f).heightIn(min = 50.dp), shape = round,
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = soft, contentColor = green)) { Text("在线找书") }
                    FilledTonalButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !model.busy,
                        modifier = Modifier.weight(1f).heightIn(min = 50.dp), shape = round,
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = soft, contentColor = green)) { Text("导入 TXT") }
                }
            }
            if (model.downloadStatus.isNotBlank()) item {
                Surface(color = card, shape = round, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (model.downloading) "正在缓存《${model.downloadingBook?.title ?: model.downloadTitle}》" else "下载记录",
                                Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                            if (model.downloading) TextButton(onClick = { model.stopDownload() }) { Text("停止", color = green) }
                        }
                        if (model.totalChapters > 0) {
                            val fraction = (model.cachedChapters.toFloat() / model.totalChapters).coerceIn(0f, 1f)
                            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth(), color = green, trackColor = soft)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("${model.cachedChapters} / ${model.totalChapters} 章", fontSize = 12.sp, color = muted)
                                Text("${(fraction * 100).toInt()}%", fontSize = 12.sp, color = muted)
                            }
                        }
                        if (!model.downloading || model.totalChapters == 0) Text(model.downloadStatus, fontSize = 12.sp, color = muted)
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("我的书架", Modifier.weight(1f), fontSize = 22.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold)
                    Text("${model.books.size} 本", color = muted, fontSize = 13.sp)
                }
            }
            if (model.books.isEmpty()) item {
                Surface(color = card, shape = round, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(24.dp)) {
                        Text("把第一本故事放进书架", fontSize = 18.sp, fontFamily = FontFamily.Serif)
                        Spacer(Modifier.height(8.dp))
                        Text("搜索喜欢的书，或导入手机里的 TXT。\n保存后，离线也能阅读。", color = muted, lineHeight = 24.sp)
                    }
                }
            }
            items(model.books, key = { it.id }) { book ->
                var expanded by remember { mutableStateOf(false) }
                Card(onClick = { model.open(book) }, enabled = !model.busy,
                    modifier = Modifier.fillMaxWidth(), shape = round,
                    colors = CardDefaults.cardColors(containerColor = card, contentColor = ink)) {
                    Row(Modifier.padding(start = 14.dp, top = 16.dp, end = 4.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Surface(Modifier.size(width = 70.dp, height = 102.dp), color = soft, shape = RoundedCornerShape(8.dp)) {
                            Column(Modifier.padding(9.dp), verticalArrangement = Arrangement.SpaceBetween) {
                                Text(book.title, color = green, fontFamily = titleFont, fontSize = 17.sp,
                                    lineHeight = 23.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                Text("悦读", color = muted, fontSize = 9.sp)
                            }
                        }
                        Column(Modifier.weight(1f).padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(book.title, fontFamily = titleFont, fontSize = 24.sp, fontWeight = FontWeight.Normal, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (book.author.isNotBlank()) Text(book.author, color = muted, fontSize = 12.sp)
                            Text(model.readingLabel(book), color = muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(if (book.total > 0) "已缓存 ${book.cached} / ${book.total} 章" else "已保存到本机", color = muted, fontSize = 12.sp)
                        }
                        Box {
                            IconButton(onClick = { expanded = true }, enabled = !model.busy,
                                modifier = Modifier.semantics { contentDescription = "书籍菜单：${book.title}" }) {
                                Text("⋮", fontSize = 24.sp, color = muted)
                            }
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                DropdownMenuItem(text = { Text("更改书名") }, onClick = { expanded = false; newTitle = book.title; renaming = book })
                                DropdownMenuItem(text = { Text("书籍信息") }, onClick = { expanded = false; information = book })
                                DropdownMenuItem(text = { Text("删除", color = Color(0xFFB8655B)) },
                                    enabled = !model.downloading || model.downloadingBook?.id != book.id,
                                    onClick = { expanded = false; deleting = book })
                            }
                        }
                    }
                }
            }

        }
        FilledTonalButton(onClick = { about = true },
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 12.dp).zIndex(2f),
            shape = RoundedCornerShape(20.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = card, contentColor = green)) {
            Text("关于", fontSize = 13.sp)
        }
    }
    }
    if (about) AlertDialog(onDismissRequest = { about = false }, title = { Text("关于悦读") },
        text = { Text("把故事留在身边\n\n版本 1.1\n制作者：草莓熊") },
        confirmButton = { TextButton(onClick = { about = false }) { Text("知道了") } })
    information?.let { book -> AlertDialog(onDismissRequest = { information = null }, title = { Text(book.title) },
        text = { Text("作者：${book.author.ifBlank { "暂无信息" }}\n${model.readingLabel(book)}\n" +
            if (book.total > 0) "已缓存 ${book.cached} / ${book.total} 章" else "本地 TXT 书籍") },
        confirmButton = { TextButton(onClick = { information = null }) { Text("关闭") } }) }
    renaming?.let { book -> AlertDialog(onDismissRequest = { renaming = null }, title = { Text("更改书名") },
        text = { OutlinedTextField(value = newTitle, onValueChange = { if (it.length <= 100) newTitle = it },
            label = { Text("书名") }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(enabled = newTitle.isNotBlank() && !model.busy,
            onClick = { model.rename(book, newTitle); renaming = null }) { Text("保存") } },
        dismissButton = { TextButton(onClick = { renaming = null }) { Text("取消") } }) }
    deleting?.let { book -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除这本书？") },
        text = { Text("将删除《${book.title}》在本应用中的副本和阅读进度，原 TXT 文件不受影响。") },
        confirmButton = { TextButton(onClick = { model.delete(book); deleting = null }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
}

@Composable
private fun ShelfPaperBackdrop(night: Boolean) {
    val context = LocalContext.current
    val bitmap = remember(context) {
        context.assets.open("home/background.png").use { android.graphics.BitmapFactory.decodeStream(it) }
    }
    androidx.compose.foundation.Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null, modifier = Modifier.fillMaxSize(),
        contentScale = androidx.compose.ui.layout.ContentScale.FillBounds)
    if (night) Box(Modifier.fillMaxSize().background(Color(0xFF101A14).copy(alpha = .86f)))
}

@Composable
private fun ShelfSearchIcon(color: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(22.dp)) {
        drawCircle(color, radius = size.minDimension * .30f,
            center = androidx.compose.ui.geometry.Offset(size.width * .42f, size.height * .42f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
        drawLine(color, androidx.compose.ui.geometry.Offset(size.width * .64f, size.height * .64f),
            androidx.compose.ui.geometry.Offset(size.width * .9f, size.height * .9f), strokeWidth = 2.dp.toPx(),
            cap = androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

private val readerFontNames = listOf("正楷", "宋体", "黑体", "行书", "系统默认")

private fun readerFontFamily(context: Context, choice: Int): FontFamily {
    if (choice == 4) return FontFamily.Default
    if (choice == 2) return FontFamily.SansSerif
    val path = when (choice) {
        1 -> "fonts/YueDuSerif-Regular.ttf"
        3 -> "fonts/ZhiMangXing-Regular.ttf"
        else -> "fonts/TaiwanMOE-Kai.ttf"
    }
    return FontFamily(android.graphics.Typeface.createFromAsset(context.assets, path))
}

private val paperColors = listOf(Color(0xFFF7F3E9), Color(0xFFE1EBDD), Color(0xFFFFFFFF), Color(0xFFE4E8EC), Color(0xFFF3E2D9))
private val paperNames = listOf("米色", "浅绿", "纯白", "灰蓝", "浅粉")
data class ReadingLine(val text: String, val paragraph: Int, val character: Int, val heading: Boolean = false)
data class ReadingPage(val lines: List<ReadingLine>) {
    val anchor: ReadingLine get() = lines.first()
}

internal fun packPages(lines: List<ReadingLine>, capacity: Int, chapters: Set<Int>): List<ReadingPage> {
    require(capacity > 0)
    val result = mutableListOf<ReadingPage>()
    var current = mutableListOf<ReadingLine>()
    fun flush() {
        if (current.any { it.text.isNotEmpty() }) result.add(ReadingPage(current.toList()))
        current = mutableListOf()
    }
    for (line in lines) {
        if (line.character == 0 && line.paragraph in chapters && line.text.isNotEmpty()) flush()
        if (current.size == capacity) flush()
        if (current.isEmpty() && line.text.isEmpty()) continue
        current.add(line)
    }
    flush()
    return result
}

internal fun pageFor(pages: List<ReadingPage>, paragraph: Int, character: Int): Int {
    var low = 0
    var high = pages.lastIndex
    var answer = 0
    while (low <= high) {
        val mid = (low + high) ushr 1
        val anchor = pages[mid].anchor
        if (anchor.paragraph < paragraph || (anchor.paragraph == paragraph && anchor.character <= character)) {
            answer = mid; low = mid + 1
        } else high = mid - 1
    }
    return answer
}

@Composable
private fun DeviceStatus(): String {
    val context = LocalContext.current
    var battery by remember { mutableStateOf<Int?>(null) }
    var time by remember { mutableStateOf("") }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                battery = if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else null
            }
        }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        onDispose { context.unregisterReceiver(receiver) }
    }
    LaunchedEffect(Unit) {
        while (isActive) {
            time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
            delay(1000)
        }
    }
    return "电量 ${battery?.toString() ?: "--"}%   $time"
}

@Composable
private fun ColumnScope.Reader(model: ReaderModel, reading: Reading) {
    val owner = LocalLifecycleOwner.current
    val view = LocalView.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val measurer = rememberTextMeasurer(cacheSize = 0)
    var settings by rememberSaveable { mutableStateOf(false) }
    var contents by rememberSaveable { mutableStateOf(false) }
    var controls by rememberSaveable { mutableStateOf(false) }
    var pages by remember { mutableStateOf<List<ReadingPage>>(emptyList()) }
    var ready by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf(false) }
    var anchorParagraph by remember { mutableIntStateOf(reading.index) }
    var anchorCharacter by remember { mutableIntStateOf(reading.character) }
    val pager = rememberPagerState { pages.size }
    var tapAnimating by remember { mutableStateOf(false) }
    val pageClicks = remember(reading.bytes, model.fontSize, model.fontFace) { kotlinx.coroutines.channels.Channel<Int>(kotlinx.coroutines.channels.Channel.UNLIMITED) }
    DisposableEffect(pageClicks) { onDispose { pageClicks.close() } }
    LaunchedEffect(pageClicks, ready) {
        for (direction in pageClicks) {
            if (ready && pager.pageCount > 0) {
                val target = (pager.currentPage + direction).coerceIn(0, pager.pageCount - 1)
                if (target != pager.currentPage) {
                    tapAnimating = true
                    try { pager.animateScrollToPage(target, animationSpec = androidx.compose.animation.core.tween(180)) }
                    finally { tapAnimating = false }
                }
            }
        }
    }
    val status = DeviceStatus()
    val chapterIndices = remember(reading) { reading.chapters.map { it.paragraphIndex }.toSet() }
    val duplicateTitles = remember(reading.paragraphs) { duplicateChapterTitles(reading.paragraphs) }
    val currentChapter by remember(reading.chapters) { derivedStateOf { chapterAt(reading.chapters, anchorParagraph) } }
    val background = if (model.night) Color(0xFF171C19) else paperColors[model.paper]
    val foreground = if (model.night) Color(0xFFD1D7D2) else Color(0xFF303730)
    val fontContext = LocalContext.current
    val readingFont = remember(fontContext, model.fontFace) { readerFontFamily(fontContext, model.fontFace) }
    val style = TextStyle(fontSize = model.fontSize.sp, lineHeight = (model.fontSize * 1.8f).sp,
        fontFamily = readingFont, letterSpacing = 0.sp,
        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
            androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
            androidx.compose.ui.text.style.LineHeightStyle.Trim.None))
    fun save() { model.pageProgress(reading.book.id, anchorParagraph, anchorCharacter) }
    LaunchedEffect(pager.settledPage, ready, model.availableBytes[reading.book.id], reading.bytes) {
        if (ready && pager.settledPage == pages.lastIndex && (model.availableBytes[reading.book.id] ?: 0) > reading.bytes) {
            save(); model.open(reading.book)
        }
    }
    fun jump(chapter: Chapter) {
        if (!ready) return
        contents = false; controls = false
        scope.launch {
            pager.scrollToPage(pageFor(pages, chapter.paragraphIndex, 0))
            val anchor = pages[pager.currentPage].anchor
            anchorParagraph = anchor.paragraph; anchorCharacter = anchor.character; save()
        }
    }
    BackHandler {
        when { contents -> contents = false; settings -> settings = false; controls -> controls = false
            else -> { save(); model.close() } }
    }
    DisposableEffect(owner, reading.book.id) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) save() }
        owner.lifecycle.addObserver(observer)
        onDispose { save(); owner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(view) {
        val window = (view.context as? android.app.Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    LaunchedEffect(pager, ready, pages) {
        if (ready) snapshotFlow { pager.settledPage }.collect { page ->
            pages.getOrNull(page)?.anchor?.let {
                anchorParagraph = it.paragraph; anchorCharacter = it.character; save()
            }
        }
    }
    Surface(Modifier.fillMaxSize(), color = background, contentColor = foreground) {
        Box(Modifier.fillMaxSize().pointerInput(ready, pageClicks) {
            detectTapGestures { position ->
                if (ready) when (tapRegion(position.x, size.width.toFloat())) {
                    -1 -> { controls = false; pageClicks.trySend(-1) }
                    1 -> { controls = false; pageClicks.trySend(1) }
                    else -> controls = !controls
                }
            }
        }) {
            Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 12.dp)) {
                val chapter = reading.chapters.getOrNull(currentChapter)
                Text(if (chapter == null) "正文开头" else "${currentChapter + 1}/${reading.chapters.size}  ${chapter.title}",
                    fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = foreground.copy(alpha = 0.65f), modifier = Modifier.padding(bottom = 14.dp))
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val width = constraints.maxWidth
                    val height = constraints.maxHeight
                    val lineHeight = with(density) { (model.fontSize * 1.8f).sp.toPx() }
                    val lineDp = with(density) { lineHeight.toDp() }
                    LaunchedEffect(width, height, model.fontSize, model.fontFace, density.density, density.fontScale, reading.bytes) {
                        if (width <= 0 || height <= 0) return@LaunchedEffect
                        val savedParagraph = anchorParagraph
                        val savedCharacter = anchorCharacter
                        ready = false; failure = false
                        try {
                            // Let fullscreen inset animations settle before choosing the cache key.
                            delay(250)
                            val layoutKey = "pages-v7-taiwan-kai|${model.fontFace}|$width|$height|${model.fontSize}|${density.density}|${density.fontScale}|${android.os.Build.FINGERPRINT}"
                            val result = model.paginated(reading, layoutKey) { withContext(Dispatchers.Default) {
                                val lines = ArrayList<ReadingLine>()
                                reading.paragraphs.forEachIndexed { paragraphIndex, paragraph ->
                                    ensureActive()
                                    if (paragraphIndex in duplicateTitles) return@forEachIndexed
                                    val heading = paragraphIndex in chapterIndices
                                    val layout = measurer.measure(paragraph,
                                        style = style.copy(fontWeight = if (heading) FontWeight.Bold else FontWeight.Normal),
                                        constraints = Constraints(maxWidth = width))
                                    for (line in 0 until layout.lineCount) {
                                        val start = layout.getLineStart(line)
                                        val end = if (line + 1 < layout.lineCount) layout.getLineStart(line + 1) else paragraph.length
                                        lines.add(ReadingLine(paragraph.substring(start, end), paragraphIndex, start, heading))
                                    }
                                    lines.add(ReadingLine("", paragraphIndex, paragraph.length))
                                }
                                packPages(lines, ((height - 2) / lineHeight).toInt().coerceAtLeast(1), chapterIndices)
                            } }
                            pages = result
                            if (result.isNotEmpty()) {
                                // The pager is not composed while ready is false. A suspending
                                // scrollToPage would wait forever for its first layout here.
                                pager.requestScrollToPage(pageFor(result, savedParagraph, savedCharacter))
                                ready = true
                            } else failure = true
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { failure = true }
                    }
                    if (ready) {
                        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(),
                            beyondViewportPageCount = 1, userScrollEnabled = !tapAnimating,
                            verticalAlignment = Alignment.Top) { page ->
                            Column(Modifier.fillMaxSize().zIndex(-page.toFloat()).graphicsLayer {
                                val offset = (pager.currentPage - page) + pager.currentPageOffsetFraction
                                translationX = if (offset < 0f) offset * size.width else 0f
                                shadowElevation = 0f
                            }.background(background)) {
                                pages[page].lines.forEach { line ->
                                    Text(line.text, modifier = Modifier.fillMaxWidth().height(lineDp),
                                        style = style, fontWeight = if (line.heading) FontWeight.Bold else FontWeight.Normal,
                                        softWrap = false, maxLines = 1, color = foreground)
                                }
                            }
                        }
                    } else Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (failure) {
                            Text("分页失败，请返回书架后重试。")
                            TextButton(onClick = { save(); model.close() }) { Text("返回书架") }
                        } else {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(12.dp))
                            Text("正在加载…", color = foreground)
                            Spacer(Modifier.height(8.dp))
                            Text(if (model.downloading && model.downloadingBook?.id == reading.book.id)
                                "下载时进入会稍慢，请耐心等候" else "首次进入时会较慢，请耐心等候",
                                color = foreground.copy(alpha = 0.7f), fontSize = 13.sp)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    val progress = if (ready && pages.isNotEmpty()) ((pager.settledPage + 1) * 100f / pages.size) else null
                    val progressLabel = if ((model.downloading && model.downloadingBook?.id == reading.book.id) || (model.availableBytes[reading.book.id] ?: 0) > reading.bytes) "已载入内容" else "阅读进度"
                    Text(if (progress == null) "$progressLabel —" else "$progressLabel ${String.format(java.util.Locale.ROOT, "%.1f", progress)}%",
                        fontSize = 11.sp, color = foreground.copy(alpha = 0.65f))
                    Text(status, fontSize = 11.sp, color = foreground.copy(alpha = 0.65f))
                }
            }
            androidx.compose.animation.AnimatedVisibility(visible = controls && ready, modifier = Modifier.align(Alignment.TopCenter),
                enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { -it / 2 },
                exit = fadeOut(tween(220)) + slideOutVertically(tween(220)) { -it / 2 }) {
                Surface(Modifier.fillMaxWidth(), color = background, tonalElevation = 0.dp, shadowElevation = 0.dp) {
                    Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { save(); model.close() }) { Text("〈 书架") }
                        TextButton(onClick = { settings = true }) { Text("阅读设置") }
                        if ((model.availableBytes[reading.book.id] ?: 0) > reading.bytes) TextButton(onClick = { save(); model.open(reading.book) }) { Text("更新章节") }
                    }
                }
            }
            androidx.compose.animation.AnimatedVisibility(visible = controls && ready, modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 2 },
                exit = fadeOut(tween(220)) + slideOutVertically(tween(220)) { it / 2 }) {
                Surface(Modifier.fillMaxWidth(), color = background, tonalElevation = 0.dp, shadowElevation = 0.dp) {
                    Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        TextButton(onClick = { jump(reading.chapters[currentChapter - 1]) }, enabled = currentChapter > 0) { Text("上一章") }
                        TextButton(onClick = { contents = true }) { Text("目录") }
                        TextButton(onClick = { jump(reading.chapters[currentChapter + 1]) },
                            enabled = currentChapter < reading.chapters.lastIndex) { Text("下一章") }
                    }
                }
            }
        }
    }
    if (contents) {
        val directoryState = rememberLazyListState(currentChapter.coerceAtLeast(0))
        AlertDialog(onDismissRequest = { contents = false }, title = { Text("章节目录 · ${reading.chapters.size}") },
            text = {
                if (reading.chapters.isEmpty()) Text("没有识别到章节标题，仍可左右翻页阅读。")
                else LazyColumn(state = directoryState, modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp)) {
                    items(reading.chapters, key = { it.paragraphIndex }) { chapter ->
                        val selected = chapter == reading.chapters.getOrNull(currentChapter)
                        Surface(color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
                            Text((if (selected) "正在阅读 · " else "") + chapter.title,
                                Modifier.fillMaxWidth().clickable { jump(chapter) }.padding(12.dp),
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            }, confirmButton = { TextButton(onClick = { contents = false }) { Text("关闭") } })
    }
    if (settings) AlertDialog(onDismissRequest = { settings = false; controls = false }, title = { Text("阅读设置") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("正文字体")
            readerFontNames.indices.toList().chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { index ->
                        OutlinedButton(onClick = { save(); model.changeFontFace(index) }, modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Text((if (model.fontFace == index) "✓ " else "") + readerFontNames[index])
                        }
                    }
                }
            }
            Text("清风翻书页，山月照归人。", fontFamily = readingFont, fontSize = 20.sp, lineHeight = 30.sp)
            Text("字号：${model.fontSize}")
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedButton(onClick = { model.setFont(model.fontSize - 2) }, enabled = model.fontSize > 14) { Text("A−") }
                OutlinedButton(onClick = { model.setFont(model.fontSize + 2) }, enabled = model.fontSize < 32) { Text("A＋") }
            }
            Text("背景颜色")
            paperColors.indices.toList().chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { index ->
                        OutlinedButton(onClick = { model.changePaper(index) }, contentPadding = PaddingValues(horizontal = 10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = paperColors[index], contentColor = Color(0xFF303730))) {
                            Text((if (!model.night && model.paper == index) "✓" else "") + paperNames[index])
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("夜间模式", Modifier.padding(top = 12.dp))
                Switch(checked = model.night, onCheckedChange = { model.changeNight(it) })
            }
        }
    }, confirmButton = { TextButton(onClick = { settings = false; controls = false }) { Text("完成") } })
}

internal fun validateDownloadedText(name: String, bytes: ByteArray): String {
    require(name.endsWith(".txt", true)) { "只支持 TXT 文件，请在网页中选择 TXT 下载。" }
    require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "文件为空或超过 100 MB，无法导入。" }
    val text = decodeNovel(bytes)
    val head = text.trimStart().take(512).lowercase(java.util.Locale.ROOT)
    require(!head.startsWith("<!doctype html") && !head.startsWith("<html") && !head.startsWith("<script")) {
        "下载到的是网页而不是 TXT，请在网盘页面重新点击普通下载。"
    }
    require(text.isNotBlank() && !text.contains('\u0000')) { "不是有效的 TXT 文本。" }
    return text
}

private suspend fun downloadTxt(url: String, userAgent: String, referer: String): Pair<String, ByteArray> = withContext(Dispatchers.IO) {
    var address = java.net.URL(url)
    repeat(6) {
        ensureActive()
        require(address.protocol == "https") { "此下载不是 HTTPS 链接，请使用浏览器下载后导入。" }
        val connection = address.openConnection() as java.net.HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            connection.setRequestProperty("User-Agent", userAgent)
            // Send only the origin as referrer; never forward the share-page password.
            runCatching { java.net.URL(referer) }.getOrNull()?.let {
                connection.setRequestProperty("Referer", "${it.protocol}://${it.authority}/")
            }
            android.webkit.CookieManager.getInstance().getCookie(address.toString())?.let {
                connection.setRequestProperty("Cookie", it)
            }
            val code = connection.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val location = connection.getHeaderField("Location") ?: error("下载跳转地址无效。")
                address = java.net.URL(address, location)
            } else {
                check(code == 200) { "下载失败（$code），请回到网盘页面重试。" }
                require(connection.contentLengthLong <= MAX_BYTES) { "当前支持最大 100 MB 的 TXT 文件。" }
                val disposition = connection.getHeaderField("Content-Disposition") ?: ""
                val encoded = Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE).find(disposition)?.groupValues?.get(1)
                val name = (encoded?.let { java.net.URLDecoder.decode(it.trim(), "UTF-8") }
                    ?: android.webkit.URLUtil.guessFileName(address.toString(), disposition, connection.contentType))
                    .substringAfterLast('/').substringAfterLast('\\')
                val output = java.io.ByteArrayOutputStream()
                connection.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= MAX_BYTES) { "文件超过 100 MB，已停止下载。" }
                        output.write(buffer, 0, count)
                    }
                }
                return@withContext name to output.toByteArray()
            }
        } finally { connection.disconnect() }
    }
    error("下载跳转次数过多，请在浏览器中下载后导入。")
}

// This bridge can only offer a web-owned blob for import; it cannot read or write local files.
private class TxtBlobBridge(private val offer: (String, String) -> Unit) {
    @android.webkit.JavascriptInterface
    fun offerBlob(url: String, name: String) { offer(url, name) }
}

private suspend fun android.webkit.WebView.evaluate(script: String): String =
    suspendCancellableCoroutine { continuation ->
        evaluateJavascript(script) { result -> if (continuation.isActive) continuation.resume(result ?: "null", null) }
    }

private suspend fun readBrowserBlob(web: android.webkit.WebView, url: String): ByteArray {
    val token = "_txt_" + UUID.randomUUID().toString().replace("-", "")
    val key = JSONObject.quote(token)
    val quotedUrl = JSONObject.quote(url)
    try {
        web.evaluate("""
            (() => {
                window[$key] = {status: 'loading'};
                fetch($quotedUrl).then(r => r.blob()).then(async b => {
                    if (b.size > $MAX_BYTES) throw new Error('too large');
                    const bytes = new Uint8Array(await b.arrayBuffer());
                    if (window[$key]) window[$key] = {status: 'ready', bytes: bytes, size: bytes.length};
                }).catch(() => { if (window[$key]) window[$key] = {status: 'error'}; });
            })();
        """.trimIndent())
        val size = withTimeout(30000) {
            var count: Int? = null
            while (count == null) {
                val raw = web.evaluate("(() => { const x=window[$key]; return x ? {status:x.status,size:x.size||0} : {status:'error'}; })()")
                val state = JSONObject(raw)
                when (state.optString("status")) {
                    "ready" -> count = state.getInt("size")
                    "error" -> error("网页文件无法读取或超过 100 MB，请改用浏览器下载后导入。")
                    else -> delay(100)
                }
            }
            count
        }
        require(size in 1..MAX_BYTES) { "文件为空或超过 100 MB。" }
        val output = java.io.ByteArrayOutputStream(size)
        for (offset in 0 until size step 32768) {
            val end = minOf(offset + 32768, size)
            val raw = web.evaluate("btoa(String.fromCharCode.apply(null, window[$key].bytes.subarray($offset,$end)))")
            val value = org.json.JSONArray("[$raw]").getString(0)
            output.write(android.util.Base64.decode(value, android.util.Base64.DEFAULT))
        }
        return output.toByteArray()
    } finally {
        runCatching { web.evaluateJavascript("delete window[$key];", null) }
    }
}

// Android WebView lacks the desktop file-save picker. Keep the file entirely in the
// web page, then hand its blob URL to the same bounded TXT importer as normal downloads.
private val fileSaveAdapter = """
    (() => {
        if (window.__readerSaveAdapter) return;
        window.__readerSaveAdapter = true;
        document.addEventListener('click', function(event) {
            const a = event.target && event.target.closest && event.target.closest('a[download]');
            if (a && a.href.startsWith('blob:') && a.download.toLowerCase().endsWith('.txt')) {
                event.preventDefault();
                TxtBlobImport.offerBlob(a.href, a.download);
            }
        }, true);
        window.showSaveFilePicker = async function(options) {
            let chunks = [], size = 0, finished = false;
            const name = options && options.suggestedName || '下载的小说.txt';
            return {
                kind: 'file', name: name,
                createWritable: async function() {
                    const sink = {
                        write: async function(value) {
                            if (finished) throw new Error('closed');
                            if (value && typeof value === 'object' && 'type' in value && !(value instanceof Blob) && !ArrayBuffer.isView(value)) {
                                if (value.type !== 'write' || (value.position != null && value.position !== size)) throw new Error('Unsupported file operation');
                                value = value.data;
                            }
                            const chunk = new Blob([value]);
                            size += chunk.size;
                            if (size > 104857600) throw new Error('TXT exceeds 100 MB');
                            chunks.push(chunk);
                        },
                        close: async function() {
                            if (finished) return;
                            finished = true;
                            const blob = new Blob(chunks, {type:'text/plain'});
                            chunks = [];
                            const url = URL.createObjectURL(blob);
                            TxtBlobImport.offerBlob(url, name);
                            setTimeout(() => URL.revokeObjectURL(url), 120000);
                        },
                        abort: async function() { chunks = []; finished = true; }
                    };
                    const stream = new WritableStream(sink);
                    stream.write = async function(value) {
                        const writer = stream.getWriter();
                        try { await writer.write(value); } finally { writer.releaseLock(); }
                    };
                    return stream;
                }
            };
        };
    })();
""".trimIndent()

@android.annotation.SuppressLint("SetJavaScriptEnabled")
@Composable
private fun OnlineBooks(model: ReaderModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    var downloading by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var address by remember { mutableStateOf("https://www.qishuxia.com/") }
    var linkDialog by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf("") }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    var web by remember { mutableStateOf<android.webkit.WebView?>(null) }
    val startDownload: (String, String, String) -> Unit = { url, filename, agent ->
        if (!downloading && !model.busy) {
            downloading = true
            downloadJob = scope.launch {
                try {
                    val current = web ?: error("网页已关闭，请重试。")
                    val result = if (url.startsWith("blob:")) {
                        require(Uri.parse(url.removePrefix("blob:")).host == Uri.parse(current.url ?: "").host) {
                            "文件来自其他页面，请打开原下载页面重试。"
                        }
                        filename to readBrowserBlob(current, url)
                    } else withTimeout(180000) { downloadTxt(url, agent, current.url ?: "") }
                    model.importDownloaded(result.first, result.second)
                } catch (_: TimeoutCancellationException) {
                    model.error = "下载超时，请重新点击普通下载，或在浏览器下载后导入。"
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { model.error = e.message ?: "下载失败，请重试。" }
                finally { downloading = false }
            }
        }
    }
    val latestDownload by rememberUpdatedState(startDownload)
    BackHandler { if (downloading) downloadJob?.cancel() else if (web?.canGoBack() == true) web?.goBack() else model.online = false }
    DisposableEffect(Unit) {
        onDispose {
            downloadJob?.cancel()
            web?.let { it.stopLoading(); it.removeJavascriptInterface("TxtBlobImport"); it.destroy() }
            web = null
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { model.online = false }) { Text("〈 书架") }
            TextButton(onClick = { web?.goBack() }) { Text("网页返回") }
            TextButton(onClick = { linkDialog = true }) { Text("打开链接") }
        }
        Text(Uri.parse(address).host ?: "在线找书", Modifier.padding(horizontal = 12.dp), fontSize = 12.sp, maxLines = 1)
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(modifier = Modifier.weight(1f).heightIn(min = 52.dp), enabled = !downloading,
                onClick = { web?.loadUrl("https://www.qishuxia.com/") }) { Text("奇书网", fontSize = 17.sp, fontWeight = FontWeight.Bold) }
            FilledTonalButton(modifier = Modifier.weight(1f).heightIn(min = 52.dp), enabled = !downloading,
                onClick = { web?.loadUrl("https://www.10086txt.com/") }) { Text("移动小说", fontSize = 17.sp, fontWeight = FontWeight.Bold) }
        }
        Text("在网页中搜索，点击 TXT 普通下载后自动加入书架。", Modifier.padding(12.dp), fontSize = 12.sp)
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { ctx ->
            android.webkit.WebView(ctx).apply {
                web = this
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.setSupportMultipleWindows(false)
                webChromeClient = android.webkit.WebChromeClient()
                addJavascriptInterface(TxtBlobBridge { url, name ->
                    main.post {
                        if (web === this && url.startsWith("blob:") && name.length <= 300) {
                            latestDownload(url, name, settings.userAgentString)
                        }
                    }
                }, "TxtBlobImport")
                webViewClient = object : android.webkit.WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: android.webkit.WebView, request: android.webkit.WebResourceRequest): Boolean {
                        if (request.url.scheme == "https") return false
                        if (request.isForMainFrame) model.error = "此链接无法在内置页面打开，请使用 HTTPS 网页链接。"
                        return true
                    }
                    override fun onPageStarted(view: android.webkit.WebView, url: String, favicon: android.graphics.Bitmap?) {
                        address = url; loading = true
                    }
                    override fun onPageFinished(view: android.webkit.WebView, url: String) {
                        loading = false
                        view.evaluateJavascript(fileSaveAdapter, null)
                    }
                    override fun onReceivedError(view: android.webkit.WebView, request: android.webkit.WebResourceRequest, error: android.webkit.WebResourceError) {
                        if (request.isForMainFrame) { loading = false; model.error = "页面加载失败，请检查网络后重试。" }
                    }
                }
                setDownloadListener { url, agent, disposition, mime, _ ->
                    val name = android.webkit.URLUtil.guessFileName(url, disposition, mime)
                    latestDownload(url, name, agent ?: settings.userAgentString)
                }
                loadUrl("https://www.qishuxia.com/")
            }
        })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton(onClick = { web?.reload() }) { Text("刷新") }
            TextButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(address))) }
                    .onFailure { model.error = "没有可用的浏览器。" }
            }) { Text("用浏览器打开") }
        }
    }
    if (downloading) AlertDialog(onDismissRequest = {}, title = { Text("正在下载并导入") },
        text = { Text("文件最大 100 MB，请稍候…") },
        confirmButton = { TextButton(onClick = { downloadJob?.cancel() }) { Text("取消") } })
    if (linkDialog) AlertDialog(onDismissRequest = { linkDialog = false }, title = { Text("打开书籍或网盘链接") },
        text = { OutlinedTextField(link, { link = it }, label = { Text("粘贴 HTTPS 网址") }) },
        confirmButton = { TextButton(onClick = {
            val uri = Uri.parse(link.trim())
            if (uri.scheme == "https" && !uri.host.isNullOrBlank()) { web?.loadUrl(uri.toString()); linkDialog = false }
            else model.error = "请输入有效的 HTTPS 网址。"
        }) { Text("打开") } }, dismissButton = { TextButton(onClick = { linkDialog = false }) { Text("取消") } })
}

internal fun tapRegion(x: Float, width: Float): Int = when {
    width <= 0f -> 0
    x < width / 3f -> -1
    x >= width * 2f / 3f -> 1
    else -> 0
}

// Versioned disk cache, independent of Compose: reopening the app can reuse pagination.
class PageCacheStore(private val directory: File) {
    private fun cacheFile(key: String): File {
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val book = key.substringBefore('|').filter { it.isLetterOrDigit() || it == '-' }.take(64)
        return File(directory, "$book-$hash.pages")
    }
    fun read(key: String): List<ReadingPage>? {
        val file = cacheFile(key)
        if (!file.isFile || file.length() > 100L * 1024 * 1024) return null
        return try {
            java.io.DataInputStream(file.inputStream().buffered()).use { input ->
                check(input.readInt() == 3 && input.readUTF() == key)
                val count = input.readInt()
                require(count in 1..500000)
                val pages = ArrayList<ReadingPage>()
                var totalLines = 0
                repeat(count) {
                    val size = input.readInt()
                    require(size in 1..500)
                    totalLines += size
                    require(totalLines <= 2000000)
                    val lines = ArrayList<ReadingLine>()
                    repeat(size) {
                        val text = input.readUTF()
                        val paragraph = input.readInt()
                        val character = input.readInt()
                        require(paragraph >= 0 && character >= 0)
                        lines.add(ReadingLine(text, paragraph, character, input.readBoolean()))
                    }
                    require(lines.first().text.isNotEmpty())
                    pages.add(ReadingPage(lines))
                }
                check(input.read() == -1)
                file.setLastModified(System.currentTimeMillis())
                pages
            }
        } catch (_: Exception) { file.delete(); null }
    }
    fun write(key: String, pages: List<ReadingPage>) {
        if (pages.isEmpty()) return
        val target = cacheFile(key)
        val temporary = File(directory, "${target.name}.${UUID.randomUUID()}.tmp")
        try {
            directory.mkdirs()
            java.io.DataOutputStream(temporary.outputStream().buffered()).use { output ->
                output.writeInt(3); output.writeUTF(key); output.writeInt(pages.size)
                pages.forEach { page ->
                    output.writeInt(page.lines.size)
                    page.lines.forEach { line ->
                        output.writeUTF(line.text); output.writeInt(line.paragraph)
                        output.writeInt(line.character); output.writeBoolean(line.heading)
                    }
                }
            }
            if (!temporary.renameTo(target)) return
            // Keep recently used layouts; a failed cache write must never prevent reading.
            var bytes = 0L
            directory.listFiles().orEmpty().filter { it.extension == "pages" }
                .sortedByDescending { it.lastModified() }.forEach { file ->
                    bytes += file.length()
                    if (bytes > 100L * 1024 * 1024 && file != target) file.delete()
                }
        } catch (_: Exception) {
            // Storage may be full: the in-memory result is still usable.
        } finally { temporary.delete() }
    }
    fun removeBook(id: String) {
        directory.listFiles().orEmpty().filter { it.name.startsWith("$id-") }.forEach { it.delete() }
    }
}

internal fun transcodeNovelFile(source: File, destination: File) {
    require(source.length() in 1..MAX_BYTES.toLong()) { "文件为空或超过 100 MB。" }
    val first = source.inputStream().use { it.read() to it.read() }
    val encodings = when (first) {
        (255 to 254) -> listOf(Charsets.UTF_16LE)
        (254 to 255) -> listOf(Charsets.UTF_16BE)
        else -> listOf(Charsets.UTF_8, charset("GB18030"))
    }
    for ((attempt, encoding) in encodings.withIndex()) {
        try {
            var hasContent = false
            var beginning = true
            val decoder = encoding.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            java.io.InputStreamReader(source.inputStream(), decoder).buffered().use { input ->
                destination.bufferedWriter(Charsets.UTF_8).use { output ->
                    val chars = CharArray(32768)
                    while (true) {
                        val count = input.read(chars)
                        if (count < 0) break
                        val start = if (beginning && count > 0 && chars[0] == '\uFEFF') 1 else 0
                        beginning = false
                        for (i in start until count) {
                            require(chars[i] != '\u0000') { "文件不是有效的 TXT 文本。" }
                            if (!chars[i].isWhitespace()) hasContent = true
                        }
                        output.write(chars, start, count - start)
                    }
                }
            }
            require(hasContent) { "小说内容不能为空。" }
            return
        } catch (e: java.nio.charset.CharacterCodingException) {
            destination.delete()
            if (attempt == encodings.lastIndex) throw e
        } catch (e: Exception) {
            destination.delete(); throw e
        }
    }
}

