package com.example.novelreader

import kotlinx.coroutines.*

private fun baoshuPlain(s: String) = android.text.Html.fromHtml(s, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()

internal suspend fun baoshuHtml(path: String): String = withContext(Dispatchers.IO) {
    require(path.startsWith("/book/") || path.startsWith("/search.html?") || path.startsWith("/novelsearch/novel/getdlist/?"))
    val c = java.net.URL("https://www.baoshubook.com$path").openConnection() as javax.net.ssl.HttpsURLConnection
    try {
        c.connectTimeout = 15000; c.readTimeout = 20000; c.instanceFollowRedirects = false
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/130.0 Safari/537.36")
        c.setRequestProperty("Referer", "https://www.baoshubook.com/")
        require(c.responseCode == 200) { "宝书网连接失败（${c.responseCode}）" }
        val bytes = c.inputStream.use { input ->
            val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192)
            while (true) { ensureActive(); val n = input.read(buf); if (n < 0) break
                require(out.size() + n <= 8_000_000); out.write(buf, 0, n) }
            out.toByteArray()
        }
        bytes.toString(Charsets.UTF_8)
    } finally { c.disconnect() }
}

internal suspend fun baoshuSearch(query: String, page: Int): AaaSearchPage {
    if (page != 1) return AaaSearchPage(emptyList(), 1)
    val keyword = query.trim().removeSurrounding("《", "》").trim()
    val html = baoshuHtml("/search.html?searchkey=${java.net.URLEncoder.encode(keyword, "UTF-8")}&searchtype=all")
    val pattern = Regex("<h3>.*?<a\\s+href=\"/book/([0-9]+)/\"[^>]*>(.*?)</a>.*?</h3>", RegexOption.DOT_MATCHES_ALL)
    return AaaSearchPage(pattern.findAll(html).map { AaaSearchHit(it.groupValues[1], "baoshu", baoshuPlain(it.groupValues[2]), "") }.distinctBy { it.book }.toList(), 1)
}

internal suspend fun baoshuBook(id: String): AaaBook {
    require(id.matches(Regex("[0-9]+")))
    val html = baoshuHtml("/book/$id/")
    val hidden = Regex("loadzj\\($id,([0-9]+),").find(html)?.groupValues?.get(1)?.toInt()
    val middle = if (hidden != null) baoshuHtml("/novelsearch/novel/getdlist/?id=$id&num=$hidden&order=asc") else ""
    val list = qishuElement(html, "ul", "toplist") + middle +
        (if (html.contains("id=\"lastchapter\"")) qishuElement(html, "ul", "lastchapter") else "")
    val pattern = Regex("href=\"/book/([A-Za-z0-9_-]+)/read_([0-9]+)\\.html\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
    val chapters = pattern.findAll(list).map { AaaChapter(it.groupValues[1] + "~" + it.groupValues[2], baoshuPlain(it.groupValues[3])) }.distinctBy { it.id }.toList()
    require(chapters.isNotEmpty()) { "未识别宝书网目录。" }
    if (hidden != null) require(pattern.findAll(middle).count() >= hidden) { "隐藏章节未加载完整，请重试。" }
    return AaaBook(AaaAddress(id, "baoshu"), chapters)
}

internal suspend fun baoshuBody(id: String): String {
    val match = Regex("([A-Za-z0-9_-]+)~([0-9]+)").matchEntire(id) ?: error("章节编号异常")
    val html = baoshuHtml("/book/${match.groupValues[1]}/read_${match.groupValues[2]}.html")
    require(!Regex(">\\s*下一页\\s*</a>").containsMatchIn(html)) { "本章有分页，已停止以免漏内容。" }
    val body = cleanQishuParagraphTags(baoshuPlain(qishuElement(html, "div", "chaptercontent")))
    require(body.isNotBlank()) { "正文为空。" }
    return body
}
