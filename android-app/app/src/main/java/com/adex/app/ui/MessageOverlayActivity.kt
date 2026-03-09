package com.adex.app.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.adex.app.R

// Displays a full-screen remote message or jump scare for a bounded duration.
class MessageOverlayActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_message_overlay)

        val text = intent.getStringExtra(EXTRA_TEXT) ?: ""
        val seconds = intent.getIntExtra(EXTRA_SECONDS, 8).coerceIn(1, 120)
        val imageRes = intent.getIntExtra(EXTRA_IMAGE_RES, 0)

        findViewById<TextView>(R.id.messageText).text = text
        if (imageRes != 0) {
            val iv = findViewById<ImageView>(R.id.scaryImage)
            iv.setImageResource(imageRes)
            iv.visibility = android.view.View.VISIBLE
        }

        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        )

        // Consume back presses so the overlay cannot be dismissed
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* block back */ }
        })

        Handler(Looper.getMainLooper()).postDelayed({ finish() }, seconds * 1000L)
    }

    companion object {
        const val EXTRA_TEXT = "text"
        const val EXTRA_SECONDS = "seconds"
        const val EXTRA_IMAGE_RES = "image_res"
    }
}
