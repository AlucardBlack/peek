package com.peek.browser.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.telephony.TelephonyManager

/**
 * Check device's network connectivity and speed
 * @author emil http://stackoverflow.com/users/220710/emil
 * via https://gist.github.com/emil2k/5130324
 */
object NetworkConnectivity {

    /**
     * Get the network info
     */
    @JvmStatic
    fun getNetworkInfo(context: Context): NetworkInfo? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.activeNetworkInfo
    }

    /**
     * Check if there is any connectivity
     */
    @JvmStatic
    fun isConnected(context: Context): Boolean {
        val info = getNetworkInfo(context)
        return info != null && info.isConnected
    }

    /**
     * Check if there is any connectivity to a Wifi network
     */
    @JvmStatic
    fun isConnectedWifi(context: Context): Boolean {
        val info = getNetworkInfo(context)
        return info != null && info.isConnected && info.type == ConnectivityManager.TYPE_WIFI
    }

    /**
     * Check if there is any connectivity to a mobile network
     */
    @JvmStatic
    fun isConnectedMobile(context: Context): Boolean {
        val info = getNetworkInfo(context)
        return info != null && info.isConnected && info.type == ConnectivityManager.TYPE_MOBILE
    }

    /**
     * Check if there is fast connectivity
     */
    @JvmStatic
    fun isConnectedFast(context: Context): Boolean {
        val info = getNetworkInfo(context)
        return info != null && info.isConnected && isConnectionFast(info.type, info.subtype)
    }

    /**
     * Check if the connection is fast
     */
    @JvmStatic
    fun isConnectionFast(type: Int, subType: Int): Boolean {
        return if (type == ConnectivityManager.TYPE_WIFI) {
            true
        } else if (type == ConnectivityManager.TYPE_MOBILE) {
            when (subType) {
                TelephonyManager.NETWORK_TYPE_1xRTT -> false // ~ 50-100 kbps
                TelephonyManager.NETWORK_TYPE_CDMA -> false // ~ 14-64 kbps
                TelephonyManager.NETWORK_TYPE_EDGE -> false // ~ 50-100 kbps
                TelephonyManager.NETWORK_TYPE_EVDO_0 -> true // ~ 400-1000 kbps
                TelephonyManager.NETWORK_TYPE_EVDO_A -> true // ~ 600-1400 kbps
                TelephonyManager.NETWORK_TYPE_GPRS -> false // ~ 100 kbps
                TelephonyManager.NETWORK_TYPE_HSDPA -> true // ~ 2-14 Mbps
                TelephonyManager.NETWORK_TYPE_HSPA -> true // ~ 700-1700 kbps
                TelephonyManager.NETWORK_TYPE_HSUPA -> true // ~ 1-23 Mbps
                TelephonyManager.NETWORK_TYPE_UMTS -> true // ~ 400-7000 kbps
                /*
                 * Above API level 7, make sure to set android:targetSdkVersion
                 * to appropriate level to use these
                 */
                TelephonyManager.NETWORK_TYPE_EHRPD -> true // API level 11, ~ 1-2 Mbps
                TelephonyManager.NETWORK_TYPE_EVDO_B -> true // API level 9, ~ 5 Mbps
                TelephonyManager.NETWORK_TYPE_HSPAP -> true // API level 13, ~ 10-20 Mbps
                TelephonyManager.NETWORK_TYPE_IDEN -> false // API level 8, ~25 kbps
                TelephonyManager.NETWORK_TYPE_LTE -> true // API level 11, ~ 10+ Mbps
                TelephonyManager.NETWORK_TYPE_UNKNOWN -> false // Unknown
                else -> false
            }
        } else {
            false
        }
    }
}
