package com.carl.glucobro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.DashPathEffect
import android.graphics.drawable.Icon
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.text.SimpleDateFormat

class GlucosePollingService : Service() {

    companion object {
        const val CHANNEL_ID = "glucose_monitor"
        const val NOTIFICATION_ID = 1001
        private const val POLL_INTERVAL_MS = 60_000L
        private const val CRITICAL_LOW_LEVEL = 2.5
        private const val URGENT_REPEAT_MS = 5 * 60_000L
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollingJob: Job? = null
    private var notificationAgeJob: Job? = null
    private lateinit var alarmPlayer: GlucoseAlarmPlayer

    private var urgentEpisodeActive = false
    private var wasAtOrBelowCriticalLow = false
    private var wasLow = false
    private var wasHigh = false
    private var lastUrgentAlarmMs = 0L
    private var lastAlarmReadingTimestamp: String? = null

    @Volatile
    private var latestReading: GlucoseReading? = null

    @Volatile
    private var latestFallbackText: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        alarmPlayer = GlucoseAlarmPlayer(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android requires a foreground service to publish its notification immediately.
        startForeground(
            NOTIFICATION_ID,
            buildNotification(null, "Starting GlucoBro…")
        )

        // Every explicit start must use the session currently saved by the app.
        // If the user logs out and back in, Libre can issue a new token while an
        // older service instance is still alive. Cancelling the previous job here
        // prevents that old token from being reused indefinitely.
        pollingJob?.cancel()
        notificationAgeJob?.cancel()
        latestReading = null
        latestFallbackText = null
        // Restore urgentEpisodeActive from persistent state below. This prevents a
        // force-close/reopen from silently clearing an active urgent-low episode.
        urgentEpisodeActive = false
        wasAtOrBelowCriticalLow = false
        wasLow = false
        wasHigh = false
        lastUrgentAlarmMs = 0L
        lastAlarmReadingTimestamp = null

        pollingJob = serviceScope.launch {
            val preferences = Preferences(applicationContext)
            urgentEpisodeActive = preferences.getUrgentLowEpisodeActive()
            val token = preferences.getAuthToken()
            val userId = preferences.getUserId()

            if (token.isNullOrBlank() || userId.isNullOrBlank()) {
                stopSelf()
                return@launch
            }

            val api = LibreApi(applicationContext).apply {
                restoreSession(token, userId)
            }

            while (isActive) {
                val reading = try {
                    api.getLatestGlucose()
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }

                if (reading != null) {
                    latestReading = reading
                    latestFallbackText = null
                    updateNotification(reading)
                    evaluateAlarms(reading, preferences)
                } else {
                    val cached = api.getCachedGlucose()
                    latestReading = cached
                    latestFallbackText = "Unable to refresh • showing last reading"
                    updateNotification(cached, latestFallbackText)
                }

                delay(POLL_INTERVAL_MS)
            }
        }

        // Keep the human-readable "Updated x mins ago" text moving even if an
        // API request is slow, Libre returns the same reading for several polls,
        // or the network poll is temporarily blocked. This clock is deliberately
        // independent of the Libre polling loop.
        notificationAgeJob = serviceScope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                latestReading?.let { updateNotification(it, latestFallbackText) }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        pollingJob?.cancel()
        notificationAgeJob?.cancel()
        alarmPlayer.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Glucose monitor",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps your current Libre glucose reading visible"
            setShowBadge(false)
        }

        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun updateNotification(
        reading: GlucoseReading?,
        fallbackText: String? = null
    ) {
        val notification = if (reading == null) {
            buildNotification(null, fallbackText ?: "Waiting for glucose…")
        } else {
            val delta = reading.deltaMmol?.let {
                String.format(Locale.UK, "%+.1f mmol/L", it)
            } ?: "Awaiting Delta"

            val title = String.format(
                Locale.UK,
                "%.1f %s mmol/L",
                reading.valueMmol,
                reading.trendArrow
            )

            val ageText = updatedText(reading.timestamp, System.currentTimeMillis())
            val notificationText = if (fallbackText != null) {
                "$delta • $ageText • $fallbackText"
            } else {
                "$delta • $ageText"
            }

            buildNotification(
                title = title,
                text = notificationText,
                glucoseValue = reading.valueMmol,
                graphPoints = reading.graphPoints,
                readingTimestampMs = parseLibreTimestampMs(reading.timestamp)
            )
        }

        NotificationManagerCompat.from(this)
            .notify(NOTIFICATION_ID, notification)
    }


    private suspend fun evaluateAlarms(reading: GlucoseReading, preferences: Preferences) {
        // Only evaluate each Libre measurement once. The service may refresh the same
        // reading more than once while waiting for the next minute's sample.
        if (reading.timestamp == lastAlarmReadingTimestamp) return
        lastAlarmReadingTimestamp = reading.timestamp

        val urgentLowEnabled = preferences.getUrgentLowAlarmEnabled()
        val lowEnabled = preferences.getLowAlarmEnabled()
        val highEnabled = preferences.getHighAlarmEnabled()
        val urgentLowLevel = preferences.getUrgentLowAlarmLevel()
        val lowLevel = preferences.getLowAlarmLevel()
        val highLevel = preferences.getHighAlarmLevel()
        val urgentLowVolume = preferences.getUrgentLowAlarmVolume()
        val lowVolume = preferences.getLowAlarmVolume()
        val highVolume = preferences.getHighAlarmVolume()
        val value = reading.valueMmol
        val now = System.currentTimeMillis()

        val lowZone = value < lowLevel
        val highZone = value > highLevel
        val urgentZone = value <= urgentLowLevel
        val criticalZone = value <= CRITICAL_LOW_LEVEL

        // Urgent Low uses the user's configured threshold (2.5-3.9 mmol/L),
        // defaulting to 2.9. The user can disable the urgent/repeating alarm if
        // a sensor is clearly misbehaving:
        //   • first urgent alarm at the configured Urgent Low threshold or below
        //   • another immediate urgent alarm if glucose subsequently reaches 2.5
        //   • once an urgent episode has started, repeat every 5 minutes while the
        //     reading remains below the user's normal Low threshold
        //   • the episode only clears once glucose is back at/above that Low threshold
        // Disabling Urgent Low immediately clears any urgent episode state. The normal
        // Low alarm remains independent and may still sound once for the excursion.
        if (!urgentLowEnabled) {
            if (urgentEpisodeActive) preferences.setUrgentLowEpisodeActive(false)
            urgentEpisodeActive = false
            wasAtOrBelowCriticalLow = false
            lastUrgentAlarmMs = 0L
        } else if (!urgentEpisodeActive && urgentZone) {
            alarmPlayer.playUrgentLow(urgentLowVolume)
            lastUrgentAlarmMs = now
            urgentEpisodeActive = true
            preferences.setUrgentLowEpisodeActive(true)
            // If the first urgent reading is already <= 2.5, do not stack two alarms
            // on top of each other; treat this first alarm as covering both thresholds.
            wasAtOrBelowCriticalLow = criticalZone
        } else if (urgentEpisodeActive) {
            if (!lowZone) {
                urgentEpisodeActive = false
                preferences.setUrgentLowEpisodeActive(false)
                wasAtOrBelowCriticalLow = false
                lastUrgentAlarmMs = 0L
            } else {
                val crossedCriticalLow = criticalZone && !wasAtOrBelowCriticalLow
                val repeatDue = now - lastUrgentAlarmMs >= URGENT_REPEAT_MS

                if (crossedCriticalLow || repeatDue) {
                    alarmPlayer.playUrgentLow(urgentLowVolume)
                    lastUrgentAlarmMs = now
                }
                wasAtOrBelowCriticalLow = criticalZone
            }
        }

        // Normal Low/High alerts remain one alert per excursion. Low is suppressed
        // at/below the configured Urgent Low threshold only while Urgent Low is enabled,
        // takes priority. If Urgent Low is disabled, the ordinary Low alert still works.
        if (lowEnabled && lowZone && !(urgentLowEnabled && urgentZone) && !wasLow) {
            alarmPlayer.playLowOrHigh(lowVolume)
        }
        if (highEnabled && highZone && !wasHigh) {
            alarmPlayer.playLowOrHigh(highVolume)
        }

        wasLow = lowZone
        wasHigh = highZone
    }

    /**
     * Use the Libre measurement timestamp for the notification age, rather than
     * the time the foreground service happened to refresh the notification.
     * This intentionally mirrors the wording used by the main app UI.
     */
    private fun parseLibreTimestampMs(timestamp: String): Long? {
        if (timestamp.isBlank()) return null

        val formats = listOf(
            "M/d/yyyy h:mm:ss a",
            "MM/dd/yyyy h:mm:ss a",
            "M/d/yyyy hh:mm:ss a"
        )
        return formats.firstNotNullOfOrNull { pattern ->
            try {
                SimpleDateFormat(pattern, Locale.US).parse(timestamp)?.time
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun updatedText(timestamp: String, nowMs: Long): String {
        val readingTimeMs = parseLibreTimestampMs(timestamp) ?: return "Updated recently"
        val minutes = ((nowMs - readingTimeMs) / 60_000L).coerceAtLeast(0)
        return when {
            minutes <= 0 -> "Updated just now"
            minutes == 1L -> "Updated 1 min ago"
            else -> "Updated $minutes mins ago"
        }
    }

    private fun buildNotification(
        title: String?,
        text: String,
        glucoseValue: Double? = null,
        graphPoints: List<GraphPoint> = emptyList(),
        readingTimestampMs: Long? = null
    ): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title ?: "GlucoBro")
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STATUS)

        // Android/One UI has its own notification timestamp, separate from the
        // content text above. Without setting this explicitly, every call to
        // notify() makes the notification itself look freshly updated ("now"),
        // even when Libre is still serving an older measurement. Tie Android's
        // timestamp to the Libre measurement so its displayed age stays honest.
        if (readingTimestampMs != null) {
            builder
                .setWhen(readingTimestampMs)
                .setShowWhen(true)
        } else {
            builder.setShowWhen(false)
        }

        if (glucoseValue != null) {
            builder.setSmallIcon(createGlucoseStatusIcon(glucoseValue))
        } else {
            builder.setSmallIcon(R.drawable.ic_stat_glucose)
        }

        // BigPictureStyle is intentionally used here instead of a Compose view:
        // Android notifications can expand this bitmap reliably on Samsung/One UI.
        if (graphPoints.size >= 2) {
            builder.setStyle(
                Notification.BigPictureStyle()
                    .bigPicture(createSixHourGraph(graphPoints))
                    .setBigContentTitle(title ?: "GlucoBro")
                    .setSummaryText(text)
            )
        }

        return builder.build()
    }

    private fun createSixHourGraph(points: List<GraphPoint>): Bitmap {
        val width = 1000
        val height = 430
        val left = 72f
        val right = 24f
        val top = 22f
        val bottom = 54f
        val plotW = width - left - right
        val plotH = height - top - bottom

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val values = points.map { it.valueMmol }
        var minY = minOf(3.0, values.minOrNull() ?: 3.0)
        var maxY = maxOf(11.0, values.maxOrNull() ?: 11.0)
        minY = kotlin.math.floor(minY).coerceAtLeast(2.0)
        maxY = kotlin.math.ceil(maxY).coerceAtMost(22.0)
        if (maxY - minY < 8.0) maxY = minY + 8.0

        fun yFor(v: Double): Float = top + ((maxY - v) / (maxY - minY) * plotH).toFloat()
        fun xFor(i: Int): Float = if (points.size <= 1) left else left + i * plotW / (points.size - 1)

        val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(110, 110, 110)
            strokeWidth = 2f
            textSize = 28f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(190, 190, 190)
            strokeWidth = 2f
            pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
        }

        // Target lines at 4.0 and 10.0 mmol/L.
        for (level in listOf(4.0, 10.0)) {
            if (level in minY..maxY) {
                val y = yFor(level)
                canvas.drawLine(left, y, width - right, y, gridPaint)
                canvas.drawText(String.format(Locale.UK, "%.0f", level), 12f, y + 10f, axisPaint)
            }
        }

        // A subtle 7 mmol/L guide makes the graph easier to read at a glance.
        if (7.0 in minY..maxY) {
            val y = yFor(7.0)
            val midPaint = Paint(gridPaint).apply { color = Color.rgb(225, 225, 225) }
            canvas.drawLine(left, y, width - right, y, midPaint)
            canvas.drawText("7", 12f, y + 10f, axisPaint)
        }

        // Draw each segment using GlucoBro's glucose colours.
        fun colourFor(v: Double): Int = when {
            v < 4.0 -> Color.rgb(220, 40, 40)
            v > 10.0 -> Color.rgb(35, 105, 210)
            else -> Color.rgb(20, 155, 75)
        }
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 9f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        // Split segments exactly where they cross 4.0 or 10.0, so red is used
        // only below 4.0 and blue only above 10.0. The in-range portion stays green.
        fun drawThresholdAwareSegment(x1: Float, v1: Double, x2: Float, v2: Double) {
            val cuts = mutableListOf(0.0, 1.0)
            if (v1 != v2) {
                for (threshold in listOf(4.0, 10.0)) {
                    val t = (threshold - v1) / (v2 - v1)
                    if (t > 0.0 && t < 1.0) cuts.add(t)
                }
            }
            cuts.sort()
            for (j in 0 until cuts.lastIndex) {
                val ta = cuts[j]
                val tb = cuts[j + 1]
                val tm = (ta + tb) / 2.0
                val va = v1 + (v2 - v1) * ta
                val vb = v1 + (v2 - v1) * tb
                val vm = v1 + (v2 - v1) * tm
                val xa = x1 + (x2 - x1) * ta.toFloat()
                val xb = x1 + (x2 - x1) * tb.toFloat()
                linePaint.color = colourFor(vm)
                canvas.drawLine(xa, yFor(va), xb, yFor(vb), linePaint)
            }
        }

        for (i in 0 until points.lastIndex) {
            drawThresholdAwareSegment(
                xFor(i), points[i].valueMmol,
                xFor(i + 1), points[i + 1].valueMmol
            )
        }

        // Highlight the current reading.
        val last = points.last()
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colourFor(last.valueMmol)
            style = Paint.Style.FILL
        }
        canvas.drawCircle(xFor(points.lastIndex), yFor(last.valueMmol), 13f, dotPaint)

