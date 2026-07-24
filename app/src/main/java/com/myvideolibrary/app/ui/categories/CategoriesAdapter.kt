package com.myvideolibrary.app.ui.categories

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.myvideolibrary.app.R
import com.myvideolibrary.app.databinding.ItemCategoryBinding
import java.util.Collections

/**
 * Mutable-list adapter for managing categories: open, rename, delete, toggle
 * visibility, set a password, and drag-to-reorder. The list is mutated in place
 * during a drag and persisted when the gesture ends.
 */
class CategoriesAdapter(
    private val onOpen: (CategoryItem) -> Unit,
    private val onRename: (String) -> Unit,
    private val onDelete: (String) -> Unit,
    private val onToggleVisibility: (CategoryItem) -> Unit,
    private val onTogglePassword: (CategoryItem) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit
) : RecyclerView.Adapter<CategoriesAdapter.VH>() {

    private val items = mutableListOf<CategoryItem>()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(newItems: List<CategoryItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    /** The current order as plain names, for persistence. */
    fun currentOrder(): List<String> = items.map { it.name }

    fun onItemMove(from: Int, to: Int) {
        if (from < to) {
            for (i in from until to) Collections.swap(items, i, i + 1)
        } else {
            for (i in from downTo to + 1) Collections.swap(items, i, i - 1)
        }
        notifyItemMoved(from, to)
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemCategoryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(private val binding: ItemCategoryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        @SuppressLint("ClickableViewAccessibility")
        fun bind(item: CategoryItem) {
            val ctx = binding.root.context
            binding.categoryName.text = item.name

            // Secondary line summarising hidden / locked state.
            val states = listOfNotNull(
                ctx.getString(R.string.state_hidden).takeIf { item.hidden },
                ctx.getString(R.string.state_locked).takeIf { item.hasPassword }
            )
            binding.categoryState.isVisible = states.isNotEmpty()
            binding.categoryState.text = states.joinToString(" · ")

            binding.visibilityButton.setImageResource(
                if (item.hidden) R.drawable.ic_visibility_off else R.drawable.ic_visibility
            )
            binding.lockButton.setImageResource(
                if (item.hasPassword) R.drawable.ic_lock else R.drawable.ic_lock_open
            )

            binding.openArea.setOnClickListener { onOpen(item) }
            binding.visibilityButton.setOnClickListener { onToggleVisibility(item) }
            binding.lockButton.setOnClickListener { onTogglePassword(item) }
            binding.editButton.setOnClickListener { onRename(item.name) }
            binding.deleteButton.setOnClickListener { onDelete(item.name) }
            binding.dragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) onStartDrag(this)
                false
            }
        }
    }
}
