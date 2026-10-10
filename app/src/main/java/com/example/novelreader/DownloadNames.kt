package com.example.novelreader

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

internal fun downloadedFileName(url: String, disposition: String?): String {
    fun decode(bytes: ByteArray, charset: String): String? = runCatching {
        Charset.forName(charset).newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }.getOrNull()
    fun percent(value: String, charset: String? = null): String {
        val output = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < value.length) {
            if (value[i] == '%' && i + 2 < value.length) {
                val byte = value.substring(i + 1, i + 3).toIntOrNull(16)
                if (byte != null) { output.write(byte); i += 3; continue }
            }
            val cp = value.codePointAt(i)
            output.write(String(Character.toChars(cp)).toByteArray(Charsets.UTF_8))
            i += Character.charCount(cp)
        }
        val bytes = output.toByteArray()
        return charset?.let { decode(bytes, it) } ?: decode(bytes, "UTF-8") ?: decode(bytes, "GB18030") ?: value
    }
    val header = disposition.orEmpty()
    val extended = Regex("(?:^|;)\\s*filename\\*\\s*=\\s*([^;]+)", RegexOption.IGNORE_CASE).find(header)?.groupValues?.get(1)?.trim()?.trim('"')
    val parts = extended?.split('\'', limit = 3)
    val regular = Regex("(?:^|;)\\s*filename\\s*=\\s*(?:\"([^\"]*)\"|([^;]*))", RegexOption.IGNORE_CASE)
        .find(header)?.let { it.groupValues[1].ifEmpty { it.groupValues[2].trim() } }
    val path = runCatching { java.net.URI(url).rawPath.substringAfterLast('/') }.getOrDefault("")
    val name = when {
        parts?.size == 3 -> percent(parts[2], parts[0])
        regular != null -> {
            // HTTP header bytes are often exposed as Latin-1 by the connection API.
            val repaired = if (regular.any { it.code in 128..255 } && regular.all { it.code <= 255 }) {
                val bytes = regular.toByteArray(Charsets.ISO_8859_1)
                decode(bytes, "UTF-8") ?: decode(bytes, "GB18030") ?: regular
            } else regular
            if ('%' in repaired) percent(repaired) else repaired
        }
        else -> percent(path)
    }
    return name.substringAfterLast('/').substringAfterLast('\\').filterNot { it.isISOControl() }
        .trim().take(240).ifBlank { "下载的小说.txt" }
}
