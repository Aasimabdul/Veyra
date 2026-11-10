package com.veyra.browser

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Device-wide VPN using the WireGuard engine (wireguard-android, userspace Go backend).
 *
 * Veyra does not run VPN servers. The user imports a standard WireGuard `.conf` from a provider
 * or from their own server. The config (it contains a private key) is stored encrypted with a
 * non-exportable Android Keystore key and is never written to logs.
 */
class VpnManager private constructor(context: Context) {
    enum class Status { OFF, CONNECTING, ON, DISCONNECTING, ERROR }

    data class ProfileInfo(val id: String, val name: String, val endpoint: String, val active: Boolean)
    private data class ProfileRecord(val id: String, val name: String, val endpoint: String)

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val file = File(app.filesDir, "vpn_config.enc")
    private val profilesDir = File(app.filesDir, "vpn_profiles").apply { mkdirs() }
    private val profilePrefs = app.getSharedPreferences("veyra_vpn_profiles", Context.MODE_PRIVATE)

    @Volatile var status = Status.OFF
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile var listener: (() -> Unit)? = null

    private val backend: GoBackend? by lazy {
        try { GoBackend(app) } catch (e: Throwable) {
            lastError = "The VPN engine could not start on this device."
            null
        }
    }

    private val tunnel = object : Tunnel {
        override fun getName(): String = TUNNEL_NAME
        override fun onStateChange(newState: Tunnel.State) {
            status = if (newState == Tunnel.State.UP) Status.ON else Status.OFF
            notifyChanged()
        }
    }

    private fun notifyChanged() { main.post { listener?.invoke() } }

    fun hasConfig(): Boolean = file.exists()

    /** Saved profile labels/endpoints; the legacy single imported config remains selectable too. */
    fun profiles(): List<ProfileInfo> {
        val records = storedProfiles()
        val activeId = profilePrefs.getString("active_profile_id", "").orEmpty()
        val result = records.map { ProfileInfo(it.id, it.name, it.endpoint, it.id == activeId) }.toMutableList()
        if (file.exists() && (activeId.isBlank() || records.none { it.id == activeId })) {
            val endpoint = summary()?.endpoints?.firstOrNull().orEmpty()
            result.add(0, ProfileInfo("legacy", "Current WireGuard profile", endpoint, true))
        }
        return result
    }

