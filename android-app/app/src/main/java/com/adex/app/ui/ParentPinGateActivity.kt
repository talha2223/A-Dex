package com.adex.app.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import com.adex.app.R
import com.adex.app.data.SettingsStore
import com.adex.app.util.ParentalShieldManager
import com.adex.app.util.PinSecurity

// Launcher gate shown after one-tap link + parent PIN arming conditions are met.
class ParentPinGateActivity : AppCompatActivity() {
    private lateinit var settingsStore: SettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        settingsStore = SettingsStore(applicationContext)
        settingsStore.syncLaunchPinGateArm()
        if (!settingsStore.launchPinGateArmed) {
            finish()
            return
        }

        setContentView(R.layout.activity_parent_pin_gate)

        val infoText = findViewById<TextView>(R.id.gateInfoText)
        val pinInput = findViewById<EditText>(R.id.gatePinInput)
        val verifyButton = findViewById<Button>(R.id.gateVerifyButton)
        val errorText = findViewById<TextView>(R.id.gateErrorText)

        if (!settingsStore.hasParentPinConfigured()) {
            infoText.visibility = View.VISIBLE
            infoText.text = getString(R.string.parent_pin_gate_no_pin_info)
        } else {
            infoText.visibility = View.GONE
        }

        onBackPressedDispatcher.addCallback(this) {
            closeAppTask()
        }

        verifyButton.setOnClickListener {
            val pin = pinInput.text?.toString()?.trim().orEmpty()
            val error = validatePin(pin)
            if (error != null) {
                errorText.text = error
                errorText.visibility = View.VISIBLE
                pinInput.setText("")
                return@setOnClickListener
            }

            // Unlock shield package window, then keep app UI closed per strict gate rules.
            ParentalShieldManager.unlockTemporarily(settingsStore)
            closeAppTask()
        }
    }

    private fun validatePin(pin: String): String? {
        if (!settingsStore.hasParentPinConfigured()) {
            return getString(R.string.parent_pin_gate_no_pin_info)
        }
        if (!PinSecurity.isValidPin(pin)) {
            return getString(R.string.pin_format_error)
        }
        if (!settingsStore.verifyParentPin(pin)) {
            return getString(R.string.pin_invalid_error)
        }
        return null
    }

    private fun closeAppTask() {
        moveTaskToBack(true)
        finishAffinity()
    }
}
