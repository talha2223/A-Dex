package com.adex.app.util

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import com.adex.app.data.SettingsStore

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

object DeviceInfoProvider {
    // Collects lightweight device telemetry for !info responses.
    fun collect(context: Context, settings: SettingsStore): Map<String, Any> {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)

        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val battery = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

        val manufacturer = settings.spoofManufacturer ?: Build.MANUFACTURER
        val model = settings.spoofModel ?: Build.MODEL

        // Get Camera IDs
        val cameras = mutableListOf<String>()
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            for (id in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val facing = when (characteristics.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "front"
                    CameraCharacteristics.LENS_FACING_BACK -> "back"
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
                    else -> "unknown"
                }
                cameras.add("$id ($facing)")
            }
        } catch (_: Exception) {}

        return mapOf(
            "manufacturer" to manufacturer,
            "model" to model,
            "androidVersion" to Build.VERSION.RELEASE,
            "sdkInt" to Build.VERSION.SDK_INT,
            "batteryPercent" to battery,
            "totalRamMb" to (memoryInfo.totalMem / (1024 * 1024)),
            "availableRamMb" to (memoryInfo.availMem / (1024 * 1024)),
            "cameras" to cameras
        )
    }
}

