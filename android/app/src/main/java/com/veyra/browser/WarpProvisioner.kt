package com.veyra.browser

import com.wireguard.crypto.KeyPair
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Sets up a free Cloudflare WARP identity on this device and turns it into a standard WireGuard
 * config, so the VPN works with one tap and no account. WARP encrypts traffic and hides it from the
 * local network and ISP, but it does not let the user pick a country. The registration endpoint is
 * Cloudflare's unofficial consumer API (the same one open-source tools such as wgcf use) and may
 * change or block a network without notice. The private key is generated on the device.
 */
object WarpProvisioner {
    private const val REGISTER_URL = "https://api.cloudflareclient.com/v0a2158/reg"
    private const val DEFAULT_PORT = 2408

    /** Pure conversion of Cloudflare's registration response into a WireGuard .conf (unit-tested). */
    fun buildConfig(privateKey: String, registrationJson: String): String {
        val config = try {
            JSONObject(registrationJson).getJSONObject("config")
        } catch (e: Exception) {
            throw IOException("Unexpected reply from Cloudflare")
        }
        val peer = config.optJSONArray("peers")?.optJSONObject(0)
            ?: throw IOException("Cloudflare returned no server")
        val peerKey = peer.optString("public_key")
        var endpoint = peer.optJSONObject("endpoint")?.optString("host").orEmpty()
        if (peerKey.isBlank() || endpoint.isBlank()) throw IOException("Cloudflare returned no server")
        if (!endpoint.contains(':')) endpoint += ":$DEFAULT_PORT"
        val addresses = config.optJSONObject("interface")?.optJSONObject("addresses")
        val v4 = addresses?.optString("v4").orEmpty()
        val v6 = addresses?.optString("v6").orEmpty()
        val device = listOfNotNull(
            v4.takeIf { it.isNotBlank() }?.let { "$it/32" },
            v6.takeIf { it.isNotBlank() }?.let { "$it/128" }
        )
        if (device.isEmpty()) throw IOException("Cloudflare returned no device address")
        return buildString {
            appendLine("[Interface]")
            appendLine("PrivateKey = $privateKey")
            appendLine("Address = ${device.joinToString(", ")}")
            appendLine("DNS = 1.1.1.1, 1.0.0.1")
            appendLine("MTU = 1280")
            appendLine()
            appendLine("[Peer]")
            appendLine("PublicKey = $peerKey")
            appendLine("AllowedIPs = 0.0.0.0/0, ::/0")
            appendLine("Endpoint = $endpoint")
        }
    }

    /** Blocking network call: run off the UI thread. Returns a WireGuard config. */
    fun register(): String {
        val pair = KeyPair()
        val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        val body = JSONObject()
            .put("key", pair.publicKey.toBase64())
            .put("install_id", "")
            .put("fcm_token", "")
            .put("tos", now)
            .put("type", "Android")
            .put("model", "PC")
            .put("locale", "en_US")
            .toString()
        val connection = URL(REGISTER_URL).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.setRequestProperty("User-Agent", "okhttp/3.12.1")
            connection.setRequestProperty("CF-Client-Version", "a-6.3-4500")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("Cloudflare refused the setup (HTTP $code). Try another network or import a config.")
            val reply = connection.inputStream.bufferedReader().use { it.readText() }
            return buildConfig(pair.privateKey.toBase64(), reply)
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Could not reach Cloudflare: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            connection.disconnect()
        }
    }
}
