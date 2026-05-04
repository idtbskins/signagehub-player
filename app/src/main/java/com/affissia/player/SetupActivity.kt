package com.affissia.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SetupActivity : AppCompatActivity() {
    private lateinit var configStore: ConfigStore
    private lateinit var deviceIdentity: DeviceIdentity
    private lateinit var secretStore: SecretStore
    private lateinit var serverUrlSection: LinearLayout
    private lateinit var serverUrlEditText: EditText
    private lateinit var inviteCodeEditText: EditText
    private lateinit var discoveryStatusView: TextView
    private var serverDiscovery: ServerDiscovery? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        configStore = ConfigStore(this)
        deviceIdentity = DeviceIdentity(this)
        secretStore = SecretStore(this)
        val forceEdit = intent.getBooleanExtra(EXTRA_FORCE_EDIT, false)
        val hasProvisioningCredential =
            configStore.deviceInviteCode.isNotBlank() || secretStore.secret != null
        if (configStore.serverUrl.isNotBlank() && hasProvisioningCredential && !forceEdit) {
            openMainActivity()
            return
        }

        setContentView(R.layout.activity_setup)

        serverUrlSection = findViewById(R.id.server_url_section)
        serverUrlEditText = findViewById(R.id.server_url_input)
        inviteCodeEditText = findViewById(R.id.invite_code_input)
        discoveryStatusView = findViewById(R.id.discovery_status)
        val initialServerUrl = configStore.serverUrl.ifBlank { BuildConfig.DEFAULT_SERVER_URL }
        val showServerUrlField = forceEdit || initialServerUrl.isBlank()
        serverUrlSection.visibility = if (showServerUrlField) View.VISIBLE else View.GONE
        serverUrlEditText.setText(initialServerUrl)
        inviteCodeEditText.setText(configStore.deviceInviteCode)

        findViewById<Button>(R.id.save_button).setOnClickListener {
            saveServerUrl()
        }

        // Production installers should only enter the invite code. Server URL
        // remains hidden and defaults to BuildConfig.DEFAULT_SERVER_URL. The
        // URL field is visible only from the long-press maintenance path, or
        // when a special build has no default URL and must rely on mDNS.
        if (showServerUrlField && serverUrlEditText.text.isNullOrBlank()) {
            startDiscovery()
        } else {
            discoveryStatusView.visibility = TextView.GONE
        }
    }

    private fun startDiscovery() {
        discoveryStatusView.visibility = TextView.VISIBLE
        discoveryStatusView.text = getString(R.string.setup_searching)
        val discovery = ServerDiscovery(this)
        serverDiscovery = discovery
        discovery.start(object : ServerDiscovery.Listener {
            override fun onServerFound(url: String, host: String, port: Int) {
                // Only auto-fill if the user hasn't started typing.
                if (serverUrlEditText.text.isNullOrBlank()) {
                    serverUrlEditText.setText(url)
                    serverUrlEditText.setSelection(url.length)
                }
                discoveryStatusView.text = getString(R.string.setup_found, url)
            }

            override fun onTimeout() {
                discoveryStatusView.text = getString(R.string.setup_not_found)
            }
        })
    }

    override fun onDestroy() {
        serverDiscovery?.stop()
        serverDiscovery = null
        super.onDestroy()
    }

    private fun saveServerUrl() {
        val rawValue = if (serverUrlSection.visibility == View.VISIBLE) {
            serverUrlEditText.text?.toString().orEmpty().trim()
        } else {
            configStore.serverUrl.ifBlank { BuildConfig.DEFAULT_SERVER_URL }.trim()
        }
        if (!isValidServerUrl(rawValue)) {
            Toast.makeText(this, R.string.setup_invalid_url, Toast.LENGTH_SHORT).show()
            return
        }
        val inviteCode = inviteCodeEditText.text?.toString().orEmpty().trim()
        if (inviteCode.isBlank() || !isValidInviteCode(inviteCode)) {
            Toast.makeText(this, R.string.setup_invalid_invite_code, Toast.LENGTH_SHORT).show()
            return
        }

        configStore.serverUrl = rawValue
        configStore.deviceInviteCode = inviteCode
        configStore.failedLoadCount = 0

        // Best-effort device announcement. A valid invite code is required
        // before a new Player can enter any merchant-visible pending list.
        val displaySize = DisplayInfo.resolution(this)
        Thread {
            val result = AnnouncementClient.announce(
                serverUrl = rawValue,
                deviceId = deviceIdentity.deviceId,
                deviceLabel = deviceIdentity.deviceLabel,
                appVersion = BuildConfig.VERSION_NAME,
                inviteCode = configStore.deviceInviteCode,
                displaySize = displaySize,
            )
            if (result.ok && !result.pairCode.isNullOrBlank()) {
                configStore.pairCode = result.pairCode
            }
            result.deviceSecret?.let { secret ->
                if (secretStore.isAvailable) {
                    secretStore.secret = secret
                }
            }
        }.start()

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

    private fun isValidInviteCode(value: String): Boolean {
        val cleaned = value.trim().replace("[\\s-]+".toRegex(), "")
        return cleaned.length in setOf(6, 12) && cleaned.all { it.isLetterOrDigit() }
    }

    companion object {
        private const val EXTRA_FORCE_EDIT = "force_edit"

        fun createIntent(context: Context, forceEdit: Boolean = false): Intent {
            return Intent(context, SetupActivity::class.java).putExtra(EXTRA_FORCE_EDIT, forceEdit)
        }
    }
}
