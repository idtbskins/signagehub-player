package com.affissia.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SetupActivity : AppCompatActivity() {
    private lateinit var configStore: ConfigStore
    private lateinit var deviceIdentity: DeviceIdentity
    private lateinit var secretStore: SecretStore
    private lateinit var serverUrlEditText: EditText
    private lateinit var discoveryStatusView: TextView
    private var serverDiscovery: ServerDiscovery? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        configStore = ConfigStore(this)
        deviceIdentity = DeviceIdentity(this)
        secretStore = SecretStore(this)
        val forceEdit = intent.getBooleanExtra(EXTRA_FORCE_EDIT, false)
        if (configStore.serverUrl.isNotBlank() && !forceEdit) {
            openMainActivity()
            return
        }

        // First boot + APK ships with a baked-in default URL → save it and
        // jump straight to the WebView. The operator sees no setup screen
        // at all; they just install the APK and the player starts playing.
        // (Force-edit mode bypasses this so a tech can still change URL.)
        if (!forceEdit && BuildConfig.DEFAULT_SERVER_URL.isNotBlank()) {
            configStore.serverUrl = BuildConfig.DEFAULT_SERVER_URL
            configStore.failedLoadCount = 0
            val displaySize = DisplayInfo.resolution(this)
            Thread {
                val result = AnnouncementClient.announce(
                    serverUrl = BuildConfig.DEFAULT_SERVER_URL,
                    deviceId = deviceIdentity.deviceId,
                    deviceLabel = deviceIdentity.deviceLabel,
                    appVersion = BuildConfig.VERSION_NAME,
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
            openMainActivity()
            return
        }

        setContentView(R.layout.activity_setup)

        serverUrlEditText = findViewById(R.id.server_url_input)
        discoveryStatusView = findViewById(R.id.discovery_status)
        serverUrlEditText.setText(configStore.serverUrl)

        findViewById<Button>(R.id.save_button).setOnClickListener {
            saveServerUrl()
        }

        // Phase 2 — zero-config discovery: scan the LAN for an Affissia
        // server (mDNS) and auto-fill the URL field. Operator can still
        // type a URL manually if discovery fails or finds the wrong one.
        // Skip the scan if the field is already populated (operator
        // pressed long-press > "Change server URL" knowing what they want).
        if (serverUrlEditText.text.isNullOrBlank()) {
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
        val rawValue = serverUrlEditText.text?.toString().orEmpty().trim()
        if (!isValidServerUrl(rawValue)) {
            Toast.makeText(this, R.string.setup_invalid_url, Toast.LENGTH_SHORT).show()
            return
        }

        configStore.serverUrl = rawValue
        configStore.failedLoadCount = 0

        // Best-effort device announcement. Tolerates 404 (backend may not
        // have shipped the endpoint yet) — pairing still works via the
        // existing 6-digit code flow served by the WebView.
        val displaySize = DisplayInfo.resolution(this)
        Thread {
            val result = AnnouncementClient.announce(
                serverUrl = rawValue,
                deviceId = deviceIdentity.deviceId,
                deviceLabel = deviceIdentity.deviceLabel,
                appVersion = BuildConfig.VERSION_NAME,
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

    companion object {
        private const val EXTRA_FORCE_EDIT = "force_edit"

        fun createIntent(context: Context, forceEdit: Boolean = false): Intent {
            return Intent(context, SetupActivity::class.java).putExtra(EXTRA_FORCE_EDIT, forceEdit)
        }
    }
}
