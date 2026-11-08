package com.veyra.browser

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.WebResponse
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

enum class DlState { RUNNING, PAUSED, DONE, FAILED }

class DownloadItem(
    val id: Long,
    val url: String,
    var name: String,
    var mime: String?,
    val isPrivate: Boolean
) {
    @Volatile var total: Long = -1
    @Volatile var done: Long = 0
    @Volatile var state: DlState = DlState.RUNNING
    @Volatile var speed: Long = 0
    @Volatile var error: String? = null
    @Volatile var savedUri: String? = null
    @Volatile var cancelRequested = false
    @Volatile var resumable = true
}

/**
 * Small download manager. Gecko hands us the response stream for non-renderable content; we
 * stream it into a private ".part" file, then publish it to the public Downloads/Veyra folder
 * (MediaStore on Android 10+, app-specific storage with a FileProvider before that).
 * Pause/resume re-requests the URL with a Range header, so it only works for public URLs whose
 * server supports ranges. Transfers run in the app process and stop if Android kills it.
 */
class DownloadCenter(private val context: Context, private val onChange: () -> Unit) {
    val items = ArrayList<DownloadItem>()
    private val ids = AtomicLong(System.currentTimeMillis())
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val partDir = File(context.cacheDir, "downloads").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("veyra_downloads", Context.MODE_PRIVATE)
    private var lastNotify = 0L

    init {
        load()
        createChannel()
    }

    private fun changed() {
        val now = System.currentTimeMillis()
        if (now - lastNotify < 500) return
        lastNotify = now
        main.post { onChange() }
    }

    private fun changedNow() { main.post { onChange() } }

    fun takenNames(): Set<String> = items.map { it.name }.toSet()

    /** Handles a response Gecko could not render (attachment, archive, binary...). */
    fun enqueue(response: WebResponse, isPrivate: Boolean) {
        val headers = response.headers
        val disposition = headers.entries.firstOrNull { it.key.equals("content-disposition", true) }?.value
        val mime = headers.entries.firstOrNull { it.key.equals("content-type", true) }?.value?.substringBefore(';')?.trim()
        val length = headers.entries.firstOrNull { it.key.equals("content-length", true) }?.value?.toLongOrNull() ?: -1L
        val name = DownloadNaming.uniqueName(DownloadNaming.fileName(disposition, response.uri), takenNames())
        val item = DownloadItem(ids.incrementAndGet(), response.uri, name, mime, isPrivate).apply { total = length }
        items.add(0, item)
        val body = response.body
        if (body == null) {
            fail(item, "The server sent no data")
        } else {
            pool.execute { run(item, body, 0L) }
        }
        changedNow()
    }

    /** Direct URL download (used for plain media/file links). Verifies status and content type. */
    fun enqueueUrl(url: String, isPrivate: Boolean) {
        if (!DownloadNaming.isHttpUrl(url)) return
        val item = DownloadItem(ids.incrementAndGet(), url,
            DownloadNaming.uniqueName(DownloadNaming.fileName(null, url), takenNames()), null, isPrivate)
        items.add(0, item)
        pool.execute { runFromUrl(item, 0L) }
        changedNow()
    }

    fun pause(item: DownloadItem) {
        if (item.state == DlState.RUNNING) item.cancelRequested = true
    }

    fun resume(item: DownloadItem) {
        if (item.state == DlState.RUNNING) return
        item.error = null
        item.cancelRequested = false
        item.state = DlState.RUNNING
        val offset = if (item.resumable) partFile(item).let { if (it.exists()) it.length() else 0L } else 0L
        if (offset == 0L) item.done = 0
        pool.execute { runFromUrl(item, offset) }
        changedNow()
    }

    fun delete(item: DownloadItem, deleteFile: Boolean) {
        item.cancelRequested = true
        partFile(item).delete()
        if (deleteFile) item.savedUri?.let { runCatching { deleteSaved(Uri.parse(it)) } }
        items.remove(item)
        persist()
        changedNow()
    }

    fun clearFinished() {
        items.removeAll { it.state == DlState.DONE || it.state == DlState.FAILED }
        persist()
        changedNow()
    }

    fun openIntent(item: DownloadItem): android.content.Intent? {
        val uri = item.savedUri?.let { Uri.parse(it) } ?: return null
        return android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, item.mime ?: "*/*")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    // ---- transfer ----------------------------------------------------------------------

    private fun partFile(item: DownloadItem) = File(partDir, "${item.id}.part")

    private fun run(item: DownloadItem, body: InputStream, offset: Long) {
        try {
            body.use { copy(item, it, offset, append = offset > 0) }
            finish(item)
        } catch (e: Exception) {
            if (item.cancelRequested) pausedOrDeleted(item) else fail(item, e.message ?: "Download failed")
        }
    }

