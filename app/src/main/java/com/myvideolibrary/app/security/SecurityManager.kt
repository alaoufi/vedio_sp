package com.myvideolibrary.app.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns all security preferences and PIN verification. Backed by
 * [EncryptedSharedPreferences] so reads are synchronous (needed for window flags
 * and the lock gate) while remaining encrypted at rest.
 *
 * The raw PIN is never stored — only a salted SHA-256 hash.
 */
@Singleton
class SecurityManager @Inject constructor(
    @ApplicationContext context: Context
) {

    private val prefs: SharedPreferences by lazy {
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

    // ---- Flags ----

    var appLockEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOCK_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_LOCK_ENABLED, value).apply()

    var biometricEnabled: Boolean
        get() = prefs.getBoolean(KEY_BIOMETRIC, false)
        set(value) = prefs.edit().putBoolean(KEY_BIOMETRIC, value).apply()

    var preventScreenshots: Boolean
        get() = prefs.getBoolean(KEY_PREVENT_SCREENSHOTS, true)
        set(value) = prefs.edit().putBoolean(KEY_PREVENT_SCREENSHOTS, value).apply()

    var hideInRecents: Boolean
        get() = prefs.getBoolean(KEY_HIDE_RECENTS, true)
        set(value) = prefs.edit().putBoolean(KEY_HIDE_RECENTS, value).apply()

    val hasPin: Boolean get() = prefs.getString(KEY_PIN_HASH, null) != null

    /** True when the app should present the lock screen. */
    val isLockConfigured: Boolean get() = appLockEnabled && hasPin

    // ---- PIN ----

    fun setPin(pin: String) {
        val salt = ByteArray(SALT_SIZE).also { SecureRandom().nextBytes(it) }
        val hash = hash(pin, salt)
        prefs.edit()
            .putString(KEY_PIN_SALT, salt.toHex())
            .putString(KEY_PIN_HASH, hash)
            .apply()
    }

    fun clearPin() {
        prefs.edit()
            .remove(KEY_PIN_SALT)
            .remove(KEY_PIN_HASH)
            .putBoolean(KEY_LOCK_ENABLED, false)
            .putBoolean(KEY_BIOMETRIC, false)
            .apply()
    }

    fun verifyPin(pin: String): Boolean {
        val saltHex = prefs.getString(KEY_PIN_SALT, null) ?: return false
        val expected = prefs.getString(KEY_PIN_HASH, null) ?: return false
        return hash(pin, saltHex.fromHex()) == expected
    }

    private fun hash(pin: String, salt: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        return digest.digest(pin.toByteArray(Charsets.UTF_8)).toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.fromHex(): ByteArray =
        ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    companion object {
        private const val PREFS_NAME = "mvl_security"
        private const val KEY_LOCK_ENABLED = "lock_enabled"
        private const val KEY_BIOMETRIC = "biometric_enabled"
        private const val KEY_PREVENT_SCREENSHOTS = "prevent_screenshots"
        private const val KEY_HIDE_RECENTS = "hide_recents"
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_PIN_SALT = "pin_salt"
        private const val SALT_SIZE = 16
    }
}