    private fun storedProfiles(): List<ProfileRecord> {
        val array = try { JSONArray(profilePrefs.getString("profiles", "[]") ?: "[]") }
        catch (_: Exception) { JSONArray() }
        val result = ArrayList<ProfileRecord>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optString("id")
            val name = item.optString("name").trim()
            val endpoint = item.optString("endpoint")
            if (!id.matches(Regex("[A-Za-z0-9_-]{1,100}")) || name.isBlank()) continue
            if (File(profilesDir, "$id.enc").isFile) result.add(ProfileRecord(id, name, endpoint))
        }
        return result
    }

    private fun persistProfiles(records: List<ProfileRecord>) {
        val array = JSONArray()
        records.forEach { record ->
            array.put(JSONObject().apply {
                put("id", record.id); put("name", record.name); put("endpoint", record.endpoint)
            })
        }
        profilePrefs.edit().putString("profiles", array.toString()).apply()
    }

    private fun encryptConfig(text: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return cipher.iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8))
    }

    private fun decryptConfig(bytes: ByteArray): String {
        require(bytes.size > 12) { "Encrypted config is incomplete." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
        }
        return String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
    }

    /** Save a named, encrypted provider profile and select it when no tunnel is active. */
    fun saveProfile(text: String, requestedName: String): ProfileInfo {
        val trimmed = validateConfig(text)
        val name = requestedName.trim().take(48).ifBlank { "WireGuard server" }
        val summary = VpnConfigSummary.of(trimmed)
        val endpoint = summary.endpoints.firstOrNull().orEmpty()
        val records = storedProfiles().toMutableList()
        val slug = name.lowercase().replace(Regex("[^a-z0-9_-]+"), "-").trim('-').take(24).ifBlank { "server" }
        var id = "profile_${System.currentTimeMillis()}_$slug"
        var suffix = 1
        while (File(profilesDir, "$id.enc").exists()) { id = "profile_${System.currentTimeMillis()}_$slug-${suffix++}" }
        File(profilesDir, "$id.enc").writeBytes(encryptConfig(trimmed))
        records.add(ProfileRecord(id, name, endpoint))
        persistProfiles(records)

        val canSelect = status != Status.ON && status != Status.CONNECTING && status != Status.DISCONNECTING
        if (canSelect) {
            file.writeBytes(encryptConfig(trimmed))
            profilePrefs.edit().putString("active_profile_id", id).apply()
            status = Status.OFF
            lastError = null
        }
        notifyChanged()
        return ProfileInfo(id, name, endpoint, canSelect)
    }

    /** Switch to a saved profile only when the current tunnel is down. */
    fun selectProfile(id: String) {
        check(status != Status.ON && status != Status.CONNECTING && status != Status.DISCONNECTING) {
            "Disconnect the current tunnel before switching VPN profiles."
        }
        if (id == "legacy") {
            check(file.isFile) { "The previous profile is no longer available." }
            profilePrefs.edit().remove("active_profile_id").apply()
            status = Status.OFF
            lastError = null
            notifyChanged()
            return
        }
        val record = storedProfiles().firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("That saved VPN profile no longer exists.")
        val profileFile = File(profilesDir, "$id.enc")
        val text = try { decryptConfig(profileFile.readBytes()) }
        catch (_: Exception) { throw IllegalArgumentException("Could not decrypt this profile. Import it again.") }
        val validated = validateConfig(text)
        file.writeBytes(encryptConfig(validated))
        profilePrefs.edit().putString("active_profile_id", record.id).apply()
        status = Status.OFF
        lastError = null
        notifyChanged()
    }

    fun deleteProfile(id: String) {
        check(status != Status.ON && status != Status.CONNECTING && status != Status.DISCONNECTING) {
            "Disconnect the current tunnel before removing a VPN profile."
        }
        if (id == "legacy") {
            file.delete()
            profilePrefs.edit().remove("active_profile_id").apply()
        } else {
            File(profilesDir, "$id.enc").delete()
            persistProfiles(storedProfiles().filterNot { it.id == id })
            if (profilePrefs.getString("active_profile_id", "") == id) {
                file.delete()
                profilePrefs.edit().remove("active_profile_id").apply()
            }
        }
        status = Status.OFF
        lastError = null
        notifyChanged()
    }

    /** Intent for the one-time Android "VPN connection request" dialog, or null if already granted. */
    fun permissionIntent(): Intent? = android.net.VpnService.prepare(app)

    fun summary(): VpnConfigSummary? = configText()?.let { VpnConfigSummary.of(it) }

    /** Validates with the WireGuard parser, then stores the active config encrypted. */
    fun saveConfig(text: String) {
        val trimmed = validateConfig(text)
        file.writeBytes(encryptConfig(trimmed))
        profilePrefs.edit().remove("active_profile_id").apply()
        lastError = null
        status = Status.OFF
        notifyChanged()
    }

    private fun validateConfig(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) throw IllegalArgumentException("The file is empty.")
        if (trimmed.length > MAX_CONFIG_CHARS) throw IllegalArgumentException("That file is too large to be a WireGuard config.")
        try {
            Config.parse(ByteArrayInputStream(trimmed.toByteArray(Charsets.UTF_8)))
        } catch (e: Exception) {
            throw IllegalArgumentException("Not a valid WireGuard config: ${e.message ?: "unreadable"}")
        }
        return trimmed
    }

    private fun configText(): String? {
        if (!file.exists()) return null
        return try { decryptConfig(file.readBytes()) } catch (_: Exception) { null }
    }

    fun deleteConfig() {
        if (status == Status.ON || status == Status.CONNECTING) disconnect()
        file.delete()
        lastError = null
        notifyChanged()
    }

    /**
     * One-tap free VPN: registers a Cloudflare WARP identity on this device and stores it as the
     * config. [onDone] runs on the UI thread with an error message, or null on success.
     */
    fun provisionFree(onDone: (String?) -> Unit) {
        status = Status.CONNECTING
        lastError = null
        notifyChanged()
        Thread {
            val error = try {
                saveConfig(WarpProvisioner.register())
                null
            } catch (e: Throwable) {
                e.message ?: "Could not set up the free VPN"
            }
            if (error != null) {
                status = Status.ERROR
                lastError = error
            }
            main.post { notifyChanged(); onDone(error) }
        }.apply { name = "veyra-vpn-setup"; isDaemon = true }.start()
    }

    /** Call after the permission dialog has been accepted. Runs the blocking setup off the UI thread. */
    fun connect() {
        val text = configText()
        val engine = backend
        if (text == null) { fail("No VPN config is saved, or it can no longer be decrypted. Import it again."); return }
        if (engine == null) { fail(lastError ?: "The VPN engine is unavailable."); return }
        status = Status.CONNECTING
        lastError = null
        notifyChanged()
        Thread {
            try {
                val config = Config.parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
                engine.setState(tunnel, Tunnel.State.UP, config)
                status = Status.ON
            } catch (e: Throwable) {
                status = Status.ERROR
                lastError = "Could not connect: ${e.message ?: e.javaClass.simpleName}"
            }
            notifyChanged()
        }.apply { name = "veyra-vpn-up"; isDaemon = true }.start()
    }

    fun disconnect() {
        val engine = backend
        if (engine == null) {
            fail(lastError ?: "The VPN engine is unavailable, so Veyra cannot confirm tunnel shutdown.")
            return
        }
        status = Status.DISCONNECTING
        lastError = null
        notifyChanged()
        Thread {
            try {
                engine.setState(tunnel, Tunnel.State.DOWN, null)
                status = Status.OFF
                lastError = null
            } catch (e: Throwable) {
                status = Status.ERROR
                lastError = "Could not disconnect cleanly: ${e.message ?: e.javaClass.simpleName}"
            }
            notifyChanged()
        }.apply { name = "veyra-vpn-down"; isDaemon = true }.start()
    }

    /** Re-reads the real tunnel state (the service can outlive the Activity). */
    fun refresh() {
        if (status == Status.CONNECTING || status == Status.DISCONNECTING) return
        val previousStatus = status
        val previousError = lastError
        try {
            val engine = backend
            val running = engine != null && engine.runningTunnelNames.contains(TUNNEL_NAME)
            if (running) {
                status = Status.ON
                lastError = null
            } else if (status != Status.ERROR) {
                status = Status.OFF
            } // an ERROR stays visible until the user acts, instead of being reset to OFF
        } catch (e: Throwable) {
            status = Status.ERROR
            lastError = "Could not read VPN status: " + (e.message ?: e.javaClass.simpleName)
        }
        // The VPN screen refreshes itself from this listener. Notify only when the observed
        // backend state actually changes; unconditional notifications cause a refresh/render loop.
        if (status != previousStatus || lastError != previousError) notifyChanged()
    }

    private fun fail(message: String) {
        status = Status.ERROR
        lastError = message
        notifyChanged()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val TUNNEL_NAME = "veyra"
        private const val KEY_ALIAS = "veyra_vpn_config_v1"
        private const val MAX_CONFIG_CHARS = 20_000

        @Volatile private var instance: VpnManager? = null

        fun get(context: Context): VpnManager =
            instance ?: synchronized(this) { instance ?: VpnManager(context).also { instance = it } }
    }
}
