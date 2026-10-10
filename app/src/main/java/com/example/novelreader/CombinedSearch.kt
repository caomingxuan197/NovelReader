package com.example.novelreader

import kotlinx.coroutines.*

internal fun parseQishuSearch(html: String): List<AaaSearchHit> {
    fun plain(value: String) = android.text.Html.fromHtml(value, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()
    val rows = Regex("<li\\b[^>]*>(.*?)</li>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    val title = Regex("<span\\s+class=[\"']s2[\"'][^>]*>\\s*<a\\s+href=[\"'](?:https://www\\.qishuxia\\.com)?/book/([0-9]+)/[\"'][^>]*>(.*?)</a>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    val author = Regex("<span\\s+class=[\"']s4[\"'][^>]*>(.*?)</span>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    val books = rows.findAll(html).mapNotNull { row ->
        val hit = title.find(row.value) ?: return@mapNotNull null
        AaaSearchHit(hit.groupValues[1], "qishu", plain(hit.groupValues[2]), plain(author.find(row.value)?.groupValues?.get(1).orEmpty()))
    }.distinctBy { it.book }.take(300).toList()
    require(books.isNotEmpty() || html.contains("没有") || html.contains("未找到") || html.contains("无相关")) { "部分搜索暂不可用，请稍后重试。" }
    return books
}

internal fun searchMatchScore(hit: AaaSearchHit, query: String): Int {
    fun normalize(value: String) = value.lowercase(java.util.Locale.ROOT).filter { it.isLetterOrDigit() }
    val q = normalize(query); val title = normalize(hit.title); val author = normalize(hit.author)
    if (q.isEmpty()) return 0
    return when {
        title == q -> 100000
        title.startsWith(q) -> 80000 - title.length.coerceAtMost(1000)
        title.contains(q) -> 70000 - title.length.coerceAtMost(1000)
        author == q -> 60000
        author.contains(q) -> 50000
        else -> q.toSet().count { it in title } * 100 - kotlin.math.abs(title.length - q.length)
    }
}

internal class CombinedSearch(private val aaa: AaaClient, private val context: android.content.Context) {
    private var query = ""
    private var qishu = emptyList<AaaSearchHit>()
    private var qishuError: String? = null
    private var aaaPages: Int? = null
    var warning: String? = null
        private set
    suspend fun search(value: String, page: Int): AaaSearchPage = supervisorScope {
        require(value.length in 1..100)
        if (query != value || page == 1) aaaPages = null
        suspend fun <T> attempt(block: suspend () -> T): Result<T> = try { Result.success(block()) }
            catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
        val a = async { attempt {
            if (aaaPages != null && page > aaaPages!!) AaaSearchPage(emptyList(), aaaPages!!)
            else aaa.search(value, page)
        } }
        val b = async { attempt { baoshuSearch(value, page) } }
        val m = async { attempt { mobileSearch(value, page) } }
        val refreshQishu = value != query || page == 1
        val allowed = refreshQishu && QishuCooldown.acquire(context)
        if (refreshQishu) { query = value; qishu = emptyList(); qishuError = null }
        val q = if (allowed) async { try { attempt { qishuBrowserSearch(context, value) } } finally { QishuCooldown.finish(context) } } else null
        val ar = a.await()
        val br = b.await()
        val mr = m.await()
        ar.getOrNull()?.let { aaaPages = it.pages }
        q?.await()?.let { result ->
            query = value; qishu = result.getOrDefault(emptyList()).sortedByDescending { searchMatchScore(it, value) }; qishuError = result.exceptionOrNull()?.message
        }
        warning = null
        if (ar.isFailure && br.isFailure && mr.isFailure && qishuError != null) error("搜索暂不可用，请检查网络后重试。")
        val extra = qishu.chunked(10).getOrNull(page - 1).orEmpty()
        fun identity(hit: AaaSearchHit) = hit.title.filter { it.isLetterOrDigit() }.lowercase() to hit.author.filter { it.isLetterOrDigit() }.lowercase()
        val aaaBooks = ar.getOrNull()?.books.orEmpty()
        val aaaIds = aaaBooks.map { identity(it) }.toSet()
        val others = (extra + br.getOrNull()?.books.orEmpty() + mr.getOrNull()?.books.orEmpty()).filter { identity(it) !in aaaIds }
        val books = (aaaBooks + others).sortedWith(compareByDescending<AaaSearchHit> { searchMatchScore(it, value) }.thenBy { if (it.site in listOf("qishu", "mobile")) 1 else 0 })
        AaaSearchPage(books, maxOf(ar.getOrNull()?.pages ?: 0, (qishu.size + 9) / 10, mr.getOrNull()?.pages ?: 0))
    }
}


