package ru.ix1ax.kotel

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class YandexHomeStore(context: Context) {
    private val prefs = context.getSharedPreferences("yandex_home", Context.MODE_PRIVATE)

    val clientId: String get() = prefs.getString("client_id", "").orEmpty()
    val verifier: String? get() = readSecret("control_verifier")
    val session: YandexSession?
        get() = readSecret("token")?.let {
            YandexSession(it, prefs.getLong("expires_at", 0), prefs.getBoolean("can_control", false))
        }

    fun saveVerifier(clientId: String, verifier: String) {
        check(prefs.edit().putString("client_id", clientId)
            .remove("verifier").putString("control_verifier", encrypt(verifier)).commit())
    }

    fun saveSession(session: YandexSession) {
        check(prefs.edit().remove("control_verifier")
            .putString("token", encrypt(session.accessToken))
            .putLong("expires_at", session.expiresAt)
            .putBoolean("can_control", session.canControl).commit())
    }

    fun clearSession() {
        prefs.edit().remove("token").remove("expires_at").remove("can_control")
            .remove("verifier").remove("control_verifier").apply()
    }

    private fun readSecret(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        return try {
            val encrypted = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, encrypted, 0, IV_BYTES))
            cipher.doFinal(encrypted, IV_BYTES, encrypted.size - IV_BYTES).toString(Charsets.UTF_8)
        } catch (_: Exception) {
            // После потери ключа пользователь может подключить аккаунт заново.
            prefs.edit().remove(name).apply()
            null
        }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "yandex_home_aes"
        const val IV_BYTES = 12
    }
}
