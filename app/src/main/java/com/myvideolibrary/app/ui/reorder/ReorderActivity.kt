package com.myvideolibrary.app.ui.reorder

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.myvideolibrary.app.databinding.ActivityReorderBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Drag-to-arrange screen: reorder clips by dragging, persisted as the library's
 * custom order. Each drag gesture is saved when it ends, so the arrangement
 * survives even if the app is closed abruptly.
 */
@AndroidEntryPoint
class ReorderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReorderBinding
    private val viewModel: ReorderViewModel by viewModels()
    private lateinit var adapter: ReorderAdapter
    private lateinit var touchHelper: ItemTouchHelper

    /** True once a drag actually moved something, so we only persist real changes. */
    private var dirty = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReorderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = ReorderAdapter(onStartDrag = { holder -> touchHelper.startDrag(holder) })
        binding.recyclerView.adapter = adapter

        touchHelper = ItemTouchHelper(dragCallback())
        touchHelper.attachToRecyclerView(binding.recyclerView)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collectLatest { render(it) }
            }
        }
    }

    private fun render(state: ReorderViewModel.State) {
        binding.progress.isVisible = state.loading
        val hasClips = state.clips.isNotEmpty()
        binding.recyclerView.isVisible = hasClips
        binding.emptyState.isVisible = !state.loading && !hasClips
        // Only seed the adapter once (from the initial load); drags own the order after.
        if (hasClips && adapter.itemCount == 0) adapter.submit(state.clips)
    }

    private fun dragCallback() = object : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
    ) {
        override fun isLongPressDragEnabled() = false // drag starts from the handle only

        override fun onMove(
            rv: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            val from = viewHolder.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
            adapter.onItemMove(from, to)
            dirty = true
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

        override fun clearView(rv: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(rv, viewHolder)
            // Persist the arrangement once the gesture settles.
            if (dirty) {
                dirty = false
                viewModel.save(adapter.currentOrder())
            }
        }
    }

    companion object {
        fun intent(context: Context) = Intent(context, ReorderActivity::class.java)
    }
}
