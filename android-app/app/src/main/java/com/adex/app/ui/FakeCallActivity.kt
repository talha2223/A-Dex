package com.adex.app.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.adex.app.R

class FakeCallActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val closeRunnable = Runnable { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fake_call)

        val callerName = intent.getStringExtra(EXTRA_CALLER_NAME).orEmpty().ifBlank { "Unknown Caller" }
        val subtitle = intent.getStringExtra(EXTRA_SUBTITLE).orEmpty().ifBlank { "Incoming call" }
        val autoDismissSeconds = intent.getIntExtra(EXTRA_AUTO_DISMISS_SECONDS, 20).coerceIn(5, 120)

        findViewById<TextView>(R.id.fakeCallCallerName).text = callerName
        findViewById<TextView>(R.id.fakeCallSubtitle).text = subtitle

        findViewById<Button>(R.id.fakeCallDecline).setOnClickListener { finish() }
        findViewById<Button>(R.id.fakeCallAnswer).setOnClickListener { finish() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* block back button */ }
        })

        handler.postDelayed(closeRunnable, autoDismissSeconds * 1000L)
    }

    override fun onDestroy() {
        handler.removeCallbacks(closeRunnable)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CALLER_NAME = "caller_name"
        const val EXTRA_SUBTITLE = "subtitle"
        const val EXTRA_AUTO_DISMISS_SECONDS = "auto_dismiss_seconds"
    }
}
