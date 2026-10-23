package org.fossify.messages.gateway

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.provider.Telephony
import android.telephony.TelephonyManager
import org.fossify.messages.BuildConfig
import org.fossify.messages.gateway.core.Battery
import org.fossify.messages.gateway.core.Heartbeat
import org.fossify.messages.gateway.core.QueueStats

/** The phone-side facts in a heartbeat (contract 2.7). */
class DeviceStatus(private val context: Context) {

    fun isDefaultSmsApp() = Telephony.Sms.getDefaultSmsPackage(context) == context.packageName

    fun isAirplaneMode() =
        Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0

    /** `wifi`, `cellular` or `none`. Ethernet counts as wifi: it's the "not on the SIM's data" case. */
    fun network(): String {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return NETWORK_NONE
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return NETWORK_NONE
        return when {
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> NETWORK_NONE
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NETWORK_WIFI

            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NETWORK_CELLULAR
            else -> NETWORK_NONE
        }
    }

    /** 0–4 for the gateway's SIM, or null when the platform can't say. */
    fun signal(subscriptionId: Int): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return try {
            val telephony = context.getSystemService(TelephonyManager::class.java) ?: return null
            val forSim = if (subscriptionId >= 0) telephony.createForSubscriptionId(subscriptionId) else telephony
            forSim.signalStrength?.level
        } catch (_: SecurityException) {
            null
        }
    }

    fun battery(): Battery {
        val intent: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) level * PERCENT / scale else 0
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val charging = plugged != 0 ||
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return Battery(percent, charging)
    }

    fun heartbeat(subscriptionId: Int, queue: QueueStats, mmsDownloadFailures: Int, lastError: String?) = Heartbeat(
        appVersion = BuildConfig.VERSION_NAME,
        defaultSmsApp = isDefaultSmsApp(),
        airplaneMode = isAirplaneMode(),
        network = network(),
        signal = signal(subscriptionId),
        battery = battery(),
        queue = queue,
        mmsDownloadFailures = mmsDownloadFailures,
        lastError = lastError,
    )

    /** The SIM's country, used as the default region for E.164. */
    fun simCountry(subscriptionId: Int): String? = try {
        val telephony = context.getSystemService(TelephonyManager::class.java)
        val forSim = if (subscriptionId >= 0) telephony?.createForSubscriptionId(subscriptionId) else telephony
        forSim?.simCountryIso?.takeIf { it.isNotBlank() } ?: forSim?.networkCountryIso?.takeIf { it.isNotBlank() }
    } catch (_: SecurityException) {
        null
    }

    companion object {
        const val NETWORK_WIFI = "wifi"
        const val NETWORK_CELLULAR = "cellular"
        const val NETWORK_NONE = "none"
        private const val PERCENT = 100
    }
}
