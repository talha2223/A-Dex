package com.adex.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import com.adex.app.util.ParentalShieldManager
import com.adex.app.util.PermissionHelper
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

// MainActivity provides onboarding, permission setup, and foreground-service control.
class MainActivity : AppCompatActivity() {
    private lateinit var settingsStore: SettingsStore
    private lateinit var statusText: TextView
    private lateinit var deviceIdText: TextView

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
        setContentView(R.layout.activity_main)

        settingsStore = SettingsStore(applicationContext)

        statusText = findViewById(R.id.statusText)
        deviceIdText = findViewById(R.id.deviceIdText)

        val startButton = findViewById<MaterialButton>(R.id.startButton)
        val stopButton = findViewById<MaterialButton>(R.id.stopButton)
        val permissionsButton = findViewById<MaterialButton>(R.id.permissionsButton)

        deviceIdText.text = "Device ID: ${settingsStore.stableDeviceId}"

        startButton.setOnClickListener {
            val intent = Intent(this, ADexForegroundService::class.java).apply {
                action = ServiceActions.ACTION_START
            }
            ContextCompat.startForegroundService(this, intent)
            updateStatusText(ADexForegroundService.lastPairCode)
        }

        stopButton.setOnClickListener {
            val intent = Intent(this, ADexForegroundService::class.java).apply {
                action = ServiceActions.ACTION_STOP
            }
            startService(intent)
            updateStatusText(ADexForegroundService.lastPairCode)
        }

        permissionsButton.setOnClickListener {
            runPermissionSetup()
        }

        updateStatusText(ADexForegroundService.lastPairCode)
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
        maybeEnableShieldAfterPermissions()
        updateStatusText(ADexForegroundService.lastPairCode)
    }

    private fun runPermissionSetup() {
        val missing = PermissionHelper.missingRuntimePermissions(this)
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1001)
        }

        if (!PermissionHelper.hasOverlayPermission(this)) {
            startActivity(PermissionHelper.overlaySettingsIntent(this))
            return
        }

        if (!PermissionHelper.hasUsageStatsPermission(this)) {
            startActivity(PermissionHelper.usageAccessSettingsIntent())
            return
        }

        if (!PermissionHelper.isAccessibilityServiceEnabled(this)) {
            startActivity(PermissionHelper.accessibilitySettingsIntent())
            return
        }

        if (!PermissionHelper.isDeviceAdminEnabled(this)) {
            startActivity(PermissionHelper.deviceAdminSettingsIntent(this))
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notificationPermission = android.Manifest.permission.POST_NOTIFICATIONS
            if (checkSelfPermission(notificationPermission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(notificationPermission), 1002)
            }
        }
    }

    private fun updateStatusText(pairCode: String) {
        val status = if (ADexForegroundService.isServiceRunning) getString(R.string.service_running) else getString(R.string.service_stopped)
        val suffix = if (pairCode.isNotBlank()) " | Pair/Enroll: $pairCode" else " | Pair/Enroll: not fetched yet"
        statusText.text = getString(R.string.service_status, status) + suffix
    }

    private fun allCriticalPermissionsGranted(): Boolean {
        return PermissionHelper.missingRuntimePermissions(this).isEmpty() &&
            PermissionHelper.hasOverlayPermission(this) &&
            PermissionHelper.hasUsageStatsPermission(this) &&
            PermissionHelper.isAccessibilityServiceEnabled(this) &&
            PermissionHelper.isDeviceAdminEnabled(this)
    }

    private fun maybeEnableShieldAfterPermissions() {
        if (!allCriticalPermissionsGranted()) {
            return
        }
        if (settingsStore.shieldEnabled) {
            return
        }

        lifecycleScope.launch {
            runCatching {
                ParentalShieldManager.setShieldEnabled(this@MainActivity, settingsStore, true)
            }
        }
    }
}
