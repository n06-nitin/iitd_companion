package com.xlonlabs.iitdcompanion

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Credentials are encrypted with an AES-256-GCM key that lives in the
 * AndroidKeyStore (hardware-backed / TEE where available). The key never
 * leaves the keystore; only ciphertext is written to SharedPreferences.
 * No third-party crypto dependency, so the APK stays tiny.
 */
data class Creds(val kerberos: String, val password: String)

object SecureCreds {
    private const val PREFS = "rch_secure"
    private const val ALIAS = "rch_master_key"
    private const val KS = "AndroidKeyStore"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(KS).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KS)
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    /** Returns "ivBase64:cipherBase64". */
    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return b64(iv) + ":" + b64(ct)
    }

    private fun decrypt(stored: String): String {
        val parts = stored.split(":")
        val iv = unb64(parts[0]); val ct = unb64(parts[1])
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    fun save(ctx: Context, c: Creds) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("u", encrypt(c.kerberos))
            .putString("p", encrypt(c.password))
            .apply()
    }

    fun load(ctx: Context): Creds? = try {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val u = sp.getString("u", null) ?: return null
        val p = sp.getString("p", null) ?: return null
        Creds(decrypt(u), decrypt(p))
    } catch (e: Exception) {
        null // key/data lost (e.g. lock-screen change) -> treat as no creds
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)
}
