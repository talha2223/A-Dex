package com.adex.app.ui

import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import com.adex.app.R

// Shows downloaded image content in fullscreen for remote !show command.
class ShowImageActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_show_image)

        val path = intent.getStringExtra(EXTRA_PATH)
        val seconds = intent.getIntExtra(EXTRA_SECONDS, 10).coerceIn(1, 120)

        val view = findViewById<ImageView>(R.id.showImageView)
        if (!path.isNullOrBlank()) {
            val bitmap = BitmapFactory.decodeFile(path)
            view.setImageBitmap(bitmap)
        }

        Handler(Looper.getMainLooper()).postDelayed({ finish() }, seconds * 1000L)
    }

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_SECONDS = "seconds"
    }
}
