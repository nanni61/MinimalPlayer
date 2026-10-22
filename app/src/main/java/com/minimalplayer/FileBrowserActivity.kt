package com.minimalplayer

import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.view.KeyEvent
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

    // Salvate per poter ri-autenticare in automatico se il token scade
    // mentre l'app è rimasta in background.
    private var storedUsername = ""
    private var storedPassword = ""

    // true quando l'utente vuole uscire davvero: alla distruzione dell'Activity
    // il processo viene terminato, così nulla resta vivo in background.
    private var exiting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFileBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)
        resumeManager = ResumeManager(this)

        val baseUrl = intent.getStringExtra("base_url") ?: ""
        storedUsername = intent.getStringExtra("username") ?: ""
        storedPassword = intent.getStringExtra("password") ?: ""

        jellyfin = JellyfinClient()
        jellyfin.baseUrl = baseUrl

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))

        // Garantisce che i bottoni della toolbar siano sempre raggiungibili col D-pad,
        // indipendentemente da come sono dichiarati nel layout XML.
        listOf(binding.btnBack, binding.btnRefresh, binding.btnSettings, binding.btnExit).forEach {
            it.isFocusable = true
            it.isFocusableInTouchMode = true
        }

        binding.btnBack.setOnClickListener { navigateBack() }
        binding.btnRefresh.setOnClickListener { refresh() }
        binding.btnSettings.setOnClickListener {
            getSharedPreferences("server_config", MODE_PRIVATE)
                .edit().remove("password").putBoolean("remember_me", false).apply()
            finish()
        }
        binding.btnExit.setOnClickListener { exitApp() }

        lifecycleScope.launch {
            if (storedUsername.isNotEmpty()) {
                binding.progressBar.visibility = View.VISIBLE
                val result = withContext(Dispatchers.IO) { jellyfin.authenticate(storedUsername, storedPassword) }
                result.onFailure {
                    Toast.makeText(this@FileBrowserActivity, "Login fallito: ${it.message}", Toast.LENGTH_LONG).show()
                    finish(); return@launch
                }
            }
            loadViews()
        }
    }

    // Uscita completa: chiude tutte le Activity, toglie l'app dalle app recenti
    // e (in onDestroy) termina il processo.
    private fun exitApp() {
        exiting = true
        finishAndRemoveTask()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (exiting) Process.killProcess(Process.myPid())
    }

    private fun refresh() {
        if (navStack.size <= 1) loadViews()
        else loadItems(navStack.last().second)
    }

    // Un errore 401 (token scaduto/non valido) attiva una ri-autenticazione
    // silenziosa seguita da un unico retry della stessa chiamata.
    private fun isAuthError(e: Throwable?): Boolean =
        e?.message?.contains("401") == true

    private suspend fun reauthenticate(): Boolean {
        if (storedUsername.isEmpty()) return false
        val result = jellyfin.authenticate(storedUsername, storedPassword)
        return result.isSuccess
    }

    private fun loadViews() {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvEmpty.visibility = View.GONE
        binding.recyclerView.visibility = View.GONE

        lifecycleScope.launch {
            var result = withContext(Dispatchers.IO) { jellyfin.getViews() }
            if (result.isFailure && isAuthError(result.exceptionOrNull())) {
                val reauthed = withContext(Dispatchers.IO) { reauthenticate() }
                if (reauthed) result = withContext(Dispatchers.IO) { jellyfin.getViews() }
            }
            binding.progressBar.visibility = View.GONE
            result.onSuccess { entries ->
                if (entries.isEmpty()) binding.tvEmpty.visibility = View.VISIBLE
                else {
                    navStack.clear()
                    navStack.addLast(Pair("Librerie", "root"))
                    updatePathDisplay()
                    showEntries(entries, isRootView = true)
                }
            }.onFailure {
                Toast.makeText(this@FileBrowserActivity, "Errore: ${it.message}", Toast.LENGTH_LONG).show()
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
            var result = withContext(Dispatchers.IO) { jellyfin.getItems(parentId) }
            if (result.isFailure && isAuthError(result.exceptionOrNull())) {
                val reauthed = withContext(Dispatchers.IO) { reauthenticate() }
                if (reauthed) result = withContext(Dispatchers.IO) { jellyfin.getItems(parentId) }
            }
            binding.progressBar.visibility = View.GONE
            result.onSuccess { entries ->
                if (entries.isEmpty()) binding.tvEmpty.visibility = View.VISIBLE
                else showEntries(entries)
            }.onFailure {
                Toast.makeText(this@FileBrowserActivity, "Errore: ${it.message}", Toast.LENGTH_LONG).show()
                binding.tvEmpty.visibility = View.VISIBLE
            }
        }
    }

    private fun showEntries(entries: List<FileEntry>, isRootView: Boolean = false) {
        binding.recyclerView.visibility = View.VISIBLE
        binding.recyclerView.adapter = FileAdapter(
            entries = entries,
            resumeManager = resumeManager,
            onClick = { onEntryClicked(it) },
            isRootView = isRootView
        )
        binding.recyclerView.post {
            binding.recyclerView.requestFocus()
            binding.recyclerView.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
    }

    private fun onEntryClicked(entry: FileEntry) {
        if (entry.isDirectory) {
            navStack.addLast(Pair(entry.name, entry.jellyfinId))
            loadItems(entry.jellyfinId)
        } else openVideo(entry)
    }

    private fun openVideo(entry: FileEntry) {
        val savedPosition = resumeManager.getPosition(entry.url)
        if (savedPosition > 10_000L) {
            AlertDialog.Builder(this, com.google.android.material.R.style.ThemeOverlay_MaterialComponents_Dialog_Alert)
                .setTitle(entry.name)
                .setMessage("Riprendere da ${resumeManager.formatPosition(savedPosition)}?")
                .setPositiveButton("Riprendi") { _, _ -> startPlayer(entry, savedPosition) }
                .setNegativeButton("Ricomincia") { _, _ -> resumeManager.remove(entry.url); startPlayer(entry, 0L) }
                .show()
        } else startPlayer(entry, 0L)
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
        } else exitApp()
    }

    private fun updatePathDisplay() {
        binding.tvCurrentPath.text = navStack.joinToString(" › ") { it.first }
        binding.btnBack.visibility = if (navStack.size > 1) View.VISIBLE else View.GONE
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // Dentro una cartella: sale di un livello. Dalla radice: uscita completa.
            if (navStack.size > 1) navigateBack() else exitApp()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // Il focus-search automatico di Android a volte non trova la barra in alto
    // partendo dalla RecyclerView. Se il focus è sulla prima riga della lista e
    // viene premuto "Su", lo spostiamo esplicitamente su un bottone della toolbar.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            val focused = currentFocus
            if (focused != null) {
                val position = binding.recyclerView.getChildAdapterPosition(focused)
                if (position == 0) {
                    val target = if (binding.btnBack.visibility == View.VISIBLE) {
                        binding.btnBack
                    } else {
                        binding.btnRefresh
                    }
                    target.requestFocus()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        binding.recyclerView.adapter?.notifyDataSetChanged()
        binding.recyclerView.post { binding.recyclerView.requestFocus() }
    }
}
