package com.jm.sillydroid.feature.main.ui.home.bridge

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.telephony.TelephonyManager
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Android 宿主系统状态快照采集器。
 *
 * 只允许读取不含设备唯一标识的即时状态（电池、网络、通知和屏幕电源状态）；不允许申请
 * 运行时权限、访问通知正文/联系人/定位等用户内容，也不负责缓存或推送状态变化。
 */
class AndroidSystemInfoCollector(context: Context) {
    private val appContext = context.applicationContext
    private val batteryManager by lazy { appContext.getSystemService(BatteryManager::class.java) }
    private val connectivityManager by lazy { appContext.getSystemService(ConnectivityManager::class.java) }
    private val notificationManager by lazy { appContext.getSystemService(NotificationManager::class.java) }
    private val powerManager by lazy { appContext.getSystemService(PowerManager::class.java) }
    private val telephonyManager by lazy { appContext.getSystemService(TelephonyManager::class.java) }

    /** 读取并序列化一次系统状态快照；单个系统服务异常只影响对应字段。 */
    fun getSnapshotJson(): String {
        val snapshot = JSONObject()
        snapshot.put("capturedAtEpochMillis", System.currentTimeMillis())
        snapshot.put("apiLevel", Build.VERSION.SDK_INT)
        snapshot.put("battery", readBattery())
        snapshot.put("network", readNetwork())
        snapshot.put("telephony", readTelephony())
        snapshot.put("notifications", readNotifications())
        snapshot.put("power", readPower())
        return snapshot.toString()
    }

    /** 读取电池百分比、充电状态和电源类型。 */
    private fun readBattery(): JSONObject {
        val battery = JSONObject()
        val intent = runCatching {
            appContext.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val capacity = if (level >= 0 && scale > 0) {
            (level * 100 / scale).coerceIn(0, 100)
        } else {
            runCatching { batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1 }.getOrDefault(-1)
        }
        battery.put("levelPercent", capacity.takeIf { it in 0..100 } ?: JSONObject.NULL)
        battery.put("charging", runCatching { batteryManager?.isCharging }.getOrNull() ?: JSONObject.NULL)
        battery.put("status", batteryStatusName(intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1))
        battery.put("plug", batteryPlugName(intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0))
        return battery
    }

    /** 读取当前活动网络的传输类型、验证状态和 bearer 信号值。 */
    private fun readNetwork(): JSONObject {
        val network = JSONObject()
        val activeNetwork = runCatching { connectivityManager?.activeNetwork }.getOrNull()
        val capabilities = runCatching { activeNetwork?.let(connectivityManager::getNetworkCapabilities) }.getOrNull()
        val transports = JSONArray()
        if (capabilities != null) {
            val transportNames = listOf(
                NetworkCapabilities.TRANSPORT_WIFI to "wifi",
                NetworkCapabilities.TRANSPORT_CELLULAR to "cellular",
                NetworkCapabilities.TRANSPORT_VPN to "vpn",
                NetworkCapabilities.TRANSPORT_ETHERNET to "ethernet",
                NetworkCapabilities.TRANSPORT_BLUETOOTH to "bluetooth",
                NetworkCapabilities.TRANSPORT_USB to "usb",
                NetworkCapabilities.TRANSPORT_LOWPAN to "lowpan",
                NetworkCapabilities.TRANSPORT_WIFI_AWARE to "wifiAware",
            )
            transportNames.filter { capabilities.hasTransport(it.first) }.forEach { transports.put(it.second) }
            network.put("validated", capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            network.put("metered", !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
            val signal = capabilities.signalStrength
            network.put("signalStrength", signal.takeUnless { it == NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED } ?: JSONObject.NULL)
        } else {
            network.put("validated", false)
            network.put("metered", JSONObject.NULL)
            network.put("signalStrength", JSONObject.NULL)
        }
        network.put("connected", capabilities != null)
        network.put("transports", transports)
        network.put("wifiConnected", capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true)
        network.put("cellularConnected", capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true)
        return network
    }

    /** 读取本应用可见的活动通知数量和通知开关；系统全局通知需通知监听服务权限，故明确标记不可用。 */
    private fun readNotifications(): JSONObject {
        val notifications = JSONObject()
        notifications.put("activeAppCount", runCatching { notificationManager?.activeNotifications?.size ?: 0 }.getOrDefault(0))
        notifications.put("appNotificationsEnabled", runCatching { NotificationManagerCompat.from(appContext).areNotificationsEnabled() }.getOrNull() ?: JSONObject.NULL)
        notifications.put("globalCountAvailable", false)
        notifications.put("globalActiveCount", JSONObject.NULL)
        return notifications
    }

    /** 读取蜂窝网络信号等级；无电话能力或系统权限限制时返回不可用字段。 */
    private fun readTelephony(): JSONObject {
        val telephony = JSONObject()
        val signal = runCatching { telephonyManager?.signalStrength }.getOrNull()
        telephony.put("available", signal != null)
        telephony.put("level", signal?.level ?: JSONObject.NULL)
        return telephony
    }

    /** 读取屏幕交互和系统省电模式。 */
    private fun readPower(): JSONObject {
        val power = JSONObject()
        power.put("interactive", runCatching { powerManager?.isInteractive }.getOrNull() ?: JSONObject.NULL)
        power.put("powerSaveMode", runCatching { powerManager?.isPowerSaveMode }.getOrNull() ?: JSONObject.NULL)
        return power
    }

    /** 将 Android 电池状态常量转为稳定的页面字符串。 */
    private fun batteryStatusName(status: Int): String = when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
        BatteryManager.BATTERY_STATUS_FULL -> "full"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "notCharging"
        else -> "unknown"
    }

    /** 将 Android 充电来源常量转为稳定的页面字符串。 */
    private fun batteryPlugName(plug: Int): String = when (plug) {
        BatteryManager.BATTERY_PLUGGED_USB -> "usb"
        BatteryManager.BATTERY_PLUGGED_AC -> "ac"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
        else -> "none"
    }
}
