package com.myvideolibrary.app.ui.categories

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.View
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
import com.myvideolibrary.app.databinding.DialogEditCategoryBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class CategoriesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCategoriesBinding
    private val viewModel: CategoriesViewModel by viewModels()
    private lateinit var adapter: CategoriesAdapter
    private lateinit var touchHelper: ItemTouchHelper
    private var unlocked = false

    /** True once the general (master) password was entered — it opens everything. */
    private var masterUnlocked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCategoriesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = CategoriesAdapter(
            onOpen = { item -> withSectionAccess(item) { returnOpen(item.name) } },
            onEdit = { item -> withSectionAccess(item) { showEditDialog(item) } },
            onDelete = { item -> withSectionAccess(item) { confirmDelete(item.name) } },
            onStartDrag = { touchHelper.startDrag(it) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.addButton.setOnClickListener { promptAdd() }

        setupDragReorder()

        // Gate the whole screen behind its password, if one is set.
        showContent(false)
        lifecycleScope.launch {
            if (viewModel.hasManagePassword()) unlockThenShow() else reveal()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_MANAGE_PW, 0, getString(R.string.manage_password)).apply {
            setIcon(R.drawable.ic_lock)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return if (item.itemId == MENU_MANAGE_PW) { promptSetManagePassword(); true }
        else super.onOptionsItemSelected(item)
    }

    // ---- Screen lock ----

    private fun showContent(show: Boolean) {
        binding.recyclerView.isVisible = show
        binding.addButton.isVisible = show
    }

    private fun reveal() {
        if (unlocked) return
        unlocked = true
        showContent(true)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.categories.collectLatest { list ->
                    adapter.submit(list)
                    binding.emptyText.isVisible = list.isEmpty()
                }
            }
        }
    }

    private fun unlockThenShow() {
        promptPassword(R.string.manage_locked_title, R.string.enter_password, dismissable = false) { entered ->
            lifecycleScope.launch {
                if (viewModel.verifyManagePassword(entered)) {
                    masterUnlocked = true // the master key opens every section
                    reveal()
                } else {
                    toast(R.string.wrong_password)
                    unlockThenShow() // ask again
                }
            }
        }
    }

    /**
     * Runs [action] only after the section is unlocked. A protected section needs
     * its own password (or the master password) every time it is edited, deleted,
     * or opened — unless the master key was already used to enter this screen.
     */
    private fun withSectionAccess(item: CategoryItem, action: () -> Unit) {
        if (!item.hasPassword || masterUnlocked) { action(); return }
        promptPassword(R.string.locked_section_title, R.string.enter_password) { entered ->
            lifecycleScope.launch {
                if (viewModel.verifyPassword(item.name, entered)) action()
                else toast(R.string.wrong_password)
            }
        }
    }

    /** Set, change, or remove the management-screen password (blank removes it). */
    private fun promptSetManagePassword() {
        val input = EditText(this).apply {
            hint = getString(R.string.manage_password_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.manage_password)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val pw = input.text.toString()
                viewModel.setManagePassword(pw)
                toast(if (pw.isBlank()) R.string.password_removed else R.string.password_set)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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

    /** The single edit dialog: rename + hide + protect, all in one place. */
    private fun showEditDialog(item: CategoryItem) {
        val dialogBinding = DialogEditCategoryBinding.inflate(layoutInflater)
        dialogBinding.nameInput.setText(item.name)
        dialogBinding.nameInput.setSelection(item.name.length)
        dialogBinding.protectSwitch.isChecked = item.hasPassword
        dialogBinding.passwordLayout.isVisible = item.hasPassword
        dialogBinding.passwordLayout.hint = getString(
            if (item.hasPassword) R.string.section_password_change_hint else R.string.section_password_hint
        )
        dialogBinding.protectSwitch.setOnCheckedChangeListener { _, checked ->
            dialogBinding.passwordLayout.isVisible = checked
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.edit_category)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save) { _, _ ->
                val newName = dialogBinding.nameInput.text?.toString().orEmpty()
                val protect = dialogBinding.protectSwitch.isChecked
                val pw = dialogBinding.passwordInput.text?.toString().orEmpty()

                // Enabling protection needs a password (new or already set).
                val needsButMissing = protect && pw.isBlank() && !item.hasPassword
                val clearPassword = !protect || needsButMissing
                val newPassword = if (protect && pw.isNotBlank()) pw else null
                // Full-hide was removed — a protected section is visible but obscured,
                // so always clear any legacy hidden flag here.
                viewModel.applyEdit(item.name, newName, hidden = false, newPassword, clearPassword)
                if (needsButMissing) toast(R.string.enter_new_password)
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

    private fun returnOpen(name: String) {
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_OPEN_CATEGORY, name))
        finish()
    }

    private fun promptPassword(
        titleRes: Int,
        hintRes: Int,
        dismissable: Boolean = true,
        onEntered: (String) -> Unit
    ) {
        val input = EditText(this).apply {
            hint = getString(hintRes)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ -> onEntered(input.text.toString()) }
            .setNegativeButton(R.string.cancel) { _, _ -> if (!dismissable) finish() }
        if (!dismissable) builder.setCancelable(false).setOnCancelListener { finish() }
        builder.show()
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_OPEN_CATEGORY = "open_category"
        private const val MENU_MANAGE_PW = 1

        fun intent(context: Context) = Intent(context, CategoriesActivity::class.java)
    }
}
