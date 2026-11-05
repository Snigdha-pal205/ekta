package com.ekta.sos

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Offline mesh. Uses Google Nearby Connections, which talks phone-to-phone over
 * Bluetooth, BLE and Wi-Fi (Direct / hotspot) with no internet or SIM signal.
 *
 *  - Every phone with Ekta is always *advertising*, so it can be reached.
 *  - A phone that holds an alert to pass on also *discovers*: whenever it sees another
 *    Ekta phone it connects, hands over what that phone doesn't have yet, and hangs up.
 *  - Phones that receive an alert hold it too, so it spreads hop by hop
 *    (up to [MAX_HOPS], for [RELAY_WINDOW_MS]).
 */
object MeshManager {
    private const val SERVICE_ID = "com.ekta.sos.mesh"
    /** New runtime permission on Android 17+; Nearby refuses to start without it. */
    const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
    const val MAX_HOPS = 8
    private const val RELAY_WINDOW_MS = 30 * 60 * 1000L
    private const val TICK_MS = 15_000L
    private const val HANG_UP_AFTER_MS = 6_000L

    fun interface Listener {
        /** A message we haven't seen before arrived from a nearby phone. */
        fun onMessage(msg: MeshMessage)
    }

    private lateinit var app: Context
    private val client: ConnectionsClient by lazy { Nearby.getConnectionsClient(app) }
    private val handler = Handler(Looper.getMainLooper())
    private var listener: Listener? = null
    private var myName = ""
    private var advertising = false
    private var discovering = false

    private class Held(val msg: MeshMessage, val since: Long)

    private val held = LinkedHashMap<String, Held>()          // key -> message we pass on
    private val seen = HashSet<String>()                        // keys already handled
    private val visible = HashMap<String, String>()            // endpointId -> peer name
    private val names = HashMap<String, String>()              // endpointId -> peer name (connected)
    private val peerHas = HashMap<String, MutableSet<String>>() // peer name -> keys it has
    private val connecting = HashSet<String>()

    /** For the SOS screen: how many nearby phones got each of *my* alerts directly. */
    private val _reached = MutableStateFlow<Map<String, Int>>(emptyMap())
    val reached: StateFlow<Map<String, Int>> = _reached

