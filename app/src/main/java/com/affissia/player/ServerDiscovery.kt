package com.affissia.player

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Zero-config discovery of an Affissia server on the local network using
 * mDNS / Bonjour. The server (Flask backend with `SIGNAGEHUB_ENABLE_MDNS=1`)
 * advertises itself as `_signagehub._tcp.local.` on the LAN; this class
 * listens for that service type and reports the first one found via the
 * provided callback as a fully-formed URL like `http://192.168.1.16:5010`.
 *
 * The result is intended to auto-fill the setup screen so the operator
 * doesn't need to type the server URL by hand on every paired device.
 *
 * Caveats:
 * - Some routers / network configurations block mDNS multicast. Always
 *   pair this with a manual-entry fallback.
 * - Android requires a held WifiManager.MulticastLock for mDNS to receive
 *   packets reliably; the lock is released on stop().
 * - Only the first discovered service is reported, then discovery stops.
 *   In a multi-server LAN the operator can still type the URL manually.
 */
class ServerDiscovery(context: Context) {
    interface Listener {
        fun onServerFound(url: String, host: String, port: Int)
        fun onTimeout()
    }

    private val appContext = context.applicationContext
    private val nsdManager: NsdManager? =
        appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifiManager: WifiManager? =
        appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var listener: Listener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var timeoutRunnable: Runnable? = null
    private var stopped = false

    fun start(listener: Listener, timeoutMs: Long = 10_000L) {
        if (nsdManager == null) {
            // No NSD service available on this device — caller will fall
            // back to manual entry. Surface as timeout immediately so the
            // UI shows the "not found" hint right away.
            mainHandler.post { listener.onTimeout() }
            return
        }
        this.listener = listener
        stopped = false

        // mDNS uses multicast which Android disables on Wi-Fi by default
        // to save battery. Hold a multicast lock while discovering.
        multicastLock = wifiManager?.createMulticastLock(MULTICAST_LOCK_TAG)?.apply {
            setReferenceCounted(false)
            try { acquire() } catch (e: Exception) { /* no-op */ }
        }

        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "Discovery started for $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service found: ${serviceInfo.serviceName} (${serviceInfo.serviceType})")
                resolveService(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${serviceInfo.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "Discovery stopped for $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Start discovery failed: $errorCode")
                mainHandler.post {
                    if (!stopped) reportTimeout()
                }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Stop discovery failed: $errorCode")
            }
        }
        discoveryListener = discovery

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
        } catch (e: Exception) {
            Log.w(TAG, "discoverServices threw", e)
            mainHandler.post { reportTimeout() }
            return
        }

        // Bound the wait so the UI doesn't sit on "Searching…" forever
        // when the network blocks mDNS entirely.
        timeoutRunnable = Runnable {
            if (!stopped) reportTimeout()
        }.also {
            mainHandler.postDelayed(it, timeoutMs)
        }
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        val mgr = nsdManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ deprecated the legacy `resolveService` callback
            // in favour of `registerServiceInfoCallback`. We use the
            // legacy API conditionally below for older devices and skip
            // the new API here to keep the dependency surface minimal —
            // legacy resolveService still works on 14+ with a deprecation
            // warning at runtime.
        }
        try {
            @Suppress("DEPRECATION")
            mgr.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    val host = resolved.host?.hostAddress ?: return
                    val port = resolved.port
                    if (port <= 0) return
                    val url = "http://$host:$port"
                    Log.d(TAG, "Resolved $url")
                    mainHandler.post { reportFound(url, host, port) }
                }

                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
                    // Don't surface timeout yet — there might be other services.
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "resolveService threw", e)
        }
    }

    private fun reportFound(url: String, host: String, port: Int) {
        if (stopped) return
        stopped = true
        cancelTimeout()
        listener?.onServerFound(url, host, port)
        // Stop discovery — we only consume the first hit.
        stopDiscovery()
        releaseLock()
    }

    private fun reportTimeout() {
        if (stopped) return
        stopped = true
        cancelTimeout()
        listener?.onTimeout()
        stopDiscovery()
        releaseLock()
    }

    fun stop() {
        if (stopped) return
        stopped = true
        cancelTimeout()
        stopDiscovery()
        releaseLock()
        listener = null
    }

    private fun stopDiscovery() {
        val mgr = nsdManager ?: return
        val l = discoveryListener ?: return
        try {
            mgr.stopServiceDiscovery(l)
        } catch (e: Exception) {
            Log.w(TAG, "stopServiceDiscovery threw", e)
        }
        discoveryListener = null
    }

    private fun cancelTimeout() {
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = null
    }

    private fun releaseLock() {
        multicastLock?.let { lock ->
            try {
                if (lock.isHeld) lock.release()
            } catch (e: Exception) { /* no-op */ }
        }
        multicastLock = null
    }

    companion object {
        private const val TAG = "ServerDiscovery"
        private const val MULTICAST_LOCK_TAG = "AffissiaPlayerMulticast"
        // Must match SIGNAGEHUB_MDNS_SERVICE_TYPE on the server side
        // (config.py default: "_signagehub._tcp.local."). Android NSD
        // expects the trailing-dot-less form.
        private const val SERVICE_TYPE = "_signagehub._tcp."
    }
}
