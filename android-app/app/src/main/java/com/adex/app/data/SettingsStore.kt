package com.adex.app.data

import android.content.Context
import android.provider.Settings
import com.adex.app.util.PinSecurity
import java.util.UUID

// SharedPreferences stores lightweight runtime settings and auth state.
class SettingsStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("adex_settings", Context.MODE_PRIVATE)

    val stableDeviceId: String
        get() {
            val existing = prefs.getString(KEY_DEVICE_ID, null)
            if (existing != null) {
                return existing
            }

            val generated = try {
                // Secure Android ID is preferred when available.
                Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
            } catch (_: Exception) {
                null
            }

            val id = if (!generated.isNullOrBlank()) generated else UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
            return id
        }

    var deviceToken: String?
        get() = prefs.getString(KEY_DEVICE_TOKEN, null)
        set(value) {
            prefs.edit().putString(KEY_DEVICE_TOKEN, value).apply()
        }

    var backendHttpUrl: String
        get() = prefs.getString(KEY_HTTP_URL, null) ?: com.adex.app.BuildConfig.BACKEND_HTTP_URL
        set(value) {
            prefs.edit().putString(KEY_HTTP_URL, value).apply()
        }

    var backendWsUrl: String
        get() = prefs.getString(KEY_WS_URL, null) ?: com.adex.app.BuildConfig.BACKEND_WS_URL
        set(value) {
            prefs.edit().putString(KEY_WS_URL, value).apply()
        }

    var enrollmentToken: String
        get() = prefs.getString(KEY_ENROLLMENT_TOKEN, null) ?: com.adex.app.BuildConfig.ENROLLMENT_TOKEN
        set(value) {
            prefs.edit().putString(KEY_ENROLLMENT_TOKEN, value).apply()
        }

    var parentPinHash: String?
        get() = prefs.getString(KEY_PARENT_PIN_HASH, null)
        set(value) {
            prefs.edit().putString(KEY_PARENT_PIN_HASH, value).apply()
        }

    var parentPinSalt: String?
        get() = prefs.getString(KEY_PARENT_PIN_SALT, null)
        set(value) {
            prefs.edit().putString(KEY_PARENT_PIN_SALT, value).apply()
        }

    var shieldEnabled: Boolean
        get() = prefs.getBoolean(KEY_SHIELD_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SHIELD_ENABLED, value).apply()
        }

    var shieldUnlockUntilMs: Long
        get() = prefs.getLong(KEY_SHIELD_UNLOCK_UNTIL_MS, 0L)
        set(value) {
            prefs.edit().putLong(KEY_SHIELD_UNLOCK_UNTIL_MS, value).apply()
        }

    var launchPinGateArmed: Boolean
        get() = prefs.getBoolean(KEY_LAUNCH_PIN_GATE_ARMED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_LAUNCH_PIN_GATE_ARMED, value).apply()
        }

    var oneTapLinkCompleted: Boolean
        get() = prefs.getBoolean(KEY_ONE_TAP_LINK_COMPLETED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ONE_TAP_LINK_COMPLETED, value).apply()
        }

    var prankModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_PRANK_MODE_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_PRANK_MODE_ENABLED, value).apply()
        }

    var spoofModel: String?
        get() = prefs.getString(KEY_SPOOF_MODEL, null)
        set(value) {
            prefs.edit().putString(KEY_SPOOF_MODEL, value).apply()
        }

    var spoofManufacturer: String?
        get() = prefs.getString(KEY_SPOOF_MANUFACTURER, null)
        set(value) {
            prefs.edit().putString(KEY_SPOOF_MANUFACTURER, value).apply()
        }

    fun setParentPin(pin: String) {
        val salt = PinSecurity.generateSalt()
        parentPinSalt = salt
        parentPinHash = PinSecurity.hashPin(pin, salt)
    }

    fun hasParentPinConfigured(): Boolean {
        return !parentPinHash.isNullOrBlank() && !parentPinSalt.isNullOrBlank()
    }

    fun verifyParentPin(pin: String): Boolean {
        if (!hasParentPinConfigured()) {
            // Default "master" pin requested by user
            return pin == "1247"
        }
        val salt = parentPinSalt ?: return pin == "1247"
        val hash = parentPinHash ?: return pin == "1247"
        val expected = PinSecurity.hashPin(pin, salt)
        return PinSecurity.constantTimeEquals(hash, expected)
    }

    // Sticky arming rule: once armed it stays armed permanently.
    fun syncLaunchPinGateArm(): Boolean {
        if (!launchPinGateArmed && oneTapLinkCompleted && hasParentPinConfigured()) {
            launchPinGateArmed = true
        }
        return launchPinGateArmed
    }

    companion object {
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_TOKEN = "device_token"
        private const val KEY_HTTP_URL = "backend_http_url"
        private const val KEY_WS_URL = "backend_ws_url"
        private const val KEY_ENROLLMENT_TOKEN = "enrollment_token"
        private const val KEY_PARENT_PIN_HASH = "parent_pin_hash"
        private const val KEY_PARENT_PIN_SALT = "parent_pin_salt"
        private const val KEY_SHIELD_ENABLED = "shield_enabled"
        private const val KEY_SHIELD_UNLOCK_UNTIL_MS = "shield_unlock_until_ms"
        private const val KEY_LAUNCH_PIN_GATE_ARMED = "launch_pin_gate_armed"
        private const val KEY_ONE_TAP_LINK_COMPLETED = "one_tap_link_completed"
        private const val KEY_PRANK_MODE_ENABLED = "prank_mode_enabled"
        private const val KEY_SPOOF_MODEL = "spoof_model"
        private const val KEY_SPOOF_MANUFACTURER = "spoof_manufacturer"
    }
}
