package com.adex.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.adex.app.service.ADexForegroundService
import com.adex.app.service.ServiceActions

// Monitors install/remove events for parental-control audit stream.
class PackageChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val packageName = intent?.data?.schemeSpecificPart ?: return
        val eventType = when (intent.action) {
            Intent.ACTION_PACKAGE_ADDED -> "package_installed"
            Intent.ACTION_PACKAGE_REMOVED -> "package_removed"
            else -> return
        }

        val serviceIntent = Intent(context, ADexForegroundService::class.java).apply {
            action = ServiceActions.ACTION_PACKAGE_EVENT
            putExtra(ServiceActions.EXTRA_EVENT_TYPE, eventType)
            putExtra(ServiceActions.EXTRA_PACKAGE_NAME, packageName)
        }

        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
