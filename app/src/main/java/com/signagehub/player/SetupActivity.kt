package com.signagehub.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SetupActivity : AppCompatActivity() {
    private lateinit var configStore: ConfigStore
    private lateinit var serverUrlEditText: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        configStore = ConfigStore(this)
        val forceEdit = intent.getBooleanExtra(EXTRA_FORCE_EDIT, false)
        if (configStore.serverUrl.isNotBlank() && !forceEdit) {
            openMainActivity()
            return
        }

        setContentView(R.layout.activity_setup)

        serverUrlEditText = findViewById(R.id.server_url_input)
        serverUrlEditText.setText(configStore.serverUrl)

        findViewById<Button>(R.id.save_button).setOnClickListener {
            saveServerUrl()
        }
    }

    private fun saveServerUrl() {
        val rawValue = serverUrlEditText.text?.toString().orEmpty().trim()
        if (!isValidServerUrl(rawValue)) {
            Toast.makeText(this, R.string.setup_invalid_url, Toast.LENGTH_SHORT).show()
            return
        }

        configStore.serverUrl = rawValue
        configStore.failedLoadCount = 0

        Toast.makeText(this, R.string.setup_saved, Toast.LENGTH_SHORT).show()
        openMainActivity()
    }

    private fun openMainActivity() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        finish()
    }

    private fun isValidServerUrl(value: String): Boolean {
        return value.startsWith("http://") || value.startsWith("https://")
    }

    companion object {
        private const val EXTRA_FORCE_EDIT = "force_edit"

        fun createIntent(context: Context, forceEdit: Boolean = false): Intent {
            return Intent(context, SetupActivity::class.java).putExtra(EXTRA_FORCE_EDIT, forceEdit)
        }
    }
}
