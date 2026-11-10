package com.veyra.browser

import org.json.JSONObject
import java.util.Locale

/** One playable / downloadable media file found on a web page. */
data class DetectedMedia(val url: String, val kind: Kind, val title: String) {
    enum class Kind { VIDEO, AUDIO, STREAM }

    /** Streams (HLS playlists) can be played but not saved as a single file. */
    val downloadable: Boolean get() = kind != Kind.STREAM

    val fileName: String get() = DownloadNaming.fileName(null, url)
}

/**
 * Parses the reports sent by the built-in page scanner (assets/veyra_media). Only plain http(s)
 * URLs are kept. blob: / MediaSource streams, DASH manifests and DRM content cannot be saved or
 * played outside the page, so they are only counted and shown to the user as unsupported.
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

    /** MIME metadata can identify extensionless HLS/DASH manifests and direct audio/video. */
    fun kindFromMime(rawMime: String): DetectedMedia.Kind? {
        val mime = rawMime.substringBefore(';').trim().lowercase(Locale.ROOT)
        return when (mime) {
            "application/vnd.apple.mpegurl",
            "application/x-mpegurl",
            "audio/mpegurl",
            "application/dash+xml" -> DetectedMedia.Kind.STREAM
            "audio/mpeg", "audio/mp4", "audio/aac", "audio/ogg", "audio/opus",
            "audio/wav", "audio/x-wav", "audio/flac", "audio/webm" -> DetectedMedia.Kind.AUDIO
            "video/mp4", "video/webm", "video/quicktime", "video/3gpp",
            "video/x-matroska", "video/ogg" -> DetectedMedia.Kind.VIDEO
            else -> null
        }
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
                // URL paths may be signed or extensionless. Prefer explicit manifest/file
                // extension, then source MIME, and only then the DOM element's broad tag.
                val kind = kindFromExtension(url) ?: kindFromMime(o.optString("mime")) ?: when (tag) {
                    "video" -> DetectedMedia.Kind.VIDEO
                    "audio" -> DetectedMedia.Kind.AUDIO
                    else -> null
                } ?: continue
                if (found.size >= MAX_ITEMS && url !in found) continue
                found.putIfAbsent(url, DetectedMedia(url, kind, o.optString("title").trim().take(120)))
            }
        }
        return Result(found.values.toList(), unsupported)
    }
}
