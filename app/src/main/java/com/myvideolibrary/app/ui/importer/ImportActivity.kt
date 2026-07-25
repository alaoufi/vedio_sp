package com.myvideolibrary.app.ui.importer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.myvideolibrary.app.R
import com.myvideolibrary.app.databinding.ActivityImportBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ImportActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImportBinding
    private val viewModel: ImportViewModel by viewModels()
    private lateinit var adapter: ImportAdapter

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.scan() else showPermissionDenied()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImportBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = ImportAdapter(
            onToggle = viewModel::toggle,
            onPlay = { video ->
                startActivity(
                    com.myvideolibrary.app.ui.player.PlayerActivity.streamIntent(
                        this, video.contentUri, video.displayName
                    )
                )
            }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.selectAll.setOnClickListener { viewModel.selectAll() }
        binding.importButton.setOnClickListener { viewModel.importSelected() }

        observe()
        ensurePermissionAndScan()
    }

    private fun requiredPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    private fun ensurePermissionAndScan() {
        val perm = requiredPermission()
        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) {
            viewModel.scan()
        } else {
            permissionLauncher.launch(perm)
        }
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collectLatest { state ->
                    binding.progressBar.isVisible = state.loading || state.importing
                    binding.emptyState.isVisible =
                        !state.loading && state.items.isEmpty() && state.error == null
                    binding.errorState.isVisible = state.error != null
                    binding.errorState.text = state.error

                    adapter.submitList(
                        state.items.map { ImportAdapter.Row(it, it.contentUri in state.selected) }
                    )

                    val count = state.selected.size
                    binding.importButton.isEnabled = count > 0 && !state.importing
                    binding.importButton.text = getString(R.string.import_selected_count, count)

                    if (state.done) {
                        Toast.makeText(
                            this@ImportActivity,
                            getString(R.string.import_done, state.importedCount),
                            Toast.LENGTH_LONG
                        ).show()
                        finish()
                    }
                }
            }
        }
    }

    private fun showPermissionDenied() {
        binding.errorState.isVisible = true
        binding.errorState.text = getString(R.string.permission_denied_media)
    }
}
