package com.myvideolibrary.app.ui.security

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.myvideolibrary.app.R
import com.myvideolibrary.app.databinding.ActivityActivationBinding
import com.myvideolibrary.app.security.LicenseManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Offline activation gate. Shows the device number and accepts a signed code
 * (or the owner's secret seed). Blocks the app until activated.
 */
@AndroidEntryPoint
class ActivationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityActivationBinding

    @Inject lateinit var licenseManager: LicenseManager
    @Inject lateinit var billingManager: com.myvideolibrary.app.security.BillingManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityActivationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.deviceId.text = licenseManager.deviceIdPretty()

        // If we're here because the trial/subscription ended, reassure the user
        // their downloads are untouched — they only need to renew to continue.
        if (licenseManager.state() == LicenseManager.State.EXPIRED) {
            showMessage(getString(R.string.activation_expired_keep), success = false)
        }

        // Diagnostics line: which build + which activation schemes it accepts.
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "?"
        }
        binding.versionText.text = "v$version · ${licenseManager.acceptedSchemes()}"

        binding.copyButton.setOnClickListener {
            val clip = ContextCompat.getSystemService(this, ClipboardManager::class.java)
            clip?.setPrimaryClip(ClipData.newPlainText("device", licenseManager.deviceId()))
            showMessage(getString(R.string.activation_copied), success = true)
        }

        binding.activateButton.setOnClickListener { attemptActivation() }
        binding.ownerRecovery.setOnClickListener { promptOwnerRecovery() }
        binding.subscribeButton.setOnClickListener { billingManager.launchSubscribe(this) }

        // Auto-proceed the moment a Play subscription becomes active — no code needed.
        billingManager.refresh()
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                billingManager.entitled.collect { active -> if (active) proceed() }
            }
        }
    }

    private fun attemptActivation() {
        val code = binding.codeInput.text?.toString().orEmpty()
        if (licenseManager.tryActivate(code)) {
            proceed()
        } else {
            showMessage(getString(R.string.activation_invalid), success = false)
        }
    }

    private fun promptOwnerRecovery() {
        val input = EditText(this).apply {
            hint = getString(R.string.activation_seed_hint)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.activation_owner_recovery)
            .setView(input)
            .setPositiveButton(R.string.activation_activate) { _, _ ->
                if (licenseManager.recoverWithSeed(input.text.toString())) {
                    proceed()
                } else {
                    showMessage(getString(R.string.activation_seed_invalid), success = false)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showMessage(text: String, success: Boolean) {
        binding.messageText.isVisible = true
        binding.messageText.text = text
        binding.messageText.setTextColor(
            ContextCompat.getColor(
                this,
                if (success) android.R.color.holo_green_dark else android.R.color.holo_red_dark
            )
        )
    }

    private fun proceed() {
        startActivity(Intent(this, LockActivity::class.java))
        finish()
    }

    override fun onBackPressed() {
        // The gate can't be dismissed; leaving the app is the only exit.
        finishAffinity()
    }
}
