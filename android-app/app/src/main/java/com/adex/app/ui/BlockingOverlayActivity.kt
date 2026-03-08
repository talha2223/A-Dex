package com.adex.app.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import com.adex.app.R
import com.adex.app.data.SettingsStore
import com.adex.app.util.ParentalShieldManager
import com.adex.app.util.PinSecurity

// Full-screen blocker shown when a locked app is opened.
class BlockingOverlayActivity : AppCompatActivity() {
    private lateinit var settingsStore: SettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_blocking_overlay)

        settingsStore = SettingsStore(applicationContext)

        val blockedPackage = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: "unknown"
        val pinRequired = intent.getBooleanExtra(EXTRA_PIN_REQUIRED, false)

        findViewById<TextView>(R.id.blockedPackageText).text = "Blocked package: $blockedPackage"
        val messageText = findViewById<TextView>(R.id.blockMessageText)
        val pinSection = findViewById<LinearLayout>(R.id.pinSection)
        val pinInput = findViewById<EditText>(R.id.parentPinInput)
        val unlockButton = findViewById<Button>(R.id.unlockButton)
        val pinError = findViewById<TextView>(R.id.pinErrorText)

        onBackPressedDispatcher.addCallback(this) {
            // Keep blocking screen visible until child exits target app context.
        }

        if (!pinRequired) {
            messageText.text = getString(R.string.blocking_simple_message)
            pinSection.visibility = View.GONE
            return
        }

        messageText.text = getString(R.string.blocking_pin_required)
        unlockButton.setOnClickListener {
            val pin = pinInput.text?.toString()?.trim().orEmpty()
            val errorMessage = validatePin(pin)
            if (errorMessage != null) {
                pinError.text = errorMessage
                pinError.visibility = View.VISIBLE
                pinInput.setText("")
                return@setOnClickListener
            }

            ParentalShieldManager.unlockTemporarily(settingsStore)
            pinError.visibility = View.GONE
            launchBlockedPackage(blockedPackage)
            finish()
        }
    }

    private fun validatePin(pin: String): String? {
        if (!settingsStore.hasParentPinConfigured()) {
            return getString(R.string.pin_missing_config)
        }
        if (!PinSecurity.isValidPin(pin)) {
            return getString(R.string.pin_format_error)
        }
        if (!settingsStore.verifyParentPin(pin)) {
            return getString(R.string.pin_invalid_error)
        }
        return null
    }

    private fun launchBlockedPackage(packageName: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launchIntent)
    }

    companion object {
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_PIN_REQUIRED = "pin_required"
    }
}
