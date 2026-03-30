package com.minimalplayer

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.minimalplayer.databinding.ActivityFileBrowserBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FileBrowserActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFileBrowserBinding
    private lateinit var resumeManager: ResumeManager
    private lateinit var jellyfin: JellyfinClient

    private val navStack = ArrayDeque<Pair<String, String>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFileBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)
        resumeManager = ResumeManager(this)

        val baseUrl = intent.getStringExtra("base_url") ?: ""
        val username = intent.getStringExtra("username") ?: ""
        val password = intent.getStringExtra("password") ?: ""

        jellyfin = JellyfinClient()
        jellyfin.baseUrl = baseUrl

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.addItemDecoration(
            DividerItemDecoration(this, DividerItemDecoration.VERTICAL)
        )

        binding.btnBack.setOnClickListener { navigateBack() }
        binding.btnRefresh.setOnClickListener { refresh() }
        binding.btnSettings.setOnClickListener {
            // Torna alla schermata di config cancellando le credenziali salvate
            getSharedPreferences("server_config", MODE_PRIVATE)
                .edit().remove("password").putBoolean("remember_me", false).apply()
            finish()
        }
        binding.btnExit.setOnClickListener { finishAffinity() }

        lifecycleScope.launch {
            if (username.isNotEmpty()) {
                binding.progressBar.visibility = View.VISIBLE
                val result = withContext(Dispatchers.IO) {
                    jellyfin.authenticate(username, password)
                }
                result.onFailure {
                    Toast.makeText(this@FileBrowserActivity,
                        "Login fallito: ${it.message}", Toast.LENGTH_LONG).show()
                    finish()
                    return@launch
                }
            }
            loadViews()
        }
    }

    private fun refresh() {
        // Ricarica il livello corrente
        if (navStack.size <= 1) {
            loadViews()
        } else {
            val (_, id) = navStack.last()
            loadItems(id)
        }
    }

    private fun loadViews() {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvEmpty.visibility = View.GONE
        binding.recyclerView.visibility = View.GONE

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { jellyfin.getViews() }
            binding.progressBar.visibility = View.GONE
            result.onSuccess { entries ->
                if (entries.isEmpty()) {
                    binding.tvEmpty.visibility = View.VISIBLE
                } else {
                    navStack.clear()
                    navStack.addLast(Pair("Librerie", "root"))
                    updatePathDisplay()
                    showEntries(entries, isRootView = true)
                }
            }.onFailure {
                Toast.makeText(this@FileBrowserActivity,
                    "Errore: ${it.message}", Toast.LENGTH_LONG).show()
                binding.tvEmpty.visibility = View.VISIBLE
            }
        }
    }

    private fun loadItems(parentId: String) {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvEmpty.visibility = View.GONE
        binding.recyclerView.visibility = View.GONE
        updatePathDisplay()

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { jellyfin.getItems(parentId) }
            binding.progressBar.visibility = View.GONE
            result.onSuccess { entries ->
                if (entries.isEmpty()) binding.tvEmpty.visibility = View.VISIBLE
                else showEntries(entries)
            }.onFailure {
                Toast.makeText(this@FileBrowserActivity,
                    "Errore: ${it.message}", Toast.LENGTH_LONG).show()
                binding.tvEmpty.visibility = View.VISIBLE
            }
        }
    }

    private fun showEntries(entries: List<FileEntry>, isRootView: Boolean = false) {
        binding.recyclerView.visibility = View.VISIBLE
        binding.recyclerView.adapter = FileAdapter(entries, resumeManager, onClick = { entry ->
            onEntryClicked(entry)
        }, isRootView = isRootView)
    }

    private fun onEntryClicked(entry: FileEntry) {
        if (entry.isDirectory) {
            navStack.addLast(Pair(entry.name, entry.jellyfinId))
            loadItems(entry.jellyfinId)
        } else {
            openVideo(entry)
        }
    }

    private fun openVideo(entry: FileEntry) {
        val savedPosition = resumeManager.getPosition(entry.url)
        if (savedPosition > 10_000L) {
            AlertDialog.Builder(this, com.google.android.material.R.style.ThemeOverlay_MaterialComponents_Dialog_Alert)
                .setTitle(entry.name)
                .setMessage("Riprendere da ${resumeManager.formatPosition(savedPosition)}?")
                .setPositiveButton("Riprendi") { _, _ -> startPlayer(entry, savedPosition) }
                .setNegativeButton("Ricomincia") { _, _ ->
                    resumeManager.remove(entry.url)
                    startPlayer(entry, 0L)
                }
                .show()
        } else {
            startPlayer(entry, 0L)
        }
    }

    private fun startPlayer(entry: FileEntry, startPositionMs: Long) {
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra("video_url", entry.url)
            putExtra("video_title", entry.name)
            putExtra("start_position", startPositionMs)
            putExtra("jellyfin_item_id", entry.jellyfinId)
            putExtra("jellyfin_token", jellyfin.accessToken)
            putExtra("jellyfin_base_url", jellyfin.baseUrl)
        })
    }

    private fun navigateBack() {
        if (navStack.size > 1) {
            navStack.removeLast()
            val (_, id) = navStack.last()
            if (id == "root") loadViews() else loadItems(id)
        } else {
            finish()
        }
    }

    private fun updatePathDisplay() {
        binding.tvCurrentPath.text = navStack.joinToString(" › ") { it.first }
        binding.btnBack.visibility = if (navStack.size > 1) View.VISIBLE else View.GONE
    }

    override fun onBackPressed() {
        if (navStack.size > 1) {
            navStack.removeLast()
            val (_, id) = navStack.last()
            if (id == "root") loadViews() else loadItems(id)
        } else {
            super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        binding.recyclerView.adapter?.notifyDataSetChanged()
    }
}
