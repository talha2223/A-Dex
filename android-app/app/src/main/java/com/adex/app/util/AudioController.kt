package com.adex.app.util

import android.content.Context
import android.media.AudioManager

object AudioController {
    // Converts user-level percentage into stream volume index for media stream.
    fun setVolumePercent(context: Context, percent: Int) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val safePercent = percent.coerceIn(0, 100)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = (max * (safePercent / 100.0f)).toInt().coerceIn(0, max)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
    }
}
