package com.adex.app.service

// Internal intent actions keep receivers and service loosely coupled.
object ServiceActions {
    const val ACTION_START = "com.adex.app.action.START"
    const val ACTION_STOP = "com.adex.app.action.STOP"
    const val ACTION_PACKAGE_EVENT = "com.adex.app.action.PACKAGE_EVENT"
    const val EXTRA_EVENT_TYPE = "event_type"
    const val EXTRA_PACKAGE_NAME = "package_name"
}
