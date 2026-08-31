package com.carl.glucobro

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.roundToInt

/**
 * Plays glucose alerts on the ALARM audio stream so they are not muted by the
 * phone's normal ring/vibrate/silent setting. The previous alarm-stream volume
 * is restored when playback finishes.
 */
class GlucoseAlarmPlayer(private val context: Context) {

    private var player: MediaPlayer? = null
    private var previousAlarmVolume: Int? = null

    fun playUrgentLow(volumePercent: Int = 100) {
        stopSound()
        vibrate(longArrayOf(0, 900, 250, 900, 250, 900, 250, 1400))
        playRaw(R.raw.urgent_low_alarm, volumePercent)
    }

    fun playLowOrHigh(volumePercent: Int = 100) {
        stopSound()
        // Three long pulses, timed to the three beeps in glucose_alert.wav.
        vibrate(longArrayOf(0, 800, 350, 800, 350, 800))
        playRaw(R.raw.glucose_alert, volumePercent)
    }

    fun stop() {
        stopSound()
        vibrator()?.cancel()
    }

    private fun playRaw(resId: Int, volumePercent: Int) {
        val audioManager = context.getSystemService(AudioManager::class.java)
        try {
            previousAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val requested = ((max * volumePercent.coerceIn(20, 100)) / 100.0).roundToInt().coerceIn(1, max)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, requested, 0)
        } catch (_: Exception) {
            previousAlarmVolume = null
        }

        val uri = Uri.parse("android.resource://${context.packageName}/$resId")
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        player = MediaPlayer().apply {
            setAudioAttributes(audioAttributes)
            setDataSource(context, uri)
            isLooping = false
            setOnCompletionListener {
                restoreAlarmVolume()
                it.release()
                if (player === it) player = null
            }
            setOnErrorListener { mp, _, _ ->
                restoreAlarmVolume()
                mp.release()
                if (player === mp) player = null
                true
            }
            prepare()
            start()
        }
    }

    private fun stopSound() {
        player?.let {
            try { if (it.isPlaying) it.stop() } catch (_: Exception) { }
            try { it.release() } catch (_: Exception) { }
        }
        player = null
        restoreAlarmVolume()
    }

    private fun restoreAlarmVolume() {
        val oldVolume = previousAlarmVolume ?: return
        previousAlarmVolume = null
        try {
            context.getSystemService(AudioManager::class.java)
                .setStreamVolume(AudioManager.STREAM_ALARM, oldVolume, 0)
        } catch (_: Exception) { }
    }

    private fun vibrate(pattern: LongArray) {
        val vibrator = vibrator() ?: return
        try {
            vibrator.vibrate(
                VibrationEffect.createWaveform(pattern, -1)
            )
        } catch (_: Exception) { }
    }

    private fun vibrator(): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
}
