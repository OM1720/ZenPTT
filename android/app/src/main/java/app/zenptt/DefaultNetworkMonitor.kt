// Converts Android default-network availability into an immediate reconnect hint.
package app.zenptt

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network

internal class DefaultNetworkMonitor(context: Context) : NetworkMonitor {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val lock = Any()
    private var callback: ConnectivityManager.NetworkCallback? = null

    override fun start(onAvailable: () -> Unit) {
        synchronized(lock) {
            if (callback != null) return
            val created = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = onAvailable()
            }
            callback = created
            connectivity.registerDefaultNetworkCallback(created)
        }
    }

    override fun stop() {
        val current = synchronized(lock) { callback.also { callback = null } } ?: return
        runCatching { connectivity.unregisterNetworkCallback(current) }
    }
}
