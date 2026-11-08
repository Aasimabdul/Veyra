package com.veyra.browser

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted store for notes and imported files (AES-256-GCM). Both the item contents and the
 * item index (names, sizes) are encrypted. The key comes from [keyProvider]; on device it lives in
 * the Android Keystore and requires a recent device-credential confirmation to use.
 * There is no backup or recovery: if the key is lost the items cannot be decrypted.
 */
class PrivateVault(private val dir: File, private val keyProvider: () -> SecretKey) {
    data class Entry(val id: String, val name: String, val kind: String, val size: Long, val added: Long)

    class TooLargeException : Exception("File is larger than the Private Space limit")

    init { dir.mkdirs() }

    private val indexFile get() = File(dir, "index.enc")

    fun list(): List<Entry> {
        if (!indexFile.exists()) return emptyList()
        val json = JSONArray(String(decryptBytes(indexFile.readBytes()), Charsets.UTF_8))
        return (0 until json.length()).map { i ->
            val o = json.getJSONObject(i)
            Entry(o.getString("id"), o.getString("name"), o.getString("kind"), o.getLong("size"), o.getLong("added"))
        }.sortedByDescending { it.added }
    }

    fun addNote(name: String, text: String): Entry {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val entry = Entry(newId(), name.ifBlank { "Untitled note" }, "note", bytes.size.toLong(), System.currentTimeMillis())
        writeBlob(entry.id, bytes)
        saveIndex(list() + entry)
        return entry
    }

    fun updateNote(id: String, name: String, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        writeBlob(id, bytes)
        saveIndex(list().map { if (it.id == id) it.copy(name = name.ifBlank { it.name }, size = bytes.size.toLong()) else it })
    }

    fun readNote(id: String): String = String(decryptBytes(File(dir, "$id.enc").readBytes()), Charsets.UTF_8)

    /** Encrypts a stream into the vault. Fails with [TooLargeException] above [maxBytes]. */
    fun importFile(name: String, input: InputStream, maxBytes: Long): Entry {
        val id = newId()
        val target = File(dir, "$id.enc")
        val temp = File(dir, "$id.tmp")
        var total = 0L
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, keyProvider()) }
            temp.outputStream().use { file ->
                file.write(cipher.iv)
                CipherOutputStream(file, cipher).use { out ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > maxBytes) throw TooLargeException()
                        out.write(buffer, 0, read)
                    }
                }
            }
            if (!temp.renameTo(target)) throw java.io.IOException("Could not store the encrypted file")
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
        val entry = Entry(id, name.ifBlank { "file" }, "file", total, System.currentTimeMillis())
        saveIndex(list() + entry)
        return entry
    }

    /** Decrypts an item to [out]. Authentication failure (tampering) raises an exception. */
    fun exportTo(id: String, out: OutputStream) {
        File(dir, "$id.enc").inputStream().use { file ->
            val iv = ByteArray(IV_SIZE)
            if (file.read(iv) != IV_SIZE) throw java.io.IOException("Corrupt item")
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(TAG_BITS, iv))
            }
            CipherInputStream(file, cipher).use { it.copyTo(out) }
        }
    }

    fun delete(id: String) {
        File(dir, "$id.enc").delete()
        saveIndex(list().filterNot { it.id == id })
    }

    /** Removes everything (used when the key is lost). */
    fun wipe() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun saveIndex(entries: List<Entry>) {
        val json = JSONArray()
        entries.forEach {
            json.put(JSONObject().put("id", it.id).put("name", it.name).put("kind", it.kind)
                .put("size", it.size).put("added", it.added))
        }
        val temp = File(dir, "index.tmp")
        temp.writeBytes(encryptBytes(json.toString().toByteArray(Charsets.UTF_8)))
        if (!temp.renameTo(indexFile)) {
            indexFile.delete()
            temp.renameTo(indexFile)
        }
    }

    private fun writeBlob(id: String, plain: ByteArray) {
        val temp = File(dir, "$id.tmp")
        temp.writeBytes(encryptBytes(plain))
        val target = File(dir, "$id.enc")
        target.delete()
        if (!temp.renameTo(target)) throw java.io.IOException("Could not store the encrypted item")
    }

    private fun encryptBytes(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, keyProvider()) }
        return cipher.iv + cipher.doFinal(plain)
    }

    private fun decryptBytes(blob: ByteArray): ByteArray {
        if (blob.size < IV_SIZE + TAG_BITS / 8) throw java.io.IOException("Corrupt item")
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(TAG_BITS, blob.copyOfRange(0, IV_SIZE)))
        }
        return cipher.doFinal(blob, IV_SIZE, blob.size - IV_SIZE)
    }

    private fun newId(): String = java.util.UUID.randomUUID().toString().replace("-", "")

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private const val TAG_BITS = 128
        const val MAX_FILE_BYTES = 50L * 1024 * 1024
    }
}

/** Keystore-backed key; usable for [AUTH_WINDOW_SECONDS] after a device-credential confirmation. */
object VaultKeys {
    private const val ALIAS = "veyra_private_space_v1"
    const val AUTH_WINDOW_SECONDS = 120

    fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .apply {
                if (Build.VERSION.SDK_INT >= 30) {
                    setUserAuthenticationParameters(
                        AUTH_WINDOW_SECONDS,
                        KeyProperties.AUTH_DEVICE_CREDENTIAL or KeyProperties.AUTH_BIOMETRIC_STRONG
                    )
                } else {
                    @Suppress("DEPRECATION")
                    setUserAuthenticationValidityDurationSeconds(AUTH_WINDOW_SECONDS)
                }
            }
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply { init(spec) }
            .generateKey()
    }

    fun delete() {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS)
    }

    fun vault(context: Context) = PrivateVault(File(context.filesDir, "private_space")) { key() }
}
