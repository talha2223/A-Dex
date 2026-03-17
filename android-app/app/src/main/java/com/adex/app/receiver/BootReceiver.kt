package com.adex.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.adex.app.util.PersistenceWorker
import com.adex.app.service.ADexForegroundService
import com.adex.app.service.ServiceActions

// Boot receiver ensures Pakistani Guitar Store reconnects automatically after reboot.
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            PersistenceWorker.schedule(context)
            val serviceIntent = Intent(context, ADexForegroundService::class.java).apply {
                this.action = ServiceActions.ACTION_START
            }
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }
}
