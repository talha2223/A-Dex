package com.adex.app.util

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build

object DeviceInfoProvider {
    // Collects lightweight device telemetry for !info responses.
    fun collect(context: Context): Map<String, Any> {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)

        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val battery = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

        return mapOf(
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "androidVersion" to Build.VERSION.RELEASE,
            "sdkInt" to Build.VERSION.SDK_INT,
            "batteryPercent" to battery,
            "totalRamMb" to (memoryInfo.totalMem / (1024 * 1024)),
            "availableRamMb" to (memoryInfo.availMem / (1024 * 1024))
        )
    }
}
