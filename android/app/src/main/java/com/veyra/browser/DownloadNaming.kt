package com.veyra.browser

import java.net.URLDecoder
import java.util.Locale

/** Pure helpers for download file names and display formatting (unit tested). */
internal object DownloadNaming {
    private val dispositionName = Regex("""filename\*?=(?:UTF-8'')?"?([^";]+)"?""", RegexOption.IGNORE_CASE)
    private val unsafe = Regex("""[\\/:*?"<>|\u0000-\u001F]""")

    /** Picks a safe file name from Content-Disposition, else the URL path, else "download". */
    fun fileName(contentDisposition: String?, url: String): String {
        val fromHeader = contentDisposition?.let { dispositionName.find(it)?.groupValues?.get(1)?.trim() }
        val fromUrl = runCatching {
            java.net.URI(url).path.orEmpty().substringAfterLast('/')
        }.getOrDefault("")
        val raw = (fromHeader?.takeIf { it.isNotBlank() } ?: fromUrl.takeIf { it.isNotBlank() } ?: "download")
        val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        val cleaned = decoded.replace(unsafe, "_").trim().trim('.').take(120)
        return cleaned.ifBlank { "download" }
    }

    /** "file.txt" -> "file (1).txt" when the name is already taken. */
    fun uniqueName(name: String, taken: Set<String>): String {
        if (name !in taken) return name
        val dot = name.lastIndexOf('.')
        val (stem, ext) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
        var index = 1
        while ("$stem ($index)$ext" in taken) index++
        return "$stem ($index)$ext"
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 0) return "unknown size"
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.size - 1) { value /= 1024; unit++ }
        return if (unit == 0) "${bytes} B" else String.format(Locale.US, "%.1f %s", value, units[unit])
    }

    fun formatSpeed(bytesPerSecond: Long): String =
        if (bytesPerSecond <= 0) "" else formatBytes(bytesPerSecond) + "/s"

    /** Only plain web downloads may be fetched by Veyra's own connection. */
    fun isHttpUrl(url: String): Boolean =
        url.startsWith("https://", true) || url.startsWith("http://", true)

    private val mediaExtensions = setOf("mp4", "webm", "mkv", "mp3", "m4a", "ogg", "wav", "flac", "pdf", "zip", "apk")

    /** True when a URL path ends with a known direct-file extension. */
    fun looksLikeDirectFile(url: String): Boolean {
        val path = runCatching { java.net.URI(url).path.orEmpty() }.getOrDefault("")
        return path.substringAfterLast('.', "").lowercase(Locale.US) in mediaExtensions
    }
}
