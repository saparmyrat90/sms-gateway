package com.gateway.sms

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class GatewaySettings(
    val serverUrl: String = "",
    val gatewayKey: String = "",
    val pollIntervalSec: Int = 3,
    /** -1 = system default SIM, otherwise the physical slot index (0, 1). */
    val simSlot: Int = -1,
    val startOnBoot: Boolean = true,
    val maxPerMinute: Int = 30,
    /** True while the user wants the gateway running (used to restore after reboot / process death). */
    val enabled: Boolean = false,
)

/** AES-GCM encryption with a non-exportable Android Keystore key. */
internal object SecretBox {
    private const val ALIAS = "sms_gateway_key"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun decrypt(encoded: String): String {
        if (encoded.isEmpty()) return ""
        return try {
            val raw = Base64.decode(encoded, Base64.NO_WRAP)
            val iv = raw.copyOfRange(0, 12)
            val c = Cipher.getInstance(TRANSFORM)
                .apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
            String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
        } catch (e: Exception) {
            "" // key lost (e.g. app data restored on another device): user must re-enter it
        }
    }
}

class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("gateway", Context.MODE_PRIVATE)

    fun load(): GatewaySettings = GatewaySettings(
        serverUrl = prefs.getString("server_url", "") ?: "",
        gatewayKey = SecretBox.decrypt(prefs.getString("gateway_key", "") ?: ""),
        pollIntervalSec = prefs.getInt("poll_interval_sec", 3),
        simSlot = prefs.getInt("sim_slot", -1),
        startOnBoot = prefs.getBoolean("start_on_boot", true),
        maxPerMinute = prefs.getInt("max_per_minute", 30),
        enabled = prefs.getBoolean("enabled", false),
    )

    fun save(s: GatewaySettings) {
        prefs.edit()
            .putString("server_url", s.serverUrl.trim())
            .putString("gateway_key", SecretBox.encrypt(s.gatewayKey.trim()))
            .putInt("poll_interval_sec", s.pollIntervalSec)
            .putInt("sim_slot", s.simSlot)
            .putBoolean("start_on_boot", s.startOnBoot)
            .putInt("max_per_minute", s.maxPerMinute)
            .putBoolean("enabled", s.enabled)
            .apply()
    }

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("enabled", enabled).apply()
    }
}
