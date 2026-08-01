package com.myvideolibrary.app.data.local

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generates and persists the SQLCipher passphrase.
 *
 * The passphrase is a 32-byte random value, created once on first launch and
 * stored inside [EncryptedSharedPreferences], which is itself sealed by a
 * hardware-backed [MasterKey] in the Android Keystore. The raw key never leaves
 * the device and is never written in plaintext.
 */
@Singleton
class DatabaseKeyManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * Returns the database passphrase as a byte array, generating and storing a
     * new random one on first access.
     */
    fun getOrCreatePassphrase(): ByteArray {
        prefs.getString(KEY_PASSPHRASE, null)?.let {
            return hexToBytes(it)
        }
        val fresh = ByteArray(KEY_SIZE_BYTES).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString(KEY_PASSPHRASE, bytesToHex(fresh)).apply()
        return fresh
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }

    companion object {
        private const val PREFS_NAME = "mvl_secure_prefs"
        private const val KEY_PASSPHRASE = "db_passphrase"
        private const val KEY_SIZE_BYTES = 32
    }
}
