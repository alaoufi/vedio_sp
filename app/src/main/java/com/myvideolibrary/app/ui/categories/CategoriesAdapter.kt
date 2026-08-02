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
    private val onEdit: (CategoryItem) -> Unit,
    private val onDelete: (CategoryItem) -> Unit,
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

            // Secondary line: a protected section shows how it behaves.
            val stateLabel = when (item.mode) {
                com.myvideolibrary.app.util.CategoryProtectionMode.HIDDEN -> R.string.state_hidden
                com.myvideolibrary.app.util.CategoryProtectionMode.OBSCURED -> R.string.state_blurred
                com.myvideolibrary.app.util.CategoryProtectionMode.VISIBLE -> R.string.state_locked
                null -> null
            }
            binding.categoryState.isVisible = stateLabel != null
            if (stateLabel != null) binding.categoryState.text = ctx.getString(stateLabel)

            binding.openArea.setOnClickListener { onOpen(item) }
            binding.editButton.setOnClickListener { onEdit(item) }
            binding.deleteButton.setOnClickListener { onDelete(item) }
            binding.dragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) onStartDrag(this)
                false
            }
        }
    }
}
