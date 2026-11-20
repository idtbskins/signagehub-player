package com.affissia.player

import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Wire payload broadcast over LAN UDP by the elected Master screen of a video wall.
 */
data class LanWallBeacon(
    val wallKey: String,
    val screenId: String,
    val positionMs: Long,
    val durationMs: Long,
    val syncOffsetMs: Long,
    val sequence: Long,
) {
    fun encode(): ByteArray {
        val json = JSONObject().apply {
            put("v", PROTOCOL_VERSION)
            put("k", wallKey)
            put("s", screenId)
            put("p", positionMs)
            put("d", durationMs)
            put("o", syncOffsetMs)
            put("q", sequence)
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        const val PROTOCOL_VERSION = 1

        fun decode(bytes: ByteArray, length: Int = bytes.size): LanWallBeacon? {
            if (length <= 0 || length > MAX_PACKET_BYTES) return null
            return try {
                val json = JSONObject(String(bytes, 0, length, Charsets.UTF_8))
                if (json.optInt("v", 0) != PROTOCOL_VERSION) return null
                val wallKey = json.optString("k", "").trim()
                val screenId = json.optString("s", "").trim()
                val positionMs = json.optLong("p", -1L)
                val durationMs = json.optLong("d", 0L)
                val syncOffsetMs = json.optLong("o", 0L)
                val sequence = json.optLong("q", 0L)
                if (wallKey.isBlank() || screenId.isBlank() || positionMs < 0L || durationMs <= 0L) {
                    return null
                }
                LanWallBeacon(
                    wallKey = wallKey,
                    screenId = screenId,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    syncOffsetMs = syncOffsetMs,
                    sequence = sequence,
                )
            } catch (_: Exception) {
                null
            }
        }

        const val MAX_PACKET_BYTES = 1024
    }
}

/**
 * Pure leader-election and target-position tracker for LAN Master/Slave sync.
 *
 * Leader rule (zero configuration):
 *   Among all active peers playing the same `wallKey`, the screen with the
 *   lexicographically smallest `screenId` is Master.
 *
 * - If a node hears a valid beacon for the same `wallKey` from a peer with
 *   `peer.screenId < self.screenId`, it adopts that peer as Master and aligns
 *   its playback position directly to the Master's media timeline (sub-20ms
 *   local precision, immune to WAN NTP jitter).
 * - If no lower-ID Master beacon has arrived within [masterTimeoutMs], this
 *   node assumes Master role and broadcasts its own playback position every
 *   ~400ms (while aligning itself to the server monotonic clock).
 * - If router AP isolation blocks UDP completely, every screen cleanly falls
 *   back to server monotonic clock sync with zero errors.
 */
class LanWallSyncState(
    private val masterTimeoutMs: Long = DEFAULT_MASTER_TIMEOUT_MS,
    private val estimatedLanTransitMs: Long = DEFAULT_ESTIMATED_LAN_TRANSIT_MS,
) {
    data class ObservedMaster(
        val beacon: LanWallBeacon,
        val receivedElapsedMs: Long,
    )

    @Volatile
    private var observedMaster: ObservedMaster? = null

    @Synchronized
    fun onBeaconReceived(
        beacon: LanWallBeacon,
        expectedWallKey: String,
        selfScreenId: String,
        receivedElapsedMs: Long,
    ): Boolean {
        if (beacon.wallKey != expectedWallKey) return false
        if (beacon.screenId == selfScreenId) return false
        if (!isHigherPriorityMaster(candidateScreenId = beacon.screenId, thanScreenId = selfScreenId)) {
            return false
        }
        val current = observedMaster
        if (current != null && (receivedElapsedMs - current.receivedElapsedMs) <= masterTimeoutMs) {
            if (isHigherPriorityMaster(current.beacon.screenId, beacon.screenId)) {
                // Keep the higher-priority (smaller screenId) master.
                return false
            }
            if (current.beacon.screenId == beacon.screenId && beacon.sequence < current.beacon.sequence) {
                // Ignore reordered stale UDP packet.
                return false
            }
        }
        observedMaster = ObservedMaster(beacon = beacon, receivedElapsedMs = receivedElapsedMs)
        return true
    }

    @Synchronized
    fun shouldBroadcastAsMaster(nowElapsedMs: Long): Boolean {
        val current = observedMaster ?: return true
        return (nowElapsedMs - current.receivedElapsedMs) > masterTimeoutMs
    }

    /**
     * Returns the Master-derived playback target position for this Slave, or
     * `null` if this screen is currently the Master (or no fresh Master beacon
     * is available, in which case caller falls back to server-clock sync).
     */
    @Synchronized
    fun resolveSlaveTargetPositionMs(
        expectedWallKey: String,
        selfSyncOffsetMs: Long,
        durationMs: Long,
        nowElapsedMs: Long,
    ): Long? {
        if (durationMs <= 0L) return null
        val current = observedMaster ?: return null
        if (current.beacon.wallKey != expectedWallKey) return null
        val ageMs = nowElapsedMs - current.receivedElapsedMs
        if (ageMs < 0L || ageMs > masterTimeoutMs) return null

        val masterNowPosMs = current.beacon.positionMs + ageMs + estimatedLanTransitMs
        val canonicalWallPosMs = masterNowPosMs - current.beacon.syncOffsetMs
        return WallDriftController.positiveModulo(canonicalWallPosMs + selfSyncOffsetMs, durationMs)
    }

    @Synchronized
    fun activeMasterScreenId(nowElapsedMs: Long): String? {
        val current = observedMaster ?: return null
        return if ((nowElapsedMs - current.receivedElapsedMs) <= masterTimeoutMs) {
            current.beacon.screenId
        } else {
            null
        }
    }

    @Synchronized
    fun reset() {
        observedMaster = null
    }

    companion object {
        const val DEFAULT_MASTER_TIMEOUT_MS = 2_500L
        const val DEFAULT_ESTIMATED_LAN_TRANSIT_MS = 2L

        fun isHigherPriorityMaster(candidateScreenId: String, thanScreenId: String): Boolean {
            return candidateScreenId < thanScreenId
        }
    }
}

/**
 * Lightweight UDP broadcaster/listener driving [LanWallSyncState] while a
 * native video wall is actively playing. All socket errors are caught and
 * logged softly so network restrictions never disrupt playback.
 */
class LanWallSyncManager(
    private val port: Int = DEFAULT_UDP_PORT,
) {
    data class LocalPlaybackSnapshot(
        val positionMs: Long,
        val durationMs: Long,
        val capturedElapsedMs: Long,
        val isPlaying: Boolean,
    )

    private val state = LanWallSyncState()
    private val running = AtomicBoolean(false)
    private val sequence = AtomicLong(0L)

    @Volatile private var activeWallKey: String = ""
    @Volatile private var activeScreenId: String = ""
    @Volatile private var activeSyncOffsetMs: Long = 0L
    @Volatile private var snapshotProvider: (() -> LocalPlaybackSnapshot?)? = null
    @Volatile private var socket: DatagramSocket? = null

    fun start(
        wallKey: String,
        screenId: String,
        syncOffsetMs: Long,
        snapshotProvider: () -> LocalPlaybackSnapshot?,
    ) {
        if (wallKey.isBlank() || screenId.isBlank()) return
        if (running.get() && activeWallKey == wallKey && activeScreenId == screenId) {
            activeSyncOffsetMs = syncOffsetMs
            this.snapshotProvider = snapshotProvider
            return
        }
        stop()

        activeWallKey = wallKey
        activeScreenId = screenId
        activeSyncOffsetMs = syncOffsetMs
        this.snapshotProvider = snapshotProvider
        state.reset()
        sequence.set(0L)

        val openedSocket = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                soTimeout = RECEIVE_TIMEOUT_MS
                bind(InetSocketAddress(port))
            }
        } catch (e: Exception) {
            Log.d(TAG, "LAN UDP sync unavailable on port $port: ${e.message}")
            return
        }

        socket = openedSocket
        running.set(true)

        Thread({ runReceiveLoop(openedSocket) }, "lan-wall-sync-rx").apply {
            isDaemon = true
            start()
        }
        Thread({ runBroadcastLoop(openedSocket) }, "lan-wall-sync-tx").apply {
            isDaemon = true
            start()
        }
        Log.d(TAG, "LAN UDP sync started key=$wallKey screen=$screenId port=$port")
    }

    fun stop() {
        running.set(false)
        snapshotProvider = null
        activeWallKey = ""
        activeScreenId = ""
        state.reset()
        val currentSocket = socket
        socket = null
        try {
            currentSocket?.close()
        } catch (_: Exception) {
            // no-op
        }
    }

    fun resolveSlaveTargetPositionMs(durationMs: Long): Long? {
        if (!running.get()) return null
        return state.resolveSlaveTargetPositionMs(
            expectedWallKey = activeWallKey,
            selfSyncOffsetMs = activeSyncOffsetMs,
            durationMs = durationMs,
            nowElapsedMs = SystemClock.elapsedRealtime(),
        )
    }

    fun activeMasterScreenId(): String? {
        if (!running.get()) return null
        return state.activeMasterScreenId(SystemClock.elapsedRealtime())
    }

    private fun runReceiveLoop(sock: DatagramSocket) {
        val buffer = ByteArray(LanWallBeacon.MAX_PACKET_BYTES)
        while (running.get() && !sock.isClosed) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                sock.receive(packet)
                val receivedAt = SystemClock.elapsedRealtime()
                val beacon = LanWallBeacon.decode(packet.data, packet.length) ?: continue
                state.onBeaconReceived(
                    beacon = beacon,
                    expectedWallKey = activeWallKey,
                    selfScreenId = activeScreenId,
                    receivedElapsedMs = receivedAt,
                )
            } catch (_: SocketTimeoutException) {
                // Expected periodic wake-up to check running flag.
            } catch (e: Exception) {
                if (running.get()) {
                    Log.d(TAG, "LAN UDP receive loop ended: ${e.message}")
                }
                break
            }
        }
    }

    private fun runBroadcastLoop(sock: DatagramSocket) {
        while (running.get() && !sock.isClosed) {
            try {
                val nowElapsed = SystemClock.elapsedRealtime()
                if (state.shouldBroadcastAsMaster(nowElapsed)) {
                    val snapshot = snapshotProvider?.invoke()
                    val snapshotAgeMs = if (snapshot != null) nowElapsed - snapshot.capturedElapsedMs else Long.MAX_VALUE
                    if (snapshot != null && snapshot.isPlaying && snapshot.durationMs > 0L && snapshotAgeMs in 0L..MAX_SNAPSHOT_AGE_MS) {
                        val currentPosMs = WallDriftController.positiveModulo(
                            snapshot.positionMs + snapshotAgeMs,
                            snapshot.durationMs,
                        )
                        val beacon = LanWallBeacon(
                            wallKey = activeWallKey,
                            screenId = activeScreenId,
                            positionMs = currentPosMs,
                            durationMs = snapshot.durationMs,
                            syncOffsetMs = activeSyncOffsetMs,
                            sequence = sequence.incrementAndGet(),
                        )
                        sendBroadcast(sock, beacon.encode())
                    }
                }
                Thread.sleep(BROADCAST_INTERVAL_MS)
            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) {
                if (running.get()) {
                    Log.d(TAG, "LAN UDP broadcast tick skipped: ${e.message}")
                }
                try {
                    Thread.sleep(BROADCAST_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun sendBroadcast(sock: DatagramSocket, payload: ByteArray) {
        val targets = linkedSetOf<InetAddress>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces != null && interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                for (ifaceAddr in iface.interfaceAddresses) {
                    val broadcast = ifaceAddr.broadcast ?: continue
                    if (broadcast is Inet4Address) {
                        targets += broadcast
                    }
                }
            }
        } catch (_: Exception) {
            // Fall back to global limited broadcast below.
        }
        if (targets.isEmpty()) {
            try {
                targets += InetAddress.getByName("255.255.255.255")
            } catch (_: Exception) {
                return
            }
        }
        for (addr in targets) {
            try {
                sock.send(DatagramPacket(payload, payload.size, addr, port))
            } catch (_: Exception) {
                // Ignore individual interface errors.
            }
        }
    }

    companion object {
        private const val TAG = "LanWallSync"
        const val DEFAULT_UDP_PORT = 47_832
        private const val RECEIVE_TIMEOUT_MS = 600
        private const val BROADCAST_INTERVAL_MS = 400L
        private const val MAX_SNAPSHOT_AGE_MS = 1_500L
    }
}
