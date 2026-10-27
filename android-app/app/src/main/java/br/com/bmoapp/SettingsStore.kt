package br.com.bmoapp

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Armazenamento local. Host/porta/volume ficam em SharedPreferences; o token
 * é cifrado com AES/GCM e uma chave do Android Keystore (não fica em texto puro).
 */
class SettingsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("bmo_prefs", Context.MODE_PRIVATE)
    private val tokenStore = SecureTokenStore()

    fun load(): AppSettings {
        return AppSettings(
            host = prefs.getString(KEY_HOST, "") ?: "",
            port = prefs.getInt(KEY_PORT, 8765),
            token = tokenStore.decrypt(prefs.getString(KEY_TOKEN, "") ?: ""),
            volume = prefs.getFloat(KEY_VOLUME, 0.9f),
            keepScreenOn = prefs.getBoolean(KEY_KEEP_SCREEN_ON, false),
            backgroundListening = prefs.getBoolean(KEY_BACKGROUND, false),
            autoConnect = prefs.getBoolean(KEY_AUTO_CONNECT, true),
        )
    }

    fun save(settings: AppSettings) {
        prefs.edit()
            .putString(KEY_HOST, settings.host.trim())
            .putInt(KEY_PORT, settings.port)
            .putString(KEY_TOKEN, tokenStore.encrypt(settings.token.trim()))
            .putFloat(KEY_VOLUME, settings.volume)
            .putBoolean(KEY_KEEP_SCREEN_ON, settings.keepScreenOn)
            .putBoolean(KEY_BACKGROUND, settings.backgroundListening)
            .putBoolean(KEY_AUTO_CONNECT, settings.autoConnect)
            .apply()
    }

    fun saveVolume(volume: Float) {
        prefs.edit().putFloat(KEY_VOLUME, volume).apply()
    }

    companion object {
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_TOKEN = "token_cifrado"
        private const val KEY_VOLUME = "volume"
        private const val KEY_KEEP_SCREEN_ON = "manter_tela_ligada"
        private const val KEY_BACKGROUND = "escuta_segundo_plano"
        private const val KEY_AUTO_CONNECT = "conectar_automaticamente"
    }
}

private class SecureTokenStore {
    private val alias = "bmo_token_key_v1"

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun getOrCreateKey(): SecretKey {
        val store = keyStore()
        val existing = store.getEntry(alias, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun encrypt(value: String): String {
        if (value.isEmpty()) return ""
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val combined = cipher.iv + encrypted
            Base64.encodeToString(combined, Base64.NO_WRAP)
        }.getOrDefault("")
    }

    fun decrypt(value: String): String {
        if (value.isEmpty()) return ""
        return runCatching {
            val combined = Base64.decode(value, Base64.NO_WRAP)
            if (combined.size < 13) return ""
            val iv = combined.copyOfRange(0, 12)
            val encrypted = combined.copyOfRange(12, combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        }.getOrDefault("")
    }
}
