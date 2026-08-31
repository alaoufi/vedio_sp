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
import com.myvideolibrary.app.security.LicenseManager
import com.myvideolibrary.app.util.Formatters
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val viewModel: SettingsViewModel by viewModels()

    @javax.inject.Inject lateinit var licenseManager: com.myvideolibrary.app.security.LicenseManager
    @javax.inject.Inject lateinit var okHttpClient: okhttp3.OkHttpClient
    @javax.inject.Inject lateinit var autoBackupManager: com.myvideolibrary.app.data.backup.AutoBackupManager
    @javax.inject.Inject lateinit var recoveryManager: com.myvideolibrary.app.data.repository.RecoveryManager

    private val restorePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { confirmRestore(it) } }

    /** Picks the folder to scan when recovering clips (e.g. the user's vedio_sp folder). */
    private val recoveryFolderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            runRecovery(uri.toString())
        }
    }

    /** Picks the external folder that automatic backups are written to. */
    private val backupFolderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            promptEnableAutoBackup(uri)
        }
    }

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            // Persist access across reboots so downloads can be copied there later.
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            viewModel.setSaveLocation(uri.toString())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.versionValue.text = com.myvideolibrary.app.BuildConfig.VERSION_NAME
        binding.licenseValue.text = licenseStatusText()

        bindActions()
        observe()

        // Deep link from the home-screen backup reminder: open straight into the
        // auto-backup setup so enabling it is a single tap away.
        if (intent?.getBooleanExtra(EXTRA_OPEN_AUTO_BACKUP, false) == true) {
            binding.root.post { showAutoBackupDialog() }
        }
    }

    private fun bindActions() {
        setupSection(binding.headerAppearance, binding.bodyAppearance, binding.chevAppearance)
        setupSection(binding.headerDownloads, binding.bodyDownloads, binding.chevDownloads)
        setupSection(binding.headerSecurity, binding.bodySecurity, binding.chevSecurity)
        setupSection(binding.headerStorage, binding.bodyStorage, binding.chevStorage)
        setupSection(binding.headerAbout, binding.bodyAbout, binding.chevAbout)

        binding.themeRow.setOnClickListener { showThemeDialog() }
        binding.languageRow.setOnClickListener { showLanguageDialog() }
        binding.endActionRow.setOnClickListener { showEndActionDialog() }
        binding.volumeBoostRow.setOnClickListener { showVolumeBoostDialog() }
        renderVolumeBoost()
        binding.languageValue.text = currentLanguageLabel()
        binding.guideRow.setOnClickListener {
            startActivity(android.content.Intent(this, com.myvideolibrary.app.ui.help.HelpActivity::class.java))
        }
        binding.checkUpdateRow.setOnClickListener { checkForUpdates() }
        binding.crashLogRow.setOnClickListener { showCrashLog() }
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

        binding.saveLocationRow.setOnClickListener { showSaveLocationDialog() }
        binding.clearCacheRow.setOnClickListener { viewModel.clearCache() }
        binding.backupRow.setOnClickListener { viewModel.export() }
        binding.restoreRow.setOnClickListener {
            restorePicker.launch(arrayOf("*/*"))
        }
        binding.autoBackupRow.setOnClickListener { showAutoBackupDialog() }
        binding.recoverRow.setOnClickListener { confirmRecoverFromStorage() }
        renderAutoBackup()
    }

    /** Rebuilds the library from media files still on disk (e.g. after a DB reset). */
    private fun confirmRecoverFromStorage() {
        AlertDialog.Builder(this)
            .setTitle(R.string.recover_storage)
            .setMessage(R.string.recover_storage_confirm)
            .setPositiveButton(R.string.recover_storage_pick_folder) { _, _ ->
                runCatching { recoveryFolderPicker.launch(null) }
                    .onFailure { runRecovery(null) }
            }
            .setNeutralButton(R.string.recover_storage_app_only) { _, _ -> runRecovery(null) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun runRecovery(folderUri: String?) {
        Toast.makeText(this, R.string.recover_storage_running, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = runCatching { recoveryManager.recoverFromStorage(folderUri) }.getOrNull()
            val msg = if (result == null) {
                getString(R.string.recover_storage_failed)
            } else {
                getString(
                    R.string.recover_storage_summary,
                    result.recovered, result.removedBroken, result.filesScanned,
                    result.alreadyPresent, result.libraryCount
                )
            }
            // Show the result as a Toast, not a dialog: the scan can outlast the
            // screen being foregrounded, and adding a dialog window to a stopped
            // activity throws BadTokenException.
            Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_LONG).show()
        }
    }

    // ---- Global default volume boost + effect ----

    private fun renderVolumeBoost() {
        val boost = com.myvideolibrary.app.util.AudioPrefs.boost(this)
        val effect = com.myvideolibrary.app.util.AudioPrefs.effect(this)
        val effectLabel = getString(effectLabelRes(effect))
        binding.volumeBoostValue.text = if (boost == 100 &&
            effect == com.myvideolibrary.app.util.PlayerAudioEffects.Preset.NONE) {
            getString(R.string.volume_boost_off)
        } else {
            getString(R.string.volume_boost_summary, boost, effectLabel)
        }
    }

    private fun effectLabelRes(preset: com.myvideolibrary.app.util.PlayerAudioEffects.Preset): Int =
        when (preset) {
            com.myvideolibrary.app.util.PlayerAudioEffects.Preset.NONE -> R.string.effect_none
            com.myvideolibrary.app.util.PlayerAudioEffects.Preset.BASS -> R.string.effect_bass
            com.myvideolibrary.app.util.PlayerAudioEffects.Preset.SURROUND -> R.string.effect_surround
            com.myvideolibrary.app.util.PlayerAudioEffects.Preset.HALL -> R.string.effect_hall
            com.myvideolibrary.app.util.PlayerAudioEffects.Preset.CONCERT -> R.string.effect_concert
        }

    /** A slider + effect picker that saves the GLOBAL default applied to every clip. */
    private fun showVolumeBoostDialog() {
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }

        var boost = com.myvideolibrary.app.util.AudioPrefs.boost(this)
        var effect = com.myvideolibrary.app.util.AudioPrefs.effect(this)

        val valueLabel = android.widget.TextView(this).apply {
            text = getString(R.string.volume_boost_value, boost)
            textSize = 16f
        }
        container.addView(valueLabel)

        val slider = android.widget.SeekBar(this).apply {
            max = 400
            progress = (boost - 100).coerceIn(0, 400)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar, value: Int, fromUser: Boolean) {
                    boost = 100 + (value / 10) * 10
                    valueLabel.text = getString(R.string.volume_boost_value, boost)
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar) {}
            })
        }
        container.addView(slider)

        container.addView(android.widget.TextView(this).apply {
            text = getString(R.string.audio_effects)
            setPadding(0, pad / 2, 0, 0)
            textSize = 16f
        })

        val presets = com.myvideolibrary.app.util.PlayerAudioEffects.Preset.entries
        val group = android.widget.RadioGroup(this)
        presets.forEachIndexed { index, preset ->
            group.addView(
                com.google.android.material.radiobutton.MaterialRadioButton(this).apply {
                    id = index
                    text = getString(effectLabelRes(preset))
                    isChecked = preset == effect
                }
            )
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            presets.getOrNull(checkedId)?.let { effect = it }
        }
        container.addView(group)

        AlertDialog.Builder(this)
            .setTitle(R.string.volume_boost)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                com.myvideolibrary.app.util.AudioPrefs.setBoost(this, boost)
                com.myvideolibrary.app.util.AudioPrefs.setEffect(this, effect)
                renderVolumeBoost()
                Toast.makeText(this, R.string.volume_boost_saved, Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(R.string.reset) { _, _ ->
                com.myvideolibrary.app.util.AudioPrefs.setBoost(this, 100)
                com.myvideolibrary.app.util.AudioPrefs.setEffect(
                    this, com.myvideolibrary.app.util.PlayerAudioEffects.Preset.NONE
                )
                renderVolumeBoost()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---- Automatic external backup ----

    /** Updates the auto-backup row's subtitle with its current state. */
    private fun renderAutoBackup() {
        binding.autoBackupValue.text = if (!autoBackupManager.isEnabled) {
            getString(R.string.auto_backup_off)
        } else {
            val folder = autoBackupManager.folderUri
                ?.let { runCatching { folderLabel(it) }.getOrNull() } ?: "—"
            val last = autoBackupManager.lastBackupAt
                .takeIf { it > 0 }?.let { Formatters.dateTime(it) }
                ?: getString(R.string.auto_backup_never)
            getString(R.string.auto_backup_on_format, folder, last)
        }
    }

    private fun showAutoBackupDialog() {
        if (!autoBackupManager.isEnabled) {
            AlertDialog.Builder(this)
                .setTitle(R.string.auto_backup)
                .setMessage(R.string.auto_backup_intro)
                .setPositiveButton(R.string.auto_backup_choose_folder) { _, _ ->
                    runCatching { backupFolderPicker.launch(null) }
                        .onFailure { Toast.makeText(this, R.string.error_unknown, Toast.LENGTH_SHORT).show() }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        val actions = arrayOf(
            getString(R.string.auto_backup_now),
            getString(R.string.auto_backup_change_folder),
            getString(R.string.auto_backup_disable)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.auto_backup)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> runBackupNow()
                    1 -> runCatching { backupFolderPicker.launch(null) }
                    2 -> {
                        autoBackupManager.disable()
                        renderAutoBackup()
                        Toast.makeText(this, R.string.auto_backup_disabled, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    /** After a folder is chosen, turn on automatic backups (no password needed). */
    private fun promptEnableAutoBackup(treeUri: Uri) {
        autoBackupManager.enable(treeUri)
        renderAutoBackup()
        runBackupNow()
    }

    private fun runBackupNow() {
        Toast.makeText(this, R.string.auto_backup_running, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = autoBackupManager.backupNow()
            renderAutoBackup()
            val msg = if (result.isSuccess) getString(R.string.auto_backup_done)
            else getString(R.string.auto_backup_failed, autoBackupManager.lastResult ?: "")
            Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_LONG).show()
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
        binding.endActionValue.text = getString(endActionLabel(state.endOfClipAction))
        binding.appLockSwitch.isChecked = state.appLockEnabled && state.hasPin
        binding.changePinRow.isEnabled = state.hasPin
        binding.biometricSwitch.isChecked = state.biometricEnabled
        binding.screenshotsSwitch.isChecked = state.preventScreenshots
        binding.recentsSwitch.isChecked = state.hideInRecents
        binding.storageValue.text = Formatters.fileSize(state.storageUsed)
        binding.saveLocationValue.text = state.saveLocation
            ?.let { runCatching { folderLabel(Uri.parse(it)) }.getOrNull() }
            ?: getString(R.string.save_location_default)

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

    private fun showSaveLocationDialog() {
        val hasCustom = viewModel.state.value.saveLocation != null
        val options = if (hasCustom) {
            arrayOf(getString(R.string.choose_folder), getString(R.string.save_location_default))
        } else {
            arrayOf(getString(R.string.choose_folder))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.save_location)
            .setItems(options) { _, which ->
                if (which == 0) runCatching { folderPicker.launch(null) }
                    .onFailure { Toast.makeText(this, R.string.error_unknown, Toast.LENGTH_SHORT).show() }
                else viewModel.setSaveLocation(null)
            }
            .show()
    }

    /** Human-readable name of the chosen SAF tree, falling back to the last path segment. */
    private fun folderLabel(uri: Uri): String {
        val doc = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, uri)
        return doc?.name ?: uri.lastPathSegment ?: uri.toString()
    }

    /** Human-readable trial / subscription state for the About section. */
    /** Shows the last recorded crash (if any), with options to share or clear it. */
    private fun showCrashLog() {
        val log = com.myvideolibrary.app.util.CrashLogger.lastCrash(this)
        if (log == null) {
            Toast.makeText(this, R.string.no_crash_log, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.crash_log)
            .setMessage(log.take(4000))
            .setPositiveButton(R.string.action_share) { _, _ ->
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_SUBJECT, "Crash log")
                    putExtra(android.content.Intent.EXTRA_TEXT, log)
                }
                startActivity(android.content.Intent.createChooser(send, getString(R.string.action_share)))
            }
            .setNeutralButton(R.string.clear) { _, _ ->
                com.myvideolibrary.app.util.CrashLogger.clear(this)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Makes a settings section collapsible: tap the coloured header to toggle. */
    private fun setupSection(
        header: android.view.View,
        body: android.view.View,
        chevron: android.view.View
    ) {
        chevron.rotation = if (body.visibility == android.view.View.VISIBLE) 180f else 0f
        header.setOnClickListener {
            val show = body.visibility != android.view.View.VISIBLE
            body.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
            chevron.animate().rotation(if (show) 180f else 0f).setDuration(180).start()
        }
    }

    /**
     * Manual update check: asks GitHub Releases for the newest build and either
     * offers to download it or reports that the app is already up to date.
     */
    private fun checkForUpdates() {
        binding.checkUpdateValue.setText(R.string.checking_updates)
        lifecycleScope.launch {
            val outcome = com.myvideolibrary.app.util.UpdateChecker.checkOutcome(
                com.myvideolibrary.app.BuildConfig.VERSION_CODE, okHttpClient
            )
            when (outcome) {
                is com.myvideolibrary.app.util.UpdateChecker.Outcome.UpToDate -> {
                    binding.checkUpdateValue.setText(R.string.up_to_date)
                    Toast.makeText(this@SettingsActivity, R.string.up_to_date, Toast.LENGTH_SHORT).show()
                }
                is com.myvideolibrary.app.util.UpdateChecker.Outcome.Failed -> {
                    // A failed check must NOT read as "up to date" — say so and let the user retry.
                    binding.checkUpdateValue.setText(R.string.update_check_failed)
                    Toast.makeText(
                        this@SettingsActivity, R.string.update_check_failed, Toast.LENGTH_LONG
                    ).show()
                }
                is com.myvideolibrary.app.util.UpdateChecker.Outcome.Available -> {
                    val result = outcome.result
                    binding.checkUpdateValue.text =
                        getString(R.string.update_available_message, result.latestVersion)
                    AlertDialog.Builder(this@SettingsActivity)
                        .setTitle(R.string.update_available_title)
                        .setMessage(getString(R.string.update_available_message, result.latestVersion))
                        .setPositiveButton(R.string.update_download) { _, _ ->
                            runCatching {
                                startActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        Uri.parse(com.myvideolibrary.app.util.UpdateChecker.APK_URL)
                                    )
                                )
                            }
                        }
                        .setNegativeButton(R.string.later, null)
                        .show()
                }
            }
        }
    }

    private fun licenseStatusText(): String {
        val days = licenseManager.daysLeft()
        return when {
            licenseManager.state() != LicenseManager.State.ACTIVE -> getString(R.string.license_inactive)
            days == null -> getString(R.string.license_permanent)
            licenseManager.isTrial() -> getString(R.string.license_trial_days, days)
            else -> getString(R.string.license_sub_days, days)
        }
    }

    // ---- Language ----

    /** Native display name of the currently applied app language. */
    private fun currentLanguageLabel(): String {
        val names = resources.getStringArray(R.array.language_names)
        val tags = resources.getStringArray(R.array.language_tags)
        val applied = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
        if (applied.isEmpty) return names[0]
        val tag = applied.toLanguageTags()
        val idx = tags.indexOfFirst { it.isNotEmpty() && tag.startsWith(it) }
        return if (idx >= 0) names[idx] else names[0]
    }

    private fun showLanguageDialog() {
        val names = resources.getStringArray(R.array.language_names)
        val tags = resources.getStringArray(R.array.language_tags)
        val applied = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
        val appliedTag = applied.toLanguageTags()
        val current = if (applied.isEmpty) 0
        else tags.indexOfFirst { it.isNotEmpty() && appliedTag.startsWith(it) }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.language)
            .setSingleChoiceItems(names, current) { dialog, which ->
                val tag = tags[which]
                val locales = if (tag.isEmpty()) {
                    androidx.core.os.LocaleListCompat.getEmptyLocaleList()
                } else {
                    androidx.core.os.LocaleListCompat.forLanguageTags(tag)
                }
                // AppCompat recreates the activity to apply the new locale.
                androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(locales)
                dialog.dismiss()
            }
            .show()
    }

    private fun endActionLabel(action: com.myvideolibrary.app.data.model.EndOfClipAction): Int =
        when (action) {
            com.myvideolibrary.app.data.model.EndOfClipAction.STOP -> R.string.end_stop
            com.myvideolibrary.app.data.model.EndOfClipAction.REPEAT -> R.string.end_repeat
            com.myvideolibrary.app.data.model.EndOfClipAction.NEXT -> R.string.end_next
        }

    private fun showEndActionDialog() {
        val actions = com.myvideolibrary.app.data.model.EndOfClipAction.entries.toTypedArray()
        val labels = actions.map { getString(endActionLabel(it)) }.toTypedArray()
        val current = actions.indexOf(viewModel.state.value.endOfClipAction).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.end_of_clip)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                viewModel.setEndOfClipAction(actions[which])
                dialog.dismiss()
            }
            .show()
    }

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

    /** Confirms then restores a chosen backup file (no password required). */
    private fun confirmRestore(uri: Uri) {
        AlertDialog.Builder(this)
            .setTitle(R.string.restore_title)
            .setMessage(R.string.restore_message)
            .setPositiveButton(R.string.restore) { _, _ -> viewModel.restore(uri) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        /** When true, Settings opens straight into the auto-backup setup dialog. */
        const val EXTRA_OPEN_AUTO_BACKUP = "open_auto_backup"

        /** Intent that opens Settings directly on the auto-backup setup. */
        fun autoBackupIntent(context: android.content.Context) =
            android.content.Intent(context, SettingsActivity::class.java)
                .putExtra(EXTRA_OPEN_AUTO_BACKUP, true)
    }
}
