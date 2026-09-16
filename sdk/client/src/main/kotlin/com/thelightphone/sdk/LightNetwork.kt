package com.thelightphone.sdk

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * SDK connectivity signal. Tools can't reach [ConnectivityManager] from inside
 * the sandbox, so the SDK watches the default network and publishes whether the
 * device currently has usable internet as [online].
 *
 * `false` covers both "no connection" and airplane mode (no validated network).
 * Requires the app to hold `ACCESS_NETWORK_STATE`, declared via lighttool.toml.
 */
object LightNetwork {

    private val _online = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online.asStateFlow()

    /** Wired up by [LightSdkApplication] at process start; tools never call this. */
    internal fun attach(context: Context) {
        val cm = context.applicationContext
            .getSystemService(ConnectivityManager::class.java) ?: return
        _online.value = hasInternet(cm)
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    _online.value = hasInternet(cm)
                }

                override fun onLost(network: Network) {
                    _online.value = false
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities,
                ) {
                    _online.value = capabilities.isUsableInternet()
                }
            })
        }
    }

    private fun hasInternet(cm: ConnectivityManager): Boolean {
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        return caps.isUsableInternet()
    }

    private fun NetworkCapabilities.isUsableInternet(): Boolean =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
