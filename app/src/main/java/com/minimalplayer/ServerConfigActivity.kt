package com.minimalplayer

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.minimalplayer.databinding.ActivityServerConfigBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ServerConfigActivity : AppCompatActivity() {

    private lateinit var binding: ActivityServerConfigBinding
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityServerConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("server_config", MODE_PRIVATE)

        val url = prefs.getString("url", "http://192.168.1.200:8096") ?: ""
        val username = prefs.getString("username", "") ?: ""
        val password = if (prefs.getBoolean("remember_me", false))
            prefs.getString("password", "") ?: "" else ""

        // Se ci sono credenziali salvate, connetti direttamente
        if (url.isNotEmpty() && username.isNotEmpty() && password.isNotEmpty()) {
            connectDirectly(url, username, password)
            return
        }

        // Altrimenti mostra la schermata di configurazione
        showConfigScreen(url, username)
    }

    private fun showConfigScreen(url: String, username: String) {
        binding.etServerUrl.setText(url)
        binding.etUsername.setText(username)
        val remembered = prefs.getBoolean("remember_me", false)
        binding.cbRememberMe.isChecked = remembered
        if (remembered) {
            binding.etPassword.setText(prefs.getString("password", ""))
        }

        binding.btnConnect.setOnClickListener { connect() }
        binding.etPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) { connect(); true } else false
        }
        binding.etServerUrl.requestFocus()
    }

    private fun connectDirectly(url: String, username: String, password: String) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val client = JellyfinClient()
                client.baseUrl = url
                client.authenticate(username, password)
            }
            result.onSuccess {
                // Vai direttamente al browser
                openBrowser(url, username, password)
            }.onFailure {
                // Login fallito — mostra la schermata di configurazione
                showConfigScreen(url, username)
                Toast.makeText(this@ServerConfigActivity,
                    "Connessione fallita, riprova", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun connect() {
        val url = binding.etServerUrl.text.toString().trim().trimEnd('/')
        if (url.isEmpty()) {
            Toast.makeText(this, "Inserisci l'URL del server", Toast.LENGTH_SHORT).show()
            return
        }

        val username = binding.etUsername.text.toString().trim()
        val password = binding.etPassword.text.toString()
        val rememberMe = binding.cbRememberMe.isChecked

        binding.btnConnect.isEnabled = false
        binding.btnConnect.text = "Connessione…"

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val client = JellyfinClient()
                client.baseUrl = url
                if (username.isNotEmpty()) client.authenticate(username, password)
                else Result.success(Unit)
            }

            binding.btnConnect.isEnabled = true
            binding.btnConnect.text = "CONNETTI"

            result.onSuccess {
                prefs.edit()
                    .putString("url", url)
                    .putString("username", username)
                    .putBoolean("remember_me", rememberMe)
                    .apply()
                if (rememberMe) prefs.edit().putString("password", password).apply()
                else prefs.edit().remove("password").apply()
                openBrowser(url, username, password)
            }.onFailure {
                Toast.makeText(this@ServerConfigActivity,
                    "Errore: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openBrowser(url: String, username: String, password: String) {
        startActivity(Intent(this, FileBrowserActivity::class.java).apply {
            putExtra("base_url", url)
            putExtra("username", username)
            putExtra("password", password)
        })
        // Non finire questa activity — serve come fallback se si preme indietro
    }
}
