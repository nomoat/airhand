package ai.rebyte.mobile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The API key is encrypted at rest; the wrapping key never leaves Android Keystore. */
class Secrets(private val context: Context) {
    private val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)
    private fun wrappingKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey("rebyte-api-key", null)
        if (existing != null) return existing as SecretKey
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder("rebyte-api-key",
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    fun hasKey() = prefs.contains("ciphertext")
    fun save(value: String) {
        require(value.matches(Regex("rbk_[A-Za-z0-9_-]+"))) { "Enter a valid Rebyte API key starting with rbk_." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        check(prefs.edit()
            .putString("ciphertext", Base64.encodeToString(cipher.doFinal(value.toByteArray()), Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).commit())
    }
    fun read(): String {
        val ciphertext = requireNotNull(prefs.getString("ciphertext", null)) { "Set your API key first." }
        val iv = requireNotNull(prefs.getString("iv", null))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(),
            GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)))
    }
    fun clear() { check(prefs.edit().clear().commit()) }

    /** adb run-as can provision a debug APK over stdin; release builds cannot import files. */
    fun importDebugKey() {
        if (!BuildConfig.DEBUG) return
        val input = File(context.noBackupFilesDir, "debug-api-key")
        if (!input.exists()) return
        try { save(input.readText().trim()) } finally { check(input.delete()) }
    }
}
