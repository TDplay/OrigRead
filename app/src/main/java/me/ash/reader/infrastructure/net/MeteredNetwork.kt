package me.ash.reader.infrastructure.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * 当前默认网络是否为计费网络（移动数据 / 收费热点）。
 *
 * 取 activeNetwork 而不是“是否存在移动网络”，这样连上 WiFi 后即使移动数据仍开着也不会被误判。
 */
fun Context.isMeteredNetwork(): Boolean {
    val manager = getSystemService(ConnectivityManager::class.java) ?: return false
    val network = manager.activeNetwork ?: return false
    val capabilities = manager.getNetworkCapabilities(network) ?: return false
    if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
    return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
}
