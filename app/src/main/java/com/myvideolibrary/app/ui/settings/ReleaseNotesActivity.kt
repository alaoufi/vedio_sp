package com.myvideolibrary.app.ui.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.myvideolibrary.app.R
import com.myvideolibrary.app.databinding.ActivityReleaseNotesBinding

class ReleaseNotesActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityReleaseNotesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val notes = ReleaseNotes.latest()
        binding.versionText.text = getString(R.string.release_notes_version, notes.version)
        binding.changesText.text = getString(R.string.release_notes_changes)
    }
}
