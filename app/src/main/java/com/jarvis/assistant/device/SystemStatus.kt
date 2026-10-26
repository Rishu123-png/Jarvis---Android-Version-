package com.jarvis.assistant.device

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

/**
 * Read-only device telemetry for the HUD.
 *
 * Phase 1 keeps this deliberately narrow: everything here is available to a normal app
 * with no runtime permission (battery, memory, network transport, uptime, build info).
 * Notification access, usage stats and settings control arrive in later phases.
 */

data class BatteryStatus(
    val percent: Int = 0,
    val isCharging: Boolean = false,
    val statusLabel: String = "UNKNOWN",
    val plugLabel: String = "NONE",
    val temperatureC: Float = 0f,
    val voltageMv: Int = 0,
    val healthLabel: String = "UNKNOWN",
    val technology: String = "—",
)

data class NetworkStatus(
    val connected: Boolean = false,
    val transport: String = "OFFLINE",
    val downstreamKbps: Int = 0,
    val validated: Boolean = false,
)

data class MemoryStatus(
    val usedGb: Float = 0f,
    val totalGb: Float = 0f,
    val percent: Int = 0,
)

data class SystemStatus(
    val battery: BatteryStatus = BatteryStatus(),
    val network: NetworkStatus = NetworkStatus(),
    val memory: MemoryStatus = MemoryStatus(),
    val uptimeMillis: Long = 0L,
) {
    val uptimeLabel: String
        get() {
            val totalSeconds = uptimeMillis / 1000
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            return "${hours}h ${minutes.toString().padStart(2, '0')}m"
        }
}

/** Static build information — never changes while the process lives. */
data class DeviceInfo(
    val model: String,
    val manufacturer: String,
    val androidRelease: String,
    val sdkInt: Int,
    val abi: String,
) {
    val displayName: String
        get() = "${manufacturer.replaceFirstChar { it.uppercase() }} $model"
}

@Composable
fun rememberDeviceInfo(): DeviceInfo = remember {
    DeviceInfo(
        model = Build.MODEL ?: "Unknown",
        manufacturer = Build.MANUFACTURER ?: "Unknown",
        androidRelease = Build.VERSION.RELEASE ?: "?",
        sdkInt = Build.VERSION.SDK_INT,
        abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "—",
    )
}

/**
 * Polls the device once every [refreshMillis]. Polling (rather than a broadcast receiver per
 * subsystem) keeps Phase 1 simple and predictable; the battery intent read is a cheap
 * sticky-broadcast lookup, and it is refreshed again in later phases by a real receiver.
 */
@Composable
fun rememberSystemStatus(refreshMillis: Long = 2_000L): SystemStatus {
    val context = LocalContext.current
    var status by remember { mutableStateOf(SystemStatus()) }

    LaunchedEffect(context) {
        while (true) {
            status = SystemStatus(
                battery = readBattery(context),
                network = readNetwork(context),
                memory = readMemory(context),
                uptimeMillis = SystemClock.elapsedRealtime(),
            )
            delay(refreshMillis)
        }
    }
    return status
}

@Suppress("DEPRECATION")
fun readBattery(context: Context): BatteryStatus {
    val intent: Intent? = try {
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    } catch (e: Exception) {
        null
    }

    if (intent == null) {
        // Fall back to BatteryManager, which is always available.
        val bm = context.getSystemService(BatteryManager::class.java)
        val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0
        return BatteryStatus(percent = level.coerceIn(0, 100), isCharging = bm?.isCharging == true)
    }

    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).takeIf { it > 0 } ?: 100
    val percent = (level * 100f / scale).toInt().coerceIn(0, 100)

    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
        status == BatteryManager.BATTERY_STATUS_FULL

    val statusLabel = when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "CHARGING"
        BatteryManager.BATTERY_STATUS_FULL -> "FULL"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "DISCHARGING"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "IDLE"
        else -> "UNKNOWN"
    }

    val plugLabel = when (intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
        BatteryManager.BATTERY_PLUGGED_AC -> "AC"
        BatteryManager.BATTERY_PLUGGED_USB -> "USB"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "WIRELESS"
        BatteryManager.BATTERY_PLUGGED_DOCK -> "DOCK"
        else -> "NONE"
    }

    val healthLabel = when (intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "GOOD"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "OVERHEAT"
        BatteryManager.BATTERY_HEALTH_DEAD -> "CRITICAL"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "OVER-VOLT"
        BatteryManager.BATTERY_HEALTH_COLD -> "COLD"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "FAULT"
        else -> "UNKNOWN"
    }

    return BatteryStatus(
        percent = percent,
        isCharging = charging,
        statusLabel = statusLabel,
        plugLabel = plugLabel,
        temperatureC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f,
        voltageMv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0),
        healthLabel = healthLabel,
        technology = intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "—",
    )
}

fun readNetwork(context: Context): NetworkStatus {
    val cm = context.getSystemService(ConnectivityManager::class.java)
        ?: return NetworkStatus()
    val network = cm.activeNetwork ?: return NetworkStatus()
    val caps: NetworkCapabilities = cm.getNetworkCapabilities(network) ?: return NetworkStatus()

    val transport = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WI-FI"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
        else -> "LINKED"
    }

    return NetworkStatus(
        connected = true,
        transport = transport,
        downstreamKbps = caps.linkDownstreamBandwidthKbps,
        validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
    )
}

fun readMemory(context: Context): MemoryStatus {
    val activityManager = context.getSystemService(ActivityManager::class.java)
        ?: return MemoryStatus()
    val info = ActivityManager.MemoryInfo()
    activityManager.getMemoryInfo(info)

    val gb = 1024f * 1024f * 1024f
    val totalGb = info.totalMem / gb
    val usedGb = (info.totalMem - info.availMem) / gb
    val percent = if (info.totalMem > 0L) {
        ((info.totalMem - info.availMem) * 100.0 / info.totalMem).toInt()
    } else {
        0
    }
    return MemoryStatus(usedGb = usedGb, totalGb = totalGb, percent = percent)
}
