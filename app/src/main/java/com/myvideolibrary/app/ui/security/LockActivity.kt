package com.myvideolibrary.app.ui.security

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.myvideolibrary.app.R
import com.myvideolibrary.app.databinding.ActivityLockBinding
import com.myvideolibrary.app.security.AppLockManager
import com.myvideolibrary.app.security.SecurityManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Launcher gate. When app-lock is configured and the session isn't authenticated,
 * requires a PIN (and optionally biometrics) before revealing the library.
 */
@AndroidEntryPoint
class LockActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLockBinding

    @Inject lateinit var securityManager: SecurityManager
    @Inject lateinit var appLockManager: AppLockManager
    @Inject lateinit var licenseManager: com.myvideolibrary.app.security.LicenseManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySecureFlags()

        // Start the free trial automatically on first launch (offline).
        licenseManager.ensureTrial()

        // License gate first: an unactivated app must be activated before anything.
        if (licenseManager.needsActivation()) {
            startActivity(Intent(this, ActivationActivity::class.java))
            finish()
            return
        }

        if (!appLockManager.shouldLock()) {
            proceed()
            return
        }

        binding = ActivityLockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.unlockButton.setOnClickListener { submitPin() }
        binding.biometricButton.isVisible = securityManager.biometricEnabled && canUseBiometrics()
        binding.biometricButton.setOnClickListener { showBiometricPrompt() }

        if (securityManager.biometricEnabled && canUseBiometrics()) {
            showBiometricPrompt()
        }
    }

    private fun applySecureFlags() {
        if (securityManager.preventScreenshots || securityManager.hideInRecents) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        }
    }

    private fun submitPin() {
        val pin = binding.pinInput.text?.toString().orEmpty()
        if (securityManager.verifyPin(pin)) {
            proceed()
        } else {
            binding.errorText.isVisible = true
            binding.errorText.setText(R.string.wrong_pin)
            binding.pinInput.text?.clear()
        }
    }

    private fun canUseBiometrics(): Boolean =
        BiometricManager.from(this).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_WEAK
        ) == BiometricManager.BIOMETRIC_SUCCESS

    private fun showBiometricPrompt() {
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(
            this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    proceed()
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.unlock_title))
            .setSubtitle(getString(R.string.unlock_biometric_subtitle))
            .setNegativeButtonText(getString(R.string.use_pin))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .build()
        prompt.authenticate(info)
    }

    private fun proceed() {
        appLockManager.markAuthenticated()
        startActivity(Intent(this, com.myvideolibrary.app.ui.main.MainActivity::class.java))
        finish()
    }
}
