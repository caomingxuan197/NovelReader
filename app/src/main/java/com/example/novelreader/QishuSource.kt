package com.example.novelreader

import kotlinx.coroutines.*
import java.net.URL
import java.net.URLEncoder

private fun qishuPlain(html: String): String = android.text.Html.fromHtml(
    html.replace(Regex("<(script|style)\\b[^>]*>.*?</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), ""),
    android.text.Html.FROM_HTML_MODE_LEGACY).toString().replace('\u00a0', ' ').trim()

internal fun qishuElement(html: String, tag: String, id: String): String {
    val opening = Regex("<$tag\\b[^>]*\\bid\\s*=\\s*[\"']${Regex.escape(id)}[\"'][^>]*>", RegexOption.IGNORE_CASE)
        .find(html) ?: error("未找到书籍内容，网站可能需要验证或页面结构已变化。")
    val tags = Regex("</?$tag\\b[^>]*>", RegexOption.IGNORE_CASE)
    var depth = 1
    for (match in tags.findAll(html, opening.range.last + 1)) {
        if (match.value.startsWith("</")) depth-- else if (!match.value.endsWith("/>")) depth++
        if (depth == 0) return html.substring(opening.range.last + 1, match.range.first)
    }
    error("书籍页面不完整，请重试。")
}

internal fun parseQishuDirectory(html: String, bookId: String): AaaBook {
    require(bookId.matches(Regex("[0-9]{1,15}")))
    val list = qishuElement(html, "ul", "section-list")
    val links = Regex("<a\\b[^>]*\\bhref\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))[^>]*>(.*?)</a>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    val chapters = links.findAll(list).mapNotNull { link ->
        val href = (1..3).map { link.groupValues[it] }.firstOrNull { it.isNotBlank() } ?: return@mapNotNull null
        val id = qishuChapterId(qishuPlain(href), bookId) ?: return@mapNotNull null
        val title = qishuPlain(link.groupValues[4])
        if (title.isBlank()) null else AaaChapter(id, title)
    }.distinctBy { it.id }.toList()
    require(chapters.size in 1..20000) { "目录中找到 ${links.findAll(list).count()} 个链接，但未能识别有效章节，请提供这本书的链接。" }
    require(!Regex(">\\s*下一页\\s*</a>").containsMatchIn(html)) { "该目录有分页，暂不支持，已停止以免漏章。" }
    return AaaBook(AaaAddress(bookId, "qishu"), chapters)
}

internal fun qishuChapterId(href: String, bookId: String): String? = runCatching {
    val uri = java.net.URI("https://www.qishuxia.com/book/$bookId/").resolve(href.trim()).normalize()
    if (uri.scheme !in listOf("https", "http") || uri.host != "www.qishuxia.com" || uri.userInfo != null || uri.port != -1) return@runCatching null
    Regex("/book/${Regex.escape(bookId)}/([0-9]+)\\.html").matchEntire(uri.path)?.groupValues?.get(1)
}.getOrNull()

internal fun cleanQishuParagraphTags(text: String): String {
    val encoded = Regex("&lt;\\s*(/?)\\s*(p|br)\\s*/?\\s*&gt;", RegexOption.IGNORE_CASE)
    val decoded = encoded.replace(text) { "<${it.groupValues[1]}${it.groupValues[2]}>" }
    return decoded.replace(Regex("</?p(?:\\s+[^<>]*)?\\s*>|<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
}

internal fun parseQishuBody(html: String): String {
    require(!Regex(">\\s*下一页\\s*</a>").containsMatchIn(html)) { "该章节有分页，暂不支持，已停止以免正文不完整。" }
    val body = cleanQishuParagraphTags(qishuPlain(qishuElement(html, "div", "content")))
        .lines().map { it.trim() }.filter { it.isNotBlank() }.joinToString("\n\n")
    require(body.isNotBlank() && !body.contains('\u0000')) { "正文为空，请在网站确认该章节可以访问。" }
    return body
}

internal suspend fun qishuHtml(path: String, query: String? = null): String = withContext(Dispatchers.IO) {
    require(Regex("/book/[0-9]+/(?:[0-9]+\\.html)?").matches(path) || path == "/modules/article/search.php")
    var url = URL("https://www.qishuxia.com$path")
    var body = query?.let { ("searchkey=" + URLEncoder.encode(it, "GBK")).toByteArray(Charsets.US_ASCII) }
    repeat(6) {
        ensureActive()
        require(url.protocol == "https" && url.host == "www.qishuxia.com" && url.userInfo == null && url.port == -1) { "网站跳转地址不受支持。" }
        val c = url.openConnection() as javax.net.ssl.HttpsURLConnection
        try {
            c.connectTimeout = 15000; c.readTimeout = 20000; c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36")
            c.setRequestProperty("Referer", "https://www.qishuxia.com/")
            android.webkit.CookieManager.getInstance().getCookie(url.toString())?.let { c.setRequestProperty("Cookie", it) }
            body?.let { bytes ->
                c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                c.setFixedLengthStreamingMode(bytes.size)
                c.outputStream.use { it.write(bytes) }
            }
            val code = c.responseCode
            c.headerFields.filterKeys { it.equals("Set-Cookie", true) }.values.flatten().forEach {
                android.webkit.CookieManager.getInstance().setCookie(url.toString(), it)
            }
            if (code in listOf(301,302,303,307,308)) {
                url = URL(url, c.getHeaderField("Location") ?: error("网站跳转地址为空。"))
                if (code in listOf(301,302,303)) body = null
                return@repeat
            }
            require(code == 200) { "网站返回 HTTP $code，请检查网络或在在线找书中完成网站验证。" }
            val bytes = c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(16384)
                while (true) {
                    ensureActive(); val n = input.read(buffer); if (n < 0) break
                    require(out.size() + n <= 8 * 1024 * 1024) { "页面数据过大。" }
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
            val header = c.contentType.orEmpty() + bytes.take(1000).toByteArray().toString(Charsets.ISO_8859_1)
            val charset = if (header.contains("utf-8", true)) Charsets.UTF_8 else java.nio.charset.Charset.forName("GBK")
            return@withContext bytes.toString(charset)
        } finally { c.disconnect() }
    }
    error("网站跳转次数过多。")
}

internal suspend fun loadSearchBook(client: AaaClient, hit: AaaSearchHit): AaaBook =
    if (hit.site == "baoshu") baoshuBook(hit.book) else if (hit.site == "qishu") {
        val html = qishuHtml("/book/${hit.book}/")
        withContext(Dispatchers.Default) { parseQishuDirectory(html, hit.book) }
    } else client.load("https://www.aaawz.cc/#/book/${hit.book}/${hit.site}")

