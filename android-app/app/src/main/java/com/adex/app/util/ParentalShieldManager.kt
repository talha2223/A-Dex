package com.adex.app.util

import android.content.Context
import com.adex.app.ADexApplication
import com.adex.app.data.LockedAppEntity
import com.adex.app.data.SettingsStore
import com.adex.app.service.AppMonitorAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ParentalShieldManager {
    const val UNLOCK_WINDOW_MS: Long = 5 * 60 * 1000L

    val protectedPackages: Set<String> = setOf(
        // Android Settings variants
        "com.android.settings",
        "com.samsung.android.settings",

        // Package installer / permission controller variants
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.samsung.android.packageinstaller",
        "com.miui.packageinstaller",
        "com.miui.securitycenter",

        // Play Store uninstall flow entrypoint on many devices
        "com.android.vending"
    )

    suspend fun setShieldEnabled(context: Context, settingsStore: SettingsStore, enabled: Boolean): ShieldState {
        return withContext(Dispatchers.IO) {
            val db = (context.applicationContext as ADexApplication).db
            val dao = db.lockedAppDao()

            if (enabled) {
                protectedPackages.forEach { packageName ->
                    dao.insert(LockedAppEntity(settingsStore.stableDeviceId, packageName, System.currentTimeMillis()))
                }
                settingsStore.shieldEnabled = true
            } else {
                protectedPackages.forEach { packageName ->
                    dao.remove(settingsStore.stableDeviceId, packageName)
                }
                settingsStore.shieldEnabled = false
                relock(settingsStore)
            }

            val locked = dao.getLockedPackages(settingsStore.stableDeviceId)
            AppMonitorAccessibilityService.updateLockedPackages(locked)
            status(settingsStore)
        }
    }

    fun unlockTemporarily(settingsStore: SettingsStore, nowMs: Long = System.currentTimeMillis()): Long {
        val until = nowMs + UNLOCK_WINDOW_MS
        settingsStore.shieldUnlockUntilMs = until
        return until
    }

    fun relock(settingsStore: SettingsStore) {
        settingsStore.shieldUnlockUntilMs = 0L
    }

    fun isTemporarilyUnlocked(settingsStore: SettingsStore, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!settingsStore.shieldEnabled) {
            return false
        }
        return settingsStore.shieldUnlockUntilMs > nowMs
    }

    fun isShieldProtectedPackage(packageName: String): Boolean {
        return protectedPackages.contains(packageName)
    }

    fun status(settingsStore: SettingsStore, nowMs: Long = System.currentTimeMillis()): ShieldState {
        return ShieldState(
            enabled = settingsStore.shieldEnabled,
            pinConfigured = settingsStore.hasParentPinConfigured(),
            unlockUntilMs = settingsStore.shieldUnlockUntilMs,
            temporarilyUnlocked = isTemporarilyUnlocked(settingsStore, nowMs),
            protectedPackages = protectedPackages.toList().sorted()
        )
    }
}

data class ShieldState(
    val enabled: Boolean,
    val pinConfigured: Boolean,
    val unlockUntilMs: Long,
    val temporarilyUnlocked: Boolean,
    val protectedPackages: List<String>
)
