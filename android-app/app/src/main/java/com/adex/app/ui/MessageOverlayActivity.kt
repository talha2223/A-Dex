package com.adex.app.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.adex.app.R

// Displays a full-screen remote message for a bounded duration.
class MessageOverlayActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_message_overlay)

        val text = intent.getStringExtra(EXTRA_TEXT) ?: ""
        val seconds = intent.getIntExtra(EXTRA_SECONDS, 8).coerceIn(1, 120)

        findViewById<TextView>(R.id.messageText).text = text

        Handler(Looper.getMainLooper()).postDelayed({ finish() }, seconds * 1000L)
    }

    companion object {
        const val EXTRA_TEXT = "text"
        const val EXTRA_SECONDS = "seconds"
    }
}
