package com.itwingtech.itwingsdk.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

internal object NetworkState {
    fun isOnline(context: Context?): Boolean {
        context ?: return false
        return runCatching {
            val manager =
                context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                    ?: return false
            val network =
                manager.activeNetwork
                    ?: return false
            val capabilities =
                manager.getNetworkCapabilities(network)
                    ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                (
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    )
        }.getOrDefault(false)
    }

    fun offlineMessage(): String =
        "No internet connection is available. Please connect and try again."
}
