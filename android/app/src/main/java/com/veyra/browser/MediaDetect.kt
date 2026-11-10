package com.veyra.browser

import org.json.JSONObject
import java.util.Locale

/** One media candidate found on a web page. Quality metadata is advisory and is not a guarantee from the server. */
data class DetectedMedia(
    val url: String,
    val kind: Kind,
    val title: String,
    val mimeType: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val quality: String? = null
) {
    enum class Kind { VIDEO, AUDIO, STREAM }

    /** Streams (HLS playlists) can be played but not saved as a single file. */
    val downloadable: Boolean get() = kind != Kind.STREAM

    val fileName: String get() = DownloadNaming.fileName(null, url)

    /** Use explicit page/URL metadata where available. Never invent a quality for an unknown source. */
    val qualityLabel: String
        get() {
            val token = QUALITY_PATTERN.find(listOfNotNull(quality, fileName, url).joinToString(" "))?.value
            if (!token.isNullOrBlank()) return if (token.equals("4k", true)) "4K · 2160p" else token.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() }
            if (height > 0) return if (height >= 2160) "4K · ${height}p" else "${height}p"
            return if (kind == Kind.AUDIO) "Audio source" else "Original / quality unknown"
        }

    companion object {
        private val QUALITY_PATTERN = Regex("""(?i)\b(2160|1440|1080|720|540|480|360|240)p?\b|\b4k\b""")
    }
}

/**
 * Parses reports from the built-in page scanner. Only plain HTTP(S) URLs are kept.
 * Direct media files are candidates for native playback/download; HLS/DASH manifests are
 * play-only candidates. blob:/MediaSource URLs cannot be handed to ExoPlayer as ordinary URLs
 * and remain counted as unsupported. DRM and source access rules are never bypassed.
 */
object MediaDetect {
    data class Result(val items: List<DetectedMedia>, val unsupported: Int)

    private val videoExt = setOf("mp4", "m4v", "webm", "mkv", "mov", "3gp", "ogv")
    private val audioExt = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac")
    private const val MAX_ITEMS = 60

    fun extensionOf(url: String): String {
        val path = url.substringBefore('#').substringBefore('?')
        val name = path.substringAfterLast('/')
        return if ('.' in name) name.substringAfterLast('.').lowercase(Locale.ROOT) else ""
    }

    /** Kind implied by the URL's file extension alone, or null when it says nothing. */
    fun kindFromExtension(url: String): DetectedMedia.Kind? = when (extensionOf(url)) {
        in videoExt -> DetectedMedia.Kind.VIDEO
        in audioExt -> DetectedMedia.Kind.AUDIO
        "m3u8", "mpd" -> DetectedMedia.Kind.STREAM
        else -> null
    }

    /** HTTP/source MIME identifies extensionless media where a URL alone does not. */
    fun kindFromMime(rawMime: String): DetectedMedia.Kind? = when (rawMime.substringBefore(';').trim().lowercase(Locale.ROOT)) {
        "application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl",
        "application/dash+xml" -> DetectedMedia.Kind.STREAM
        "audio/mpeg", "audio/mp4", "audio/aac", "audio/ogg", "audio/opus",
        "audio/wav", "audio/x-wav", "audio/flac", "audio/webm" -> DetectedMedia.Kind.AUDIO
        "video/mp4", "video/webm", "video/quicktime", "video/3gpp",
        "video/x-matroska", "video/ogg" -> DetectedMedia.Kind.VIDEO
        else -> null
    }

    fun parse(json: String): Result {
        val root = try { JSONObject(json) } catch (_: Exception) { return Result(emptyList(), 0) }
        var unsupported = root.optInt("blobs", 0).coerceAtLeast(0)
        val found = LinkedHashMap<String, DetectedMedia>()
        val array = root.optJSONArray("items")
        if (array != null) {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val url = o.optString("url").trim()
                if (!DownloadNaming.isHttpUrl(url)) {
                    if (url.startsWith("blob:") || url.startsWith("mediastream:")) unsupported++
                    continue
                }
                val tag = o.optString("tag")
                // An explicit extension wins, then MIME metadata, then the DOM element's tag.
                val rawMime = o.optString("mime").trim().ifBlank { null }
                val kind = kindFromExtension(url) ?: rawMime?.let(::kindFromMime) ?: when (tag) {
                    "video" -> DetectedMedia.Kind.VIDEO
                    "audio" -> DetectedMedia.Kind.AUDIO
                    else -> null
                } ?: continue
                if (found.size >= MAX_ITEMS && url !in found) continue
                val item = DetectedMedia(
                    url = url,
                    kind = kind,
                    title = o.optString("title").trim().take(120),
                    mimeType = rawMime,
                    width = o.optInt("width", 0).coerceAtLeast(0),
                    height = o.optInt("height", 0).coerceAtLeast(0),
                    quality = o.optString("quality").trim().ifBlank { null }
                )
                val existing = found[url]
                // Prefer richer DOM metadata over a URL-only performance entry.
                if (existing == null || (existing.height == 0 && item.height > 0) || (existing.quality == null && item.quality != null)) {
                    found[url] = item
                }
            }
        }
        return Result(found.values.toList(), unsupported)
    }
}