    fun hasPermissions(context: Context): Boolean {
        fun ok(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        if (!ok(Manifest.permission.ACCESS_FINE_LOCATION)) return false
        if (Build.VERSION.SDK_INT >= 31 &&
            !(ok(Manifest.permission.BLUETOOTH_SCAN) && ok(Manifest.permission.BLUETOOTH_ADVERTISE) &&
                ok(Manifest.permission.BLUETOOTH_CONNECT))
        ) return false
        if (Build.VERSION.SDK_INT >= 33 && !ok(Manifest.permission.NEARBY_WIFI_DEVICES)) return false
        if (Build.VERSION.SDK_INT >= 37 && !ok(ACCESS_LOCAL_NETWORK)) return false
        return true
    }

    /** Start being reachable. Called by the always-on listener service. */
    fun start(context: Context, listener: Listener) {
        app = context.applicationContext
        this.listener = listener
        myName = SosRepository.deviceId(app).take(12)
        if (!hasPermissions(app)) return
        startAdvertising()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    /** Put a message of our own (an SOS we pressed, or an ACK as gateway) into the mesh. */
    fun originate(context: Context, msg: MeshMessage) {
        if (!::app.isInitialized) app = context.applicationContext
        if (myName.isEmpty()) myName = SosRepository.deviceId(app).take(12)
        handler.post {
            seen += msg.key
            hold(msg)
        }
    }

    /** Stop passing on a message (e.g. an SOS that was cancelled). */
    fun drop(key: String) = handler.post { held.remove(key) }

    // ---------- holding & pushing ----------

    private fun hold(msg: MeshMessage) {
        held[msg.key] = Held(msg, System.currentTimeMillis())
        if (hasPermissions(app)) {
            startAdvertising()
            startDiscovery()
        }
        pushToVisible()
    }

    private val tick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            held.entries.removeAll { now - it.value.since > RELAY_WINDOW_MS }
            if (held.isEmpty()) stopDiscovery() else if (hasPermissions(app)) startDiscovery()
            if (hasPermissions(app)) startAdvertising()
            pushToVisible() // retries peers we failed to reach last time
            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun missingFor(peer: String): List<MeshMessage> {
        val has = peerHas[peer].orEmpty()
        return held.values.map { it.msg }.filter { it.key !in has }
    }

    private fun pushToVisible() {
        visible.forEach { (endpointId, peer) ->
            if (missingFor(peer).isNotEmpty()) connect(endpointId)
        }
    }

    private fun connect(endpointId: String) {
        if (!connecting.add(endpointId)) return
        client.requestConnection(myName, endpointId, lifecycle)
            .addOnFailureListener { connecting.remove(endpointId) }
        handler.postDelayed({ connecting.remove(endpointId) }, HANG_UP_AFTER_MS * 3)
    }

    private fun sendWhatTheyLack(endpointId: String) {
        val peer = names[endpointId] ?: visible[endpointId] ?: endpointId
        val toSend = missingFor(peer)
        if (toSend.isEmpty()) return
        client.sendPayload(endpointId, Payload.fromBytes(MeshMessage.encode(toSend)))
            .addOnSuccessListener {
                peerHas.getOrPut(peer) { mutableSetOf() } += toSend.map { it.key }
                countReached(peer, toSend)
            }
    }

    private fun countReached(peer: String, sent: List<MeshMessage>) {
        val mine = SosRepository.deviceId(app)
        val updated = _reached.value.toMutableMap()
        sent.filter { it.type == MeshMessage.TYPE_SOS && it.sender == mine && it.hops == 0 }.forEach {
            val peers = reachedPeers.getOrPut(it.id) { mutableSetOf() }
            peers += peer
            updated[it.id] = peers.size
        }
        _reached.value = updated
    }

    private val reachedPeers = HashMap<String, MutableSet<String>>()

    // ---------- receiving ----------

    /** Debug/test hook: behave as if [bytes] just arrived from a nearby phone. */
    fun injectForTest(bytes: ByteArray) = handler.post { receive("test-peer", bytes) }

    private fun receive(endpointId: String, bytes: ByteArray) {
        val peer = names[endpointId] ?: endpointId
        for (msg in MeshMessage.decode(bytes)) {
            peerHas.getOrPut(peer) { mutableSetOf() } += msg.key // they obviously have it
            if (!seen.add(msg.key)) continue
            listener?.onMessage(msg)
            if (msg.hops < MAX_HOPS) hold(msg.copy(hops = msg.hops + 1))
        }
        if (endpointId != "test-peer") sendWhatTheyLack(endpointId) // and give them anything they're missing
    }

    // ---------- Nearby Connections plumbing ----------

    private fun startAdvertising() {
        if (advertising) return
        advertising = true
        client.startAdvertising(
            myName, SERVICE_ID, lifecycle,
            AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build(),
        ).addOnFailureListener { advertising = false }
    }

    private fun startDiscovery() {
        if (discovering) return
        discovering = true
        client.startDiscovery(
            SERVICE_ID, discovery,
            DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build(),
        ).addOnFailureListener { discovering = false }
    }

    private fun stopDiscovery() {
        if (!discovering) return
        discovering = false
        client.stopDiscovery()
        visible.clear()
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.serviceId != SERVICE_ID) return
            visible[endpointId] = info.endpointName
            if (missingFor(info.endpointName).isNotEmpty()) connect(endpointId)
        }

        override fun onEndpointLost(endpointId: String) {
            visible.remove(endpointId)
        }
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            names[endpointId] = info.endpointName
            // Everyone running Ekta is welcome: accept automatically.
            client.acceptConnection(endpointId, payloads)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (!result.status.isSuccess) {
                connecting.remove(endpointId)
                return
            }
            sendWhatTheyLack(endpointId)
            handler.postDelayed({
                client.disconnectFromEndpoint(endpointId)
                connecting.remove(endpointId)
            }, HANG_UP_AFTER_MS)
        }

        override fun onDisconnected(endpointId: String) {
            connecting.remove(endpointId)
            names.remove(endpointId)
        }
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            handler.post { receive(endpointId, bytes) }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }
}
