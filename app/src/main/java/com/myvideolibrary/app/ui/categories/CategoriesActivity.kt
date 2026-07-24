package com.myvideolibrary.app.ui.categories

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.myvideolibrary.app.R
import com.myvideolibrary.app.databinding.ActivityCategoriesBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class CategoriesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCategoriesBinding
    private val viewModel: CategoriesViewModel by viewModels()
    private lateinit var adapter: CategoriesAdapter
    private lateinit var touchHelper: ItemTouchHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCategoriesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = CategoriesAdapter(
            onOpen = ::openCategory,
            onRename = ::promptRename,
            onDelete = ::confirmDelete,
            onToggleVisibility = { viewModel.setHidden(it.name, !it.hidden) },
            onTogglePassword = ::togglePassword,
            onStartDrag = { touchHelper.startDrag(it) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.addButton.setOnClickListener { promptAdd() }

        setupDragReorder()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.categories.collectLatest { list ->
                    adapter.submit(list)
                    binding.emptyText.isVisible = list.isEmpty()
                }
            }
        }
    }

    private fun setupDragReorder() {
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                rv: RecyclerView,
                vh: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                adapter.onItemMove(vh.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}

            override fun isLongPressDragEnabled(): Boolean = false

            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                // Persist the new order once the drag gesture completes.
                viewModel.saveOrder(adapter.currentOrder())
            }
        }
        touchHelper = ItemTouchHelper(callback)
        touchHelper.attachToRecyclerView(binding.recyclerView)
    }

    private fun promptAdd() {
        val input = EditText(this).apply { hint = getString(R.string.category_hint) }
        AlertDialog.Builder(this)
            .setTitle(R.string.add_category)
            .setView(input)
            .setPositiveButton(R.string.create) { _, _ -> viewModel.add(input.text.toString()) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun promptRename(name: String) {
        val input = EditText(this).apply { setText(name); setSelection(name.length) }
        AlertDialog.Builder(this)
            .setTitle(R.string.rename)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                viewModel.rename(name, input.text.toString())
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(name: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete)
            .setMessage(getString(R.string.delete_category_message, name))
            .setPositiveButton(R.string.delete) { _, _ -> viewModel.delete(name) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Sets a new password, or (when one exists) removes it after verifying. */
    private fun togglePassword(item: CategoryItem) {
        if (item.hasPassword) {
            promptPassword(R.string.remove_password_title, R.string.enter_current_password) { entered ->
                lifecycleScope.launch {
                    if (viewModel.verifyPassword(item.name, entered)) {
                        viewModel.setPassword(item.name, null)
                        toast(R.string.password_removed)
                    } else {
                        toast(R.string.wrong_password)
                    }
                }
            }
        } else {
            promptPassword(R.string.set_password_title, R.string.enter_new_password) { entered ->
                if (entered.isBlank()) return@promptPassword
                viewModel.setPassword(item.name, entered)
                toast(R.string.password_set)
            }
        }
    }

    /** Opens a category's contents; password-protected ones ask for the password. */
    private fun openCategory(item: CategoryItem) {
        if (item.hasPassword) {
            promptPassword(R.string.open_locked_title, R.string.enter_password) { entered ->
                lifecycleScope.launch {
                    if (viewModel.verifyPassword(item.name, entered)) {
                        returnOpen(item.name)
                    } else {
                        toast(R.string.wrong_password)
                    }
                }
            }
        } else {
            returnOpen(item.name)
        }
    }

    private fun returnOpen(name: String) {
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_OPEN_CATEGORY, name))
        finish()
    }

    private fun promptPassword(titleRes: Int, hintRes: Int, onEntered: (String) -> Unit) {
        val input = EditText(this).apply {
            hint = getString(hintRes)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ -> onEntered(input.text.toString()) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_OPEN_CATEGORY = "open_category"

        fun intent(context: Context) = Intent(context, CategoriesActivity::class.java)
    }
}
