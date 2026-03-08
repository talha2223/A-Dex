package com.adex.app.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

// DeviceAdminReceiver enables lockNow command when user grants device admin rights.
class ADexDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
    }
}
