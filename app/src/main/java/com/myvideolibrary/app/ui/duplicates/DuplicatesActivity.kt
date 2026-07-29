package com.myvideolibrary.app.ui.duplicates

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.myvideolibrary.app.R
import com.myvideolibrary.app.databinding.ActivityDuplicatesBinding
import com.myvideolibrary.app.util.Formatters
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Lists groups of duplicate videos (same content fingerprint) and lets the user
 * reclaim space by removing the extra copies, keeping the oldest of each group.
 */
@AndroidEntryPoint
class DuplicatesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDuplicatesBinding
    private val viewModel: DuplicatesViewModel by viewModels()
    private lateinit var adapter: DuplicatesAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDuplicatesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = DuplicatesAdapter(onRemove = ::confirmRemoveGroup)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.removeAllButton.setOnClickListener { confirmRemoveAll() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collectLatest { render(it) }
            }
        }
    }

    private fun render(state: DuplicatesViewModel.State) {
        binding.progress.isVisible = state.loading
        val hasGroups = state.groups.isNotEmpty()
        binding.recyclerView.isVisible = hasGroups
        binding.emptyState.isVisible = !state.loading && !hasGroups
        binding.removeAllButton.isVisible = hasGroups
        binding.summary.isVisible = hasGroups
        if (hasGroups) {
            binding.summary.text = getString(
                R.string.dup_summary,
                state.groups.size,
                state.extrasCount,
                Formatters.fileSize(state.totalReclaimable)
            )
        }
        adapter.submitList(state.groups)
    }

    private fun confirmRemoveGroup(group: DuplicatesViewModel.Group) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dup_clean)
            .setMessage(
                getString(
                    R.string.dup_confirm_group,
                    group.extras.size,
                    Formatters.fileSize(group.reclaimable)
                )
            )
            .setPositiveButton(R.string.delete) { _, _ -> viewModel.removeExtras(group) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmRemoveAll() {
        val state = viewModel.state.value
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dup_clean_all)
            .setMessage(
                getString(
                    R.string.dup_confirm_all,
                    state.extrasCount,
                    Formatters.fileSize(state.totalReclaimable)
                )
            )
            .setPositiveButton(R.string.delete) { _, _ -> viewModel.removeAllExtras() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        fun intent(context: Context) = Intent(context, DuplicatesActivity::class.java)
    }
}