    private fun runFromUrl(item: DownloadItem, offset: Long) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(item.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 20000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Veyra")
                if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
            }
            val status = connection.responseCode
            val type = connection.contentType?.substringBefore(';')?.trim()
            if (status != 200 && status != 206) {
                fail(item, "Server replied with status $status")
                return
            }
            if (type != null && type.startsWith("text/html", true) && offset == 0L) {
                fail(item, "This link is a web page, not a downloadable file")
                return
            }
            var start = offset
            if (offset > 0 && status == 200) {
                // The server ignored the Range header; restart from the beginning.
                item.resumable = false
                partFile(item).delete()
                start = 0
            }
            item.mime = item.mime ?: type
            val len = connection.contentLengthLong
            item.total = if (len >= 0) len + start else item.total
            connection.inputStream.use { copy(item, it, start, append = start > 0) }
            finish(item)
        } catch (e: Exception) {
            if (item.cancelRequested) pausedOrDeleted(item) else fail(item, e.message ?: "Download failed")
        } finally {
            connection?.disconnect()
        }
    }

    private fun copy(item: DownloadItem, input: InputStream, offset: Long, append: Boolean) {
        val file = partFile(item)
        if (!append && file.exists()) file.delete()
        RandomAccessFile(file, "rw").use { out ->
            out.seek(offset)
            item.done = offset
            val buffer = ByteArray(32 * 1024)
            var windowStart = System.currentTimeMillis()
            var windowBytes = 0L
            while (true) {
                if (item.cancelRequested) throw java.io.InterruptedIOException("paused")
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                item.done += read
                windowBytes += read
                val now = System.currentTimeMillis()
                if (now - windowStart >= 1000) {
                    item.speed = windowBytes * 1000 / (now - windowStart)
                    windowStart = now; windowBytes = 0
                }
                changed()
            }
            if (item.total < 0) item.total = item.done
        }
    }

    private fun pausedOrDeleted(item: DownloadItem) {
        if (items.contains(item)) {
            item.state = DlState.PAUSED
            item.speed = 0
            persist()
            changedNow()
        }
    }

    private fun fail(item: DownloadItem, message: String) {
        item.state = DlState.FAILED
        item.error = message
        item.speed = 0
        persist()
        changedNow()
    }

    private fun finish(item: DownloadItem) {
        try {
            item.savedUri = publish(item)?.toString()
            partFile(item).delete()
            item.state = DlState.DONE
            item.speed = 0
            notifyDone(item)
        } catch (e: Exception) {
            fail(item, "Could not save the file: ${e.message}")
            return
        }
        persist()
        changedNow()
    }

    private fun publish(item: DownloadItem): Uri? {
        val source = partFile(item)
        val mime = item.mime ?: "application/octet-stream"
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, item.name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/Veyra")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw java.io.IOException("Downloads folder is unavailable")
            resolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
                ?: throw java.io.IOException("Cannot write to Downloads")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        }
        val dir = File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "Veyra").apply { mkdirs() }
        val target = File(dir, item.name)
        source.copyTo(target, overwrite = true)
        return FileProvider.getUriForFile(context, "${context.packageName}.files", target)
    }

    private fun deleteSaved(uri: Uri) {
        if (uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY) {
            context.contentResolver.delete(uri, null, null)
        } else {
            val path = uri.lastPathSegment ?: return
            File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "Veyra/${path.substringAfterLast('/')}").delete()
        }
    }

    // ---- notifications & persistence ---------------------------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel("downloads", "Downloads", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notifyDone(item: DownloadItem) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        val intent = openIntent(item)
        val pending = intent?.let {
            android.app.PendingIntent.getActivity(
                context, item.id.toInt(), it,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
        val notification = NotificationCompat.Builder(context, "downloads")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(if (item.isPrivate) "Download complete" else item.name)
            .setContentText("Saved to Downloads/Veyra")
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setVisibility(if (item.isPrivate) NotificationCompat.VISIBILITY_SECRET else NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(item.id.toInt(), notification)
    }

    private fun persist() {
        val array = JSONArray()
        items.filter { !it.isPrivate && it.state != DlState.RUNNING }.forEach { item ->
            array.put(JSONObject().apply {
                put("id", item.id); put("url", item.url); put("name", item.name)
                put("mime", item.mime ?: ""); put("total", item.total); put("done", item.done)
                put("state", item.state.name); put("error", item.error ?: "")
                put("uri", item.savedUri ?: ""); put("resumable", item.resumable)
            })
        }
        prefs.edit().putString("items", array.toString()).apply()
    }

    private fun load() {
        try {
            val array = JSONArray(prefs.getString("items", "[]") ?: "[]")
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val item = DownloadItem(o.getLong("id"), o.getString("url"), o.getString("name"),
                    o.optString("mime").ifBlank { null }, false)
                item.total = o.optLong("total", -1)
                item.done = o.optLong("done", 0)
                item.error = o.optString("error").ifBlank { null }
                item.savedUri = o.optString("uri").ifBlank { null }
                item.resumable = o.optBoolean("resumable", true)
                item.state = runCatching { DlState.valueOf(o.getString("state")) }.getOrDefault(DlState.FAILED)
                items.add(item)
            }
        } catch (_: Exception) {
            // Ignore a corrupt download list.
        }
    }
}
