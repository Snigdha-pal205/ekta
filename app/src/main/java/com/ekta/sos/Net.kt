package com.ekta.sos

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Whether this phone currently has working internet (Wi-Fi or mobile data). */
object Net {
    private val _online = MutableStateFlow(false)
    val online: StateFlow<Boolean> = _online
    private var started = false
    private var cm: ConnectivityManager? = null
    private val listeners = mutableListOf<() -> Unit>()

    fun init(context: Context) {
        if (started) return
        started = true
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        this.cm = cm
        _online.value = isOnline(cm)
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = update(cm)
            override fun onAvailable(network: Network) = update(cm)
            // Right after a loss Android can still report the old network as active,
            // so trust the "lost" event and say offline now; refresh() corrects it later.
            override fun onLost(network: Network) {
                _online.value = false
            }
        })
    }

    /** Called (on a background thread) every time we come online. */
    fun onOnline(block: () -> Unit) {
        listeners += block
    }

    /** Re-read the real state (called every second while an SOS is active). */
    fun refresh() {
        cm?.let(::update)
    }

    private fun update(cm: ConnectivityManager) {
        val now = isOnline(cm)
        val was = _online.value
        _online.value = now
        if (now && !was) listeners.forEach { it() }
    }

    private fun isOnline(cm: ConnectivityManager): Boolean {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