        // Six-hour time axis at hourly intervals. The first and last labels use
        // edge alignment so neither can be clipped by Android's BigPicture crop.
        val now = java.util.Calendar.getInstance()
        val timeFormat = java.text.SimpleDateFormat("HH:mm", Locale.UK)
        for (slot in 0..6) {
            val cal = now.clone() as java.util.Calendar
            cal.add(java.util.Calendar.MINUTE, -(6 - slot) * 60)
            val x = left + slot * plotW / 6f
            axisPaint.textAlign = when (slot) {
                0 -> Paint.Align.LEFT
                6 -> Paint.Align.RIGHT
                else -> Paint.Align.CENTER
            }
            canvas.drawText(timeFormat.format(cal.time), x, height - 14f, axisPaint)
        }

        return bitmap
    }

    /**
     * Draw the current mmol/L value into the notification small icon. Android
     * treats a small icon as an alpha mask and applies the status-bar tint, so
     * the bitmap is deliberately transparent with solid white digits.
     */
    private fun createGlucoseStatusIcon(valueMmol: Double): Icon {
        val label = String.format(Locale.UK, "%.1f", valueMmol)
        val size = 96
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Absolute-max status-bar renderer: size the ACTUAL glyph bounds to almost
        // the full bitmap height, then compress WIDTH only when a value is too long.
        // This preserves maximum digit height for both 6.2 and 10.4.
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            textSize = 96f
        }

        val bounds = android.graphics.Rect()
        paint.getTextBounds(label, 0, label.length, bounds)

        // Leave only ~1 pixel at the top and bottom. Using glyph bounds rather than
        // font metrics avoids Android font ascent/descent padding stealing icon size.
        val targetHeight = size * 0.98f
        if (bounds.height() > 0) {
            paint.textSize *= targetHeight / bounds.height().toFloat()
        }

        // Re-measure at the maximised height, then squeeze horizontally only.
        // Nearly the entire icon width is available; Android supplies the outer slot.
        paint.getTextBounds(label, 0, label.length, bounds)
        val maxWidth = size * 0.995f
        val naturalWidth = paint.measureText(label)
        paint.textScaleX = (maxWidth / naturalWidth).coerceAtMost(1.0f)

        // Re-read bounds after final sizing and centre the visible glyphs precisely.
        paint.getTextBounds(label, 0, label.length, bounds)
        val baseline = size / 2f - (bounds.top + bounds.bottom) / 2f
        canvas.drawText(label, size / 2f, baseline, paint)

        return Icon.createWithBitmap(bitmap)
    }
}
