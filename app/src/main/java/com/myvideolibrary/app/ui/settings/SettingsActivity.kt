package com.myvideolibrary.app.ui.settings

import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.myvideolibrary.app.R
import com.myvideolibrary.app.data.model.AppTheme
import com.myvideolibrary.app.databinding.ActivitySettingsBinding
import com.myvideolibrary.app.util.Formatters
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val viewModel: SettingsViewModel by viewModels()

    private val restorePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { promptRestorePassword(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.versionValue.text = com.myvideolibrary.app.BuildConfig.VERSION_NAME

        bindActions()
        observe()
    }

    private fun bindActions() {
        binding.themeRow.setOnClickListener { showThemeDialog() }
        binding.wifiOnlySwitch.setOnClickListener {
            viewModel.setWifiOnly(binding.wifiOnlySwitch.isChecked)
        }
        binding.maxConcurrentRow.setOnClickListener { showMaxConcurrentDialog() }

        binding.appLockSwitch.setOnClickListener {
            if (binding.appLockSwitch.isChecked) showSetPinDialog() else viewModel.disableLock()
        }
        binding.changePinRow.setOnClickListener { showSetPinDialog() }
        binding.biometricSwitch.setOnClickListener {
            viewModel.setBiometric(binding.biometricSwitch.isChecked)
        }
        binding.screenshotsSwitch.setOnClickListener {
            viewModel.setPreventScreenshots(binding.screenshotsSwitch.isChecked)
        }
        binding.recentsSwitch.setOnClickListener {
            viewModel.setHideInRecents(binding.recentsSwitch.isChecked)
        }

        binding.clearCacheRow.setOnClickListener { viewModel.clearCache() }
        binding.backupRow.setOnClickListener { promptBackupPassword() }
        binding.restoreRow.setOnClickListener {
            restorePicker.launch(arrayOf("*/*"))
        }
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collectLatest { render(it) }
            }
        }
    }

    private fun render(state: SettingsUiState) {
        binding.themeValue.text = when (state.theme) {
            AppTheme.SYSTEM -> getString(R.string.theme_system)
            AppTheme.LIGHT -> getString(R.string.theme_light)
            AppTheme.DARK -> getString(R.string.theme_dark)
        }
        binding.wifiOnlySwitch.isChecked = state.wifiOnly
        binding.maxConcurrentValue.text = state.maxConcurrent.toString()
        binding.appLockSwitch.isChecked = state.appLockEnabled && state.hasPin
        binding.changePinRow.isEnabled = state.hasPin
        binding.biometricSwitch.isChecked = state.biometricEnabled
        binding.screenshotsSwitch.isChecked = state.preventScreenshots
        binding.recentsSwitch.isChecked = state.hideInRecents
        binding.storageValue.text = Formatters.fileSize(state.storageUsed)

        state.message?.let {
            Toast.makeText(this, it, Toast.LENGTH_LONG).show()
            viewModel.consumeMessage()
        }

        state.exportedFile?.let { file ->
            shareBackup(file)
            viewModel.consumeExportedFile()
        }
    }

    /** Lets the user save/send the encrypted backup outside app-private storage. */
    private fun shareBackup(file: java.io.File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this, "$packageName.fileprovider", file
        )
        val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(android.content.Intent.createChooser(share, getString(R.string.backup)))
    }

    // ---- Dialogs ----

    private fun showThemeDialog() {
        val labels = arrayOf(
            getString(R.string.theme_system),
            getString(R.string.theme_light),
            getString(R.string.theme_dark)
        )
        val values = arrayOf(AppTheme.SYSTEM, AppTheme.LIGHT, AppTheme.DARK)
        val current = values.indexOf(viewModel.state.value.theme).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.theme)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                viewModel.setTheme(values[which])
                dialog.dismiss()
            }
            .show()
    }

    private fun showMaxConcurrentDialog() {
        val values = arrayOf("1", "2", "3", "4", "5")
        AlertDialog.Builder(this)
            .setTitle(R.string.max_concurrent)
            .setItems(values) { _, which -> viewModel.setMaxConcurrent(which + 1) }
            .show()
    }

    private fun showSetPinDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = getString(R.string.pin_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.set_pin)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val pin = input.text.toString()
                if (pin.length in 4..12) {
                    viewModel.setPin(pin)
                } else {
                    Toast.makeText(this, R.string.pin_length_error, Toast.LENGTH_SHORT).show()
                    viewModel.refresh()
                }
            }
            .setNegativeButton(R.string.cancel) { _, _ -> viewModel.refresh() }
            .setOnCancelListener { viewModel.refresh() }
            .show()
    }

    private fun promptBackupPassword() {
        val input = passwordField()
        AlertDialog.Builder(this)
            .setTitle(R.string.backup_encrypt_title)
            .setMessage(R.string.backup_encrypt_message)
            .setView(input)
            .setPositiveButton(R.string.export) { _, _ ->
                val pw = input.text.toString()
                if (pw.length >= 6) viewModel.export(pw)
                else Toast.makeText(this, R.string.password_too_short, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun promptRestorePassword(uri: Uri) {
        val input = passwordField()
        AlertDialog.Builder(this)
            .setTitle(R.string.restore_title)
            .setMessage(R.string.restore_message)
            .setView(input)
            .setPositiveButton(R.string.restore) { _, _ ->
                viewModel.restore(uri, input.text.toString())
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun passwordField(): EditText = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        hint = getString(R.string.backup_password_hint)
    }
}
