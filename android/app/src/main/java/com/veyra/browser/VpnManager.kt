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

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val file = File(app.filesDir, "vpn_config.enc")

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

    /** Intent for the one-time Android "VPN connection request" dialog, or null if already granted. */
    fun permissionIntent(): Intent? = android.net.VpnService.prepare(app)

    fun summary(): VpnConfigSummary? = configText()?.let { VpnConfigSummary.of(it) }

    /** Validates with the WireGuard parser, then stores encrypted. Throws IllegalArgumentException. */
    fun saveConfig(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) throw IllegalArgumentException("The file is empty.")
        if (trimmed.length > MAX_CONFIG_CHARS) throw IllegalArgumentException("That file is too large to be a WireGuard config.")
        try {
            Config.parse(ByteArrayInputStream(trimmed.toByteArray(Charsets.UTF_8)))
        } catch (e: Exception) {
            throw IllegalArgumentException("Not a valid WireGuard config: ${e.message ?: "unreadable"}")
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        file.writeBytes(cipher.iv + cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8)))
        lastError = null
        status = Status.OFF
        notifyChanged()
    }

    private fun configText(): String? {
        if (!file.exists()) return null
        return try {
            val bytes = file.readBytes()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
            }
            String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
        } catch (_: Exception) { null }
    }

    fun deleteConfig() {
        if (status != Status.OFF) disconnect()
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
            status = if (running) Status.ON else Status.OFF
            if (running) lastError = null
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
