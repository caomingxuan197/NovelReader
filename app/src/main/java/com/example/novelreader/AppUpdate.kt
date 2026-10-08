package com.example.novelreader

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** A failed update check must never prevent offline reading. */
fun ComponentActivity.checkAppUpdate() {
    lifecycleScope.launch {
        val update = try {
            withContext(Dispatchers.IO) {
                val connection = URL("https://yuedu.caomeixiong.fun/update.json?t=${System.currentTimeMillis()}")
                    .openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 8000
                    connection.readTimeout = 8000
                    connection.useCaches = false
                    connection.instanceFollowRedirects = false
                    connection.setRequestProperty("Cache-Control", "no-cache")
                    require(connection.responseCode == 200)
                    val bytes = connection.inputStream.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= 65536)
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    val json = JSONObject(String(bytes, Charsets.UTF_8))
                    require(json.getString("packageName") == packageName)
                    val info = packageManager.getPackageInfo(packageName, 0)
                    @Suppress("DEPRECATION")
                    val installed = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
                    val version = json.getLong("versionCode")
                    val uri = Uri.parse(json.getString("apkUrl"))
                    require(uri.scheme == "https" && uri.userInfo == null)
                    require(uri.host == "github.com" &&
                        uri.path.orEmpty().startsWith("/caomingxuan197/NovelReader/releases/download/") &&
                        uri.path.orEmpty().endsWith(".apk"))
                    if (version > installed && json.optInt("minSdk", 24) <= Build.VERSION.SDK_INT) {
                        Triple(json.getString("versionName").take(40), json.optString("notes").take(2000), uri)
                    } else null
                } finally { connection.disconnect() }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { null }
        if (update != null && !isFinishing && !isDestroyed) {
            val dialog = AlertDialog.Builder(this@checkAppUpdate)
                .setTitle("发现新版本 ${update.first}")
                .setMessage(update.second + "\n\n将使用浏览器下载安装包，下载完成后打开并确认安装。")
                .setNegativeButton("暂不更新", null)
                .setPositiveButton("下载更新") { _, _ ->
                    try { startActivity(Intent(Intent.ACTION_VIEW, update.third)) }
                    catch (_: Exception) { Toast.makeText(this@checkAppUpdate, "无法打开浏览器，请访问悦读下载网站", Toast.LENGTH_LONG).show() }
                }.create()
            lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
                override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
                    dialog.dismiss()
                    owner.lifecycle.removeObserver(this)
                }
            })
            dialog.show()
        }
    }
}
