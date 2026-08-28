package com.myvideolibrary.app.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generates and persists the SQLCipher passphrase.
 *
 * The passphrase is a 32-byte random value, created once on first launch. It is
 * stored in two independent places so a failure of either one never orphans the
 * (encrypted) database:
 *
 *  1. **Primary** — [EncryptedSharedPreferences], sealed by a hardware-backed
 *     [MasterKey]. This is the original store.
 *  2. **Backup** — the same passphrase wrapped by a dedicated Android Keystore AES
 *     key and kept in ordinary prefs. Keystore keys survive app updates, so if the
 *     EncryptedSharedPreferences keyset ever gets into a bad state (a known issue in
 *     the alpha security-crypto library) the passphrase is recovered from here
 *     instead of silently regenerating a new one — which would make the existing
 *     database undecryptable and *look* like all data was lost.
 *
 * Every backup path is best-effort and defensive: any failure is swallowed so the
 * primary behaviour is never made worse. The raw key never leaves the device and is
 * never written in plaintext (the backup copy is Keystore-encrypted).
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
     * Returns the database passphrase, recovering it from the Keystore-wrapped
     * backup if the primary store has lost it, and generating a new one only when
     * neither store has a value (a genuine first run).
     */
    fun getOrCreatePassphrase(): ByteArray {
        // 1) Primary store.
        runCatching { prefs.getString(KEY_PASSPHRASE, null) }.getOrNull()?.let { hex ->
            val bytes = hexToBytes(hex)
            // Make sure a Keystore backup exists for next time.
            runCatching { writeBackup(bytes) }
            return bytes
        }

        // 2) The primary store had nothing — try the Keystore-wrapped backup before
        //    assuming this is a first run. This is what prevents catastrophic loss.
        runCatching { readBackup() }.getOrNull()?.let { recovered ->
            // Heal the primary store so it works again next launch.
            runCatching { prefs.edit().putString(KEY_PASSPHRASE, bytesToHex(recovered)).apply() }
            return recovered
        }

        // 3) Genuine first run (or both stores unrecoverable): create a fresh key.
        val fresh = ByteArray(KEY_SIZE_BYTES).also { SecureRandom().nextBytes(it) }
        runCatching { prefs.edit().putString(KEY_PASSPHRASE, bytesToHex(fresh)).apply() }
        runCatching { writeBackup(fresh) }
        return fresh
    }

    // ---- Keystore-wrapped backup of the passphrase ----

    private fun writeBackup(passphrase: ByteArray) {
        val cipher = Cipher.getInstance(WRAP_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, wrapKey())
        val iv = cipher.iv
        val cipherText = cipher.doFinal(passphrase)
        val blob = Base64.encodeToString(iv + cipherText, Base64.NO_WRAP)
        context.getSharedPreferences(BACKUP_PREFS, Context.MODE_PRIVATE)
            .edit().putString(BACKUP_KEY, blob).apply()
    }

    private fun readBackup(): ByteArray? {
        val blob = context.getSharedPreferences(BACKUP_PREFS, Context.MODE_PRIVATE)
            .getString(BACKUP_KEY, null) ?: return null
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        if (raw.size <= GCM_IV_LEN) return null
        val iv = raw.copyOfRange(0, GCM_IV_LEN)
        val cipherText = raw.copyOfRange(GCM_IV_LEN, raw.size)
        val cipher = Cipher.getInstance(WRAP_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(cipherText)
    }

    /** The persistent Android Keystore AES key used to wrap the passphrase backup. */
    private fun wrapKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(WRAP_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                WRAP_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
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

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val WRAP_ALIAS = "mvl_db_key_wrap"
        private const val WRAP_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val BACKUP_PREFS = "mvl_key_backup"
        private const val BACKUP_KEY = "wrapped_passphrase"
        private const val GCM_IV_LEN = 12
        private const val GCM_TAG_BITS = 128
    }
}
