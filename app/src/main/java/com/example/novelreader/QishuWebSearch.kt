package com.example.novelreader

import android.content.Context
import android.webkit.*
import kotlinx.coroutines.*
import org.json.JSONObject

// Submit the site's real form so WebView handles document charset, cookies and redirects.
internal fun qishuSubmitScript(query: String): String = """
    (() => {
        const input = document.querySelector('input[name="searchkey"]');
        const form = input && input.form;
        if (!form) return 'missing';
        const action = new URL(form.action, location.href);
        if (action.protocol !== 'https:' || action.hostname !== 'www.qishuxia.com' ||
            action.pathname !== '/modules/article/search.php') return 'unsupported';
        input.value = ${JSONObject.quote(query)};
        HTMLFormElement.prototype.submit.call(form);
        return 'submitted';
    })();
""".trimIndent()

@android.annotation.SuppressLint("SetJavaScriptEnabled")
internal suspend fun qishuBrowserSearch(context: Context, query: String): List<AaaSearchHit> = withContext(Dispatchers.Main) {
    val result = CompletableDeferred<Pair<String, String>>()
    val view = WebView(context)
    var submitted = false
    fun fail(message: String) { result.completeExceptionally(IllegalStateException(message)) }
    try {
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.settings.allowFileAccess = false
        view.settings.allowContentAccess = false
        view.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        view.settings.setSupportMultipleWindows(false)
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(web: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                val allowed = request.url.scheme == "https" && request.url.host == "www.qishuxia.com"
                if (!allowed) fail("搜索跳转到未支持的地址，请在在线找书中查看。")
                return !allowed
            }
            override fun onReceivedError(web: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) fail("搜索页面连接失败（${error.errorCode}），请检查网络。")
            }
            override fun onReceivedHttpError(web: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame) fail("网站返回 HTTP ${response.statusCode}，请先在在线找书中打开奇书网完成验证后重试。")
            }
            override fun onPageFinished(web: WebView, url: String) {
                if (result.isCompleted) return
                val uri = android.net.Uri.parse(url)
                if (uri.scheme != "https" || uri.host != "www.qishuxia.com") return
                if (!submitted) {
                    submitted = true
                    web.evaluateJavascript(qishuSubmitScript(query)) { raw ->
                        if (raw != "\"submitted\"" && !result.isCompleted) fail("未找到搜索表单，请先在在线找书中打开奇书网完成验证后重试。")
                    }
                } else if (uri.path == "/modules/article/search.php" || uri.path.orEmpty().startsWith("/book/")) {
                    web.evaluateJavascript("document.documentElement.outerHTML.slice(0, 2000000)") { raw ->
                        if (!result.isCompleted) {
                            try {
                                val html = org.json.JSONTokener(raw).nextValue() as? String ?: error("无法读取搜索结果。")
                                result.complete(html to url)
                            } catch (e: Exception) { result.completeExceptionally(e) }
                        }
                    }
                }
            }
        }
        view.loadUrl("https://www.qishuxia.com/")
        val (html, url) = try { withTimeout(40000) { result.await() } }
            catch (e: TimeoutCancellationException) { error("搜索等待超时，请在在线找书中确认网站可以访问。") }
        withContext(Dispatchers.Default) {
            val direct = Regex("https://www\\.qishuxia\\.com/book/([0-9]+)/?").matchEntire(url)
            if (direct == null) parseQishuSearch(html) else {
                val heading = Regex("<h1\\b[^>]*>(.*?)</h1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                    .find(html)?.groupValues?.get(1) ?: error("未识别书籍详情，请在在线找书中查看。")
                val title = android.text.Html.fromHtml(heading, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()
                require(title.isNotEmpty()) { "书籍标题为空。" }
                listOf(AaaSearchHit(direct.groupValues[1], "qishu", title, ""))
            }
        }
    } finally {
        result.cancel()
        view.stopLoading()
        view.webViewClient = WebViewClient()
        view.destroy()
    }
}
