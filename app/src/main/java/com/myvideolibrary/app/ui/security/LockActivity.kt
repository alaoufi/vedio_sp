package com.myvideolibrary.app.ui.security

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
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
    @Inject lateinit var billingManager: com.myvideolibrary.app.security.BillingManager

    private val resumeIntent: Intent? by lazy {
        IntentCompat.getParcelableExtra(intent, EXTRA_RESUME_INTENT, Intent::class.java)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySecureFlags()

        // Start the free trial automatically on first launch (offline).
        licenseManager.ensureTrial()
        // Refresh the Play subscription in the background for next time.
        billingManager.refresh()

        // License gate: an active Play subscription unlocks automatically; otherwise
        // the offline licence/trial decides. Store payment needs no manual step.
        if (!billingManager.isEntitledCached() && licenseManager.needsActivation()) {
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
        val retryAfter = securityManager.pinRetryAfterMs()
        if (retryAfter > 0) {
            binding.errorText.isVisible = true
            binding.errorText.text = getString(R.string.pin_retry_later, (retryAfter + 999) / 1000)
            return
        }
        val pin = binding.pinInput.text?.toString().orEmpty()
        if (securityManager.verifyPin(pin)) {
            securityManager.recordSuccessfulPin()
            proceed()
        } else {
            securityManager.recordFailedPin()
            binding.errorText.isVisible = true
            val delay = securityManager.pinRetryAfterMs()
            binding.errorText.text = if (delay > 0) {
                getString(R.string.pin_retry_later, (delay + 999) / 1000)
            } else getString(R.string.wrong_pin)
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
        startActivity(resumeIntent ?: Intent(this, com.myvideolibrary.app.ui.main.MainActivity::class.java))
        finish()
    }

    companion object {
        private const val EXTRA_RESUME_INTENT = "resume_intent"

        fun intent(context: android.content.Context, resumeIntent: Intent): Intent =
            Intent(context, LockActivity::class.java).putExtra(EXTRA_RESUME_INTENT, resumeIntent)
    }
}
