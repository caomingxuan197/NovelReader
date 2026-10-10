package com.example.novelreader

import kotlinx.coroutines.*

internal suspend fun mobileSearch(query: String, page: Int): AaaSearchPage = withContext(Dispatchers.IO) {
    val url = "https://www.10086txt.com/search.php?q=${java.net.URLEncoder.encode(query, "UTF-8")}&page=$page"
    val c = java.net.URL(url).openConnection() as javax.net.ssl.HttpsURLConnection
    try {
        c.connectTimeout = 12000; c.readTimeout = 15000; c.instanceFollowRedirects = false
        require(c.responseCode == 200) { "移动小说搜索暂不可用。" }
        val bytes = c.inputStream.use { input ->
            val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { ensureActive(); val n = input.read(buffer); if (n < 0) break
                require(out.size() + n <= 2_000_000); out.write(buffer, 0, n) }
            out.toByteArray()
        }
        parseMobileSearch(bytes.toString(Charsets.UTF_8))
    } finally { c.disconnect() }
}

internal fun parseMobileSearch(html: String): AaaSearchPage {
    val links = Regex("<h2>\\s*<a\\b.*?href=\"https://www\\.10086txt\\.com/\\?id=([0-9]+)\"[^>]*>(.*?)</a>\\s*</h2>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    val books = links.findAll(html).mapNotNull {
        val label = android.text.Html.fromHtml(it.groupValues[2], android.text.Html.FROM_HTML_MODE_LEGACY).toString()
        val title = Regex("《(.*?)》").find(label)?.groupValues?.get(1) ?: return@mapNotNull null
        AaaSearchHit(it.groupValues[1], "mobile", title, label.substringAfter("作者：", "").trim())
    }.distinctBy { it.book }.toList()
    val pages = Regex("(?:&amp;|&)page=([0-9]+)").findAll(html).map { it.groupValues[1].toInt() }.maxOrNull() ?: 1
    require(books.isNotEmpty() || html.contains("搜索") && (html.contains("没有") || html.contains("找到 0"))) { "移动小说搜索格式未识别。" }
    return AaaSearchPage(books, pages.coerceIn(1, 1000))
}

internal object QishuCooldown {
    private var active = false
    @Synchronized fun acquire(context: android.content.Context): Boolean {
        val prefs = context.getSharedPreferences("search-throttle", 0)
        val elapsed = System.currentTimeMillis() - prefs.getLong("qishu-last", 0)
        if (active || elapsed < 31000) return false
        active = true
        prefs.edit().putLong("qishu-last", System.currentTimeMillis()).apply()
        return true
    }
    @Synchronized fun finish(context: android.content.Context) {
        context.getSharedPreferences("search-throttle", 0).edit().putLong("qishu-last", System.currentTimeMillis()).apply()
        active = false
    }
}
