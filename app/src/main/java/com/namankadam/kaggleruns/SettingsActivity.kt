package com.namankadam.kaggleruns

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException

class SettingsActivity : Activity() {
    private val scope = MainScope()
    private lateinit var tokenInput: EditText
    private lateinit var result: TextView
    private lateinit var save: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        tokenInput = findViewById(R.id.token)
        result = findViewById(R.id.result)
        save = findViewById(R.id.save)
        val remove = findViewById<Button>(R.id.remove)
        WidgetRenderer.updateAll(this) // opening the app also repairs a blank widget

        TokenStore.load(this)?.let {
            result.text = getString(R.string.token_saved, it.takeLast(4))
            remove.visibility = View.VISIBLE
        }

        save.setOnClickListener {
            val token = tokenInput.text.toString().trim()
            if (token.isEmpty()) {
                result.text = getString(R.string.token_empty)
            } else {
                verifyAndSave(token)
            }
        }
        remove.setOnClickListener {
            TokenStore.clear(this)
            SnapshotCache.clear(this)
            WidgetRenderer.updateAll(this)
            result.text = getString(R.string.token_removed)
            remove.visibility = View.GONE
        }
    }

    private fun verifyAndSave(token: String) {
        save.isEnabled = false
        result.text = getString(R.string.checking)
        scope.launch {
            result.text = try {
                val kernels = KaggleApi(token).listMine()
                TokenStore.save(this@SettingsActivity, token)
                tokenInput.text.clear()
                findViewById<Button>(R.id.remove).visibility = View.VISIBLE
                RefreshWorker.schedulePeriodic(this@SettingsActivity)
                RefreshWorker.refreshNow(this@SettingsActivity)
                val user = kernels.firstOrNull()?.first?.substringBefore('/')
                if (user != null) resources.getQuantityString(R.plurals.connected_as, kernels.size, user, kernels.size)
                else getString(R.string.connected_empty)
            } catch (e: AuthException) {
                getString(R.string.token_rejected)
            } catch (e: IOException) {
                getString(R.string.network_error, e.message ?: "")
            } catch (e: Exception) {
                getString(R.string.network_error, e.javaClass.simpleName)
            }
            save.isEnabled = true
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
