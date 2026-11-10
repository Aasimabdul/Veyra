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
    /** Saved in the app-private, lock-protected folder instead of the public Downloads folder. */
    @Volatile var privateFolder = false
    val inPrivateFolder: Boolean get() = isPrivate || privateFolder
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
    private val pool = Executors.newFixedThreadPool(3)
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
        items.filter { it.state == DlState.RUNNING }.forEach { notifyProgress(it) }
        main.post { onChange() }
    }

    private fun changedNow() { main.post { onChange() } }

    fun takenNames(): Set<String> =
        (items.map { it.name } +
            (privateDir(context).listFiles()?.filter { it.isFile }?.map { it.name } ?: emptyList()) +
            (publicDir().listFiles()?.filter { it.isFile }?.map { it.name } ?: emptyList())).toSet()

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
    fun enqueueUrl(
        url: String,
        isPrivate: Boolean,
        privateFolder: Boolean = false,
        suggestedFileName: String? = null
    ) {
        if (!DownloadNaming.isHttpUrl(url)) return
        val preferredName = suggestedFileName?.let { DownloadNaming.sanitize(it) }
            ?.takeIf { it.isNotBlank() } ?: DownloadNaming.fileName(null, url)
        val item = DownloadItem(ids.incrementAndGet(), url,
            DownloadNaming.uniqueName(preferredName, takenNames()), null, isPrivate)
        item.privateFolder = privateFolder
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

    // ---- file actions (long-press menu) -------------------------------------------------

    private fun publicDir() = File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "Veyra")

    private fun mimeFor(name: String): String =
        android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

    private fun contentUriNameExists(name: String): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        return context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            selection,
            arrayOf(name, "Download/Veyra"),
            null
        )?.use { it.moveToFirst() } ?: false
    }

    private fun fileNameCollision(name: String, excluding: DownloadItem? = null): Boolean {
        val itemCollision = items.any { it !== excluding && it.name.equals(name, ignoreCase = true) }
        val privateCollision = privateDir(context).listFiles()?.any {
            it.isFile && it.name.equals(name, ignoreCase = true) &&
                !(excluding?.inPrivateFolder == true && it.name == excluding.name)
        } ?: false
        val publicCollision = publicDir().listFiles()?.any {
            it.isFile && it.name.equals(name, ignoreCase = true) &&
                !(excluding?.inPrivateFolder == false && it.name == excluding.name)
        } ?: false
        return itemCollision || privateCollision || publicCollision || contentUriNameExists(name)
    }

    private fun withPreservedExtension(requested: String, existing: String): String {
        val clean = DownloadNaming.sanitize(requested)
        if (clean.isBlank()) return ""
        val dot = existing.lastIndexOf('.')
        val extension = existing.substring(dot + 1).takeIf { dot > 0 && it.isNotBlank() }
        return if ('.' !in clean && extension != null) "$clean.$extension" else clean
    }

    /** Copies and verifies a completed download before removing its public source. */
    fun moveToPrivate(item: DownloadItem): Boolean {
        if (item.state != DlState.DONE || item.isPrivate || item.inPrivateFolder) return false
        val sourceUri = item.savedUri?.let(Uri::parse) ?: return false
        val directory = privateDir(context).apply { mkdirs() }
        val reserved = (items.filter { it !== item && it.inPrivateFolder }.map { it.name } +
            (directory.listFiles()?.filter { it.isFile }?.map { it.name } ?: emptyList())).toSet()
        val targetName = DownloadNaming.uniqueName(item.name, reserved)
        val staging = File(directory, ".move-${item.id}.part")
        val target = File(directory, targetName)
        try {
            staging.delete()
            val bytesCopied = context.contentResolver.openInputStream(sourceUri)?.use { input ->
                staging.outputStream().use { output -> input.copyTo(output) }
            } ?: return false
            if (staging.length() != bytesCopied || (item.done >= 0 && bytesCopied != item.done) ||
                (item.total >= 0 && bytesCopied != item.total)) return false
            if (target.exists() || !staging.renameTo(target)) return false
            val privateUri = try {
                FileProvider.getUriForFile(context, "${context.packageName}.files", target)
            } catch (e: Exception) {
                target.delete()
                return false
            }
            if (!deleteSavedChecked(sourceUri)) {
                target.delete()
                return false
            }
            item.name = targetName
            item.privateFolder = true
            item.savedUri = privateUri.toString()
            persist()
            changedNow()
            return true
        } catch (_: Exception) {
            target.delete()
            return false
        } finally {
            staging.delete()
        }
    }

    /** Renames a completed download. Returns a user-readable error, or null on success. */
    fun rename(item: DownloadItem, requested: String): String? {
        val newName = withPreservedExtension(requested, item.name)
        if (newName.isBlank()) return "Enter a file name"
        if (newName == item.name) return null
        if (item.state != DlState.DONE) return "Wait for the download to finish"
        if (fileNameCollision(newName, item)) return "A file with that name already exists"
        return try {
            val uri = item.savedUri?.let(Uri::parse) ?: return "File not found"
            if (uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY) {
                val values = ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME, newName) }
                if (context.contentResolver.update(uri, values, null, null) < 1) return "Could not rename this file"
            } else {
                val directory = if (item.inPrivateFolder) privateDir(context) else publicDir()
                val source = File(directory, item.name)
                val target = File(directory, newName)
                if (!source.isFile) return "File not found"
                if (target.exists()) return "A file with that name already exists"
                if (!source.renameTo(target)) return "Could not rename this file"
                item.savedUri = FileProvider.getUriForFile(context, "${context.packageName}.files", target).toString()
            }
            item.name = newName
            persist()
            changedNow()
            null
        } catch (e: Exception) {
            "Could not rename: ${e.message ?: "unknown error"}"
        }
    }

    /** Renames a file in private storage and refreshes any persisted download URI that refers to it. */
    fun renamePrivateFile(file: File, requested: String): String? {
        val privateRoot = privateDir(context).canonicalFile
        if (!file.isFile || file.canonicalFile.parentFile != privateRoot) return "Private file not found"
        val newName = withPreservedExtension(requested, file.name)
        if (newName.isBlank()) return "Enter a file name"
        if (newName == file.name) return null
        val target = File(privateRoot, newName)
        if (target.exists() || fileNameCollision(newName)) return "A file with that name already exists"
        if (!file.renameTo(target)) return "Could not rename this file"
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", target).toString()
        items.filter { it.inPrivateFolder && it.name == file.name }.forEach {
            it.name = newName
            it.savedUri = uri
        }
        persist()
        changedNow()
        return null
    }

    /** Moves an unlocked private file to public Downloads only after its published copy is verified. */
    fun moveOutOfPrivate(file: File): Boolean {
        val privateRoot = runCatching { privateDir(context).canonicalFile }.getOrNull() ?: return false
        if (!file.isFile || file.canonicalFile.parentFile != privateRoot) return false
        val oldName = file.name
        val matchedItems = items.filter { it.inPrivateFolder && it.name == oldName }
        // Files saved by private browsing remain private; exporting them is not part of this action.
        if (matchedItems.any { it.isPrivate }) return false
        val publicNames = (items.filter { !it.inPrivateFolder }.map { it.name } +
            (publicDir().listFiles()?.filter { it.isFile }?.map { it.name } ?: emptyList())).toSet()
        var targetName = DownloadNaming.uniqueName(oldName, publicNames)
        if (contentUriNameExists(targetName)) {
            var index = 1
            val dot = targetName.lastIndexOf('.')
            val stem = if (dot > 0) targetName.substring(0, dot) else targetName
            val ext = if (dot > 0) targetName.substring(dot) else ""
            while (contentUriNameExists("$stem ($index)$ext") || "$stem ($index)$ext" in publicNames) index++
            targetName = "$stem ($index)$ext"
        }
        val mime = mimeFor(targetName)
        val size = file.length()
        var publishedUri: Uri? = null
        var targetFile: File? = null
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, targetName)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/Veyra")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
                publishedUri = uri
                val copied = file.inputStream().use { input ->
                    resolver.openOutputStream(uri)?.use { output -> input.copyTo(output) }
                        ?: throw java.io.IOException("Cannot write to Downloads")
                }
                if (copied != size) throw java.io.IOException("The copied file size did not match")
                val verifySize = resolver.openInputStream(uri)?.use { input ->
                    var count = 0L
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        count += read
                    }
                    count
                } ?: throw java.io.IOException("Cannot verify the saved file")
                if (verifySize != size) throw java.io.IOException("The saved file size did not match")
                val publishValues = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                if (resolver.update(uri, publishValues, null, null) < 1) throw java.io.IOException("Could not publish the file")
            } else {
                val directory = publicDir().apply { mkdirs() }
                val target = File(directory, targetName)
                if (target.exists()) return false
                file.copyTo(target, overwrite = false)
                targetFile = target
                if (target.length() != size) throw java.io.IOException("The copied file size did not match")
                publishedUri = FileProvider.getUriForFile(context, "${context.packageName}.files", target)
            }
            if (!file.delete()) throw java.io.IOException("Could not remove the private copy")
            val outputUri = publishedUri ?: throw java.io.IOException("Saved URI missing")
            if (matchedItems.isEmpty()) {
                items.add(0, DownloadItem(ids.incrementAndGet(), "", targetName, mime, false).apply {
                    total = size
                    done = size
                    state = DlState.DONE
                    savedUri = outputUri.toString()
                })
            } else {
                matchedItems.forEach {
                    it.name = targetName
                    it.mime = mime
                    it.privateFolder = false
                    it.total = size
                    it.done = size
                    it.state = DlState.DONE
                    it.savedUri = outputUri.toString()
                }
            }
            persist()
            changedNow()
            return true
        } catch (_: Exception) {
            // Keep the private original intact on failure; remove any incomplete public copy.
            publishedUri?.let { uri ->
                if (uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY) {
                    runCatching { context.contentResolver.delete(uri, null, null) }
                }
            }
            targetFile?.delete()
            return false
        }
    }

    private fun deleteSavedChecked(uri: Uri): Boolean {
        if (uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY) {
            return context.contentResolver.delete(uri, null, null) > 0
        }
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: return false
        val publicFile = File(publicDir(), name)
        val privateFile = File(privateDir(context), name)
        return when {
            publicFile.isFile -> publicFile.delete()
            privateFile.isFile -> privateFile.delete()
            else -> false
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
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("Connection", "keep-alive")
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
            val buffer = ByteArray(128 * 1024)
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

        // Private downloads must never be published to the shared MediaStore Downloads collection.
        // Keep them under app-specific storage so other apps and gallery/downloads scanners cannot
        // discover them. This is app-sandbox privacy, not encrypted vault storage.
        if (item.inPrivateFolder) {
            val dir = privateDir(context).apply { mkdirs() }
            val occupied = (items.filter { it !== item && it.inPrivateFolder }.map { it.name } +
                (dir.listFiles()?.filter { it.isFile }?.map { it.name } ?: emptyList())).toSet()
            item.name = DownloadNaming.uniqueName(item.name, occupied)
            val target = File(dir, item.name)
            if (target.exists()) throw java.io.IOException("A private file with that name already exists")
            source.copyTo(target, overwrite = false)
            return FileProvider.getUriForFile(context, "${context.packageName}.files", target)
        }

        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            var occupied = (items.filter { it !== item }.map { it.name } +
                (publicDir().listFiles()?.filter { it.isFile }?.map { it.name } ?: emptyList()) +
                (privateDir(context).listFiles()?.filter { it.isFile }?.map { it.name } ?: emptyList())).toSet()
            var candidate = DownloadNaming.uniqueName(item.name, occupied)
            while (contentUriNameExists(candidate)) {
                occupied = occupied + candidate
                candidate = DownloadNaming.uniqueName(item.name, occupied)
            }
            item.name = candidate
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, item.name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/Veyra")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw java.io.IOException("Downloads folder is unavailable")
            try {
                resolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
                    ?: throw java.io.IOException("Cannot write to Downloads")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                if (resolver.update(uri, values, null, null) <= 0) {
                    throw java.io.IOException("Could not publish the saved download")
                }
                return uri
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
        }
        val dir = publicDir().apply { mkdirs() }
        val occupied = (items.filter { it !== item }.map { it.name } +
            (dir.listFiles()?.filter { it.isFile }?.map { it.name } ?: emptyList()) +
            (privateDir(context).listFiles()?.filter { it.isFile }?.map { it.name } ?: emptyList())).toSet()
        item.name = DownloadNaming.uniqueName(item.name, occupied)
        val target = File(dir, item.name)
        if (target.exists()) throw java.io.IOException("A file with that name already exists")
        source.copyTo(target, overwrite = false)
        return FileProvider.getUriForFile(context, "${context.packageName}.files", target)
    }

    private fun deleteSaved(uri: Uri) {
        if (uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY) {
            context.contentResolver.delete(uri, null, null)
        } else {
            val path = uri.lastPathSegment ?: return
            val name = path.substringAfterLast('/')
            File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "Veyra/$name").delete()
            File(privateDir(context), name).delete()
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

    private fun notificationsAllowed(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Persistent download-only notification with live byte count and network throughput. */
    private fun notifyProgress(item: DownloadItem) {
        if (!notificationsAllowed()) return
        val totalKnown = item.total > 0
        val percent = if (totalKnown) ((item.done * 100L) / item.total).toInt().coerceIn(0, 100) else 0
        val title = if (item.isPrivate) "Private download in progress" else item.name
        val details = buildString {
            append(DownloadNaming.formatBytes(item.done))
            if (totalKnown) append(" of " + DownloadNaming.formatBytes(item.total))
            append(" · " + DownloadNaming.formatSpeed(item.speed))
        }
        val notification = NotificationCompat.Builder(context, "downloads")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(details)
            .setStyle(NotificationCompat.BigTextStyle().bigText(details))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(if (totalKnown) 100 else 0, percent, !totalKnown)
            .setVisibility(if (item.isPrivate) NotificationCompat.VISIBILITY_SECRET else NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(item.id.toInt(), notification)
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
            .setOngoing(false)
            .setProgress(0, 0, false)
            .setContentIntent(pending)
            .setVisibility(if (item.isPrivate) NotificationCompat.VISIBILITY_SECRET else NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(item.id.toInt(), notification)
    }

    private fun persist() {
        val array = JSONArray()
        items.filter { (!it.isPrivate || it.privateFolder) && it.state != DlState.RUNNING }.forEach { item ->
            array.put(JSONObject().apply {
                put("id", item.id); put("url", item.url); put("name", item.name)
                put("mime", item.mime ?: ""); put("total", item.total); put("done", item.done)
                put("state", item.state.name); put("error", item.error ?: "")
                put("uri", item.savedUri ?: ""); put("resumable", item.resumable); put("pf", item.privateFolder)
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
                item.privateFolder = o.optBoolean("pf", false)
                item.state = runCatching { DlState.valueOf(o.getString("state")) }.getOrDefault(DlState.FAILED)
                items.add(item)
            }
        } catch (_: Exception) {
            // Ignore a corrupt download list.
        }
    }

    companion object {
        /** Internal storage: invisible to Gallery, file managers and other apps. */
        fun privateDir(context: Context) = File(context.filesDir, "private_videos")
    }
}
