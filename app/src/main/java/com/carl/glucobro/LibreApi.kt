package com.carl.glucobro

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

data class GraphPoint(
    val valueMmol: Double,
    val timestamp: String
)

data class GlucoseReading(
    val valueMgDl: Int,
    val valueMmol: Double,
    val deltaMmol: Double?,
    val trendArrow: String,
    val timestamp: String,
    val graphPoints: List<GraphPoint> = emptyList(),
    val history24h: List<GraphPoint> = emptyList(),
    val history7d: List<GraphPoint> = emptyList(),
    val sensorSerial: String? = null,
    val sensorActivatedAtEpochSeconds: Long? = null
)

class LibreApi(context: Context) {

    private val client = OkHttpClient()
    private val historyPrefs = context.applicationContext.getSharedPreferences(
        "glucobro_glucose_history",
        Context.MODE_PRIVATE
    )

    var authToken: String? = null
        private set

    var userId: String? = null
        private set

    private var patientId: String? = null

    private data class StoredReading(
        val valueMgDl: Int,
        val fetchedAtMs: Long,
        val libreTimestamp: String
    )

    private val recentReadings = ArrayDeque<StoredReading>()
    private var lastLibreTimestamp: String? = null

    init {
        restoreReadingHistory()
    }

    fun login(
        username: String,
        password: String
    ): Boolean {

        val json = JSONObject().apply {
            put("email", username)
            put("password", password)
        }

        val body = json
            .toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://api.libreview.io/llu/auth/login")
            .addHeader("Content-Type", "application/json")
            .addHeader("product", "llu.android")
            .addHeader("version", "4.17.0")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->

            if (!response.isSuccessful) {
                println("Login HTTP error: ${response.code}")
                return false
            }

            val responseText = response.body?.string()
                ?: return false

            val responseJson = JSONObject(responseText)
            val data = responseJson.getJSONObject("data")

            authToken = data
                .getJSONObject("authTicket")
                .getString("token")

            userId = data
                .getJSONObject("user")
                .getString("id")

            return true
        }
    }

    fun getLatestGlucose(): GlucoseReading? {

        if (authToken == null || userId == null) {
            return null
        }

        if (patientId == null) {
            patientId = getPatientId()
        }

        val patient = patientId ?: return null

        val request = Request.Builder()
            .url("https://api.libreview.io/llu/connections/$patient/graph")
            .addHeader("Authorization", "Bearer $authToken")
            .addHeader("Account-Id", sha256(userId!!))
            .addHeader("product", "llu.android")
            .addHeader("version", "4.17.0")
            .get()
            .build()

        client.newCall(request).execute().use { response ->

            if (!response.isSuccessful) {
                println("Graph HTTP error: ${response.code}")
                return null
            }

            val text = response.body?.string()
                ?: return null

            val json = JSONObject(text)
            val data = json.getJSONObject("data")

            val connection = data.getJSONObject("connection")

            val measurement = connection
                .getJSONObject("glucoseMeasurement")

            val mgDl = measurement.getInt("ValueInMgPerDl")
            val trend = measurement.optInt("TrendArrow", 0)
            val timestamp = measurement.optString("Timestamp", "")

            // Libre exposes the active sensor on the connection object.  "a" is
            // the sensor activation timestamp (Unix seconds), and "sn" is the
            // serial number. Keep both with the cached reading so the Stats page
            // can show sensor identity and estimated life remaining even offline.
            val sensor = connection.optJSONObject("sensor")
            val sensorSerial = sensor?.optString("sn", "")?.takeIf { it.isNotBlank() }
            val sensorActivatedAt = sensor?.optLong("a", 0L)?.takeIf { it > 0L }

            // Merge Libre's server graph history with our own persisted history.
            // This gives the graph an immediate six-hour view and lets the app
            // gradually retain a full rolling 24 hours for Time in Range.
            val mergedHistory = linkedMapOf<String, GraphPoint>()
            restore7dPoints().forEach { if (it.timestamp.isNotBlank()) mergedHistory[it.timestamp] = it }

            val graphData = data.optJSONArray("graphData")
            if (graphData != null) {
                for (i in 0 until graphData.length()) {
                    val point = graphData.optJSONObject(i) ?: continue
                    val pointMgDl = point.optInt("ValueInMgPerDl", -1)
                    val pointTimestamp = point.optString("Timestamp", "")
                    if (pointMgDl <= 0 || pointTimestamp.isBlank()) continue
                    mergedHistory[pointTimestamp] = GraphPoint(pointMgDl / 18.0, pointTimestamp)
                }
            }

            if (timestamp.isNotBlank()) {
                mergedHistory[timestamp] = GraphPoint(mgDl / 18.0, timestamp)
            }

            val history7d = trimHistoryByAge(mergedHistory.values.toList(), 7 * 24 * 60)
            persist7dPoints(history7d)
            val history24h = trimHistoryByAge(history7d, 24 * 60)
            val graphPoints = trimHistoryByAge(history24h, 6 * 60)

            val nowMs = System.currentTimeMillis()

            // Only store a reading once. If Libre returns the same measurement on a
            // later poll, it must not be treated as a new one-minute reading.
            if (timestamp != lastLibreTimestamp) {
                recentReadings.addLast(
                    StoredReading(
                        valueMgDl = mgDl,
                        fetchedAtMs = nowMs,
                        libreTimestamp = timestamp
                    )
                )
                lastLibreTimestamp = timestamp
            }

            // Keep only a small rolling history. Ten minutes is more than enough
            // for the three-minute delta and prevents this list growing forever.
            val tenMinutesAgo = nowMs - 10 * 60_000L
            while (recentReadings.isNotEmpty() &&
                recentReadings.first().fetchedAtMs < tenMinutesAgo
            ) {
                recentReadings.removeFirst()
            }

            // Save the rolling buffer after every successful fetch so it survives
            // the app being closed, swiped away, or killed by Android.
            persistReadingHistory()

            // Delta is deliberately unavailable until we genuinely have a reading
            // at least three minutes older than the current fetch.
            val targetMs = nowMs - 3 * 60_000L
            val previous = recentReadings
                .filter { it.fetchedAtMs <= targetMs }
                .maxByOrNull { it.fetchedAtMs }

            val deltaMmol = previous?.let {
                (mgDl - it.valueMgDl) / 18.0
            }

            val reading = GlucoseReading(
                valueMgDl = mgDl,
                valueMmol = mgDl / 18.0,
                deltaMmol = deltaMmol,
                trendArrow = trendToArrow(trend),
                timestamp = timestamp,
                graphPoints = graphPoints,
                history24h = history24h,
                history7d = history7d,
                sensorSerial = sensorSerial,
                sensorActivatedAtEpochSeconds = sensorActivatedAt
            )

            persistLatestGlucose(reading)
            return reading
        }
    }


    fun getCachedGlucose(): GlucoseReading? {
        if (!historyPrefs.contains("latest_value_mgdl")) return null

        return try {
            GlucoseReading(
                valueMgDl = historyPrefs.getInt("latest_value_mgdl", 0),
                valueMmol = historyPrefs.getInt("latest_value_mgdl", 0) / 18.0,
                deltaMmol = if (historyPrefs.contains("latest_delta_mmol_bits")) {
                    Double.fromBits(historyPrefs.getLong("latest_delta_mmol_bits", 0L))
                } else {
                    null
                },
                trendArrow = historyPrefs.getString("latest_trend_arrow", "?") ?: "?",
                timestamp = historyPrefs.getString("latest_timestamp", "") ?: "",
                graphPoints = restoreGraphPoints(),
                history24h = trimHistoryByAge(restore7dPoints(), 24 * 60),
                history7d = restore7dPoints(),
                sensorSerial = historyPrefs.getString("latest_sensor_serial", null),
                sensorActivatedAtEpochSeconds = historyPrefs.getLong("latest_sensor_activated", 0L).takeIf { it > 0L }
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun persistLatestGlucose(reading: GlucoseReading) {
        val editor = historyPrefs.edit()
            .putInt("latest_value_mgdl", reading.valueMgDl)
            .putString("latest_trend_arrow", reading.trendArrow)
            .putString("latest_timestamp", reading.timestamp)

        if (!reading.sensorSerial.isNullOrBlank()) {
            editor.putString("latest_sensor_serial", reading.sensorSerial)
        }
        if (reading.sensorActivatedAtEpochSeconds != null && reading.sensorActivatedAtEpochSeconds > 0L) {
            editor.putLong("latest_sensor_activated", reading.sensorActivatedAtEpochSeconds)
        }

        if (reading.deltaMmol != null) {
            editor.putLong("latest_delta_mmol_bits", reading.deltaMmol.toBits())
        } else {
            editor.remove("latest_delta_mmol_bits")
        }

        val graphArray = JSONArray()
        reading.graphPoints.forEach { point ->
            graphArray.put(JSONObject().apply {
                put("valueMmol", point.valueMmol)
                put("timestamp", point.timestamp)
            })
        }
        editor.putString("latest_graph_points", graphArray.toString())
        editor.apply()
    }

    private fun restoreGraphPoints(): List<GraphPoint> {
        val raw = historyPrefs.getString("latest_graph_points", null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    add(GraphPoint(item.getDouble("valueMmol"), item.optString("timestamp", "")))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private fun persist7dPoints(points: List<GraphPoint>) {
        val array = JSONArray()
        points.forEach { point ->
            array.put(JSONObject().apply {
                put("valueMmol", point.valueMmol)
                put("timestamp", point.timestamp)
            })
        }
        historyPrefs.edit().putString("history_7d_points", array.toString()).apply()
    }

    private fun restore7dPoints(): List<GraphPoint> {
        // Migrate seamlessly from the old 24-hour cache so an update does not
        // throw away the history already collected by previous GlucoBro builds.
        val raw = historyPrefs.getString("history_7d_points", null)
            ?: historyPrefs.getString("history_24h_points", null)
            ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    add(GraphPoint(item.getDouble("valueMmol"), item.optString("timestamp", "")))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private fun trimHistoryByAge(points: List<GraphPoint>, maxAgeMinutes: Int): List<GraphPoint> {
        if (points.isEmpty()) return emptyList()
        val formats = listOf("M/d/yyyy h:mm:ss a", "MM/dd/yyyy h:mm:ss a", "M/d/yyyy hh:mm:ss a")
        fun parseMs(timestamp: String): Long? {
            for (pattern in formats) {
                try {
                    val parsed = java.text.SimpleDateFormat(pattern, java.util.Locale.US).parse(timestamp)
                    if (parsed != null) return parsed.time
                } catch (_: Exception) { }
            }
            return null
        }
        val now = System.currentTimeMillis()
        val cutoff = now - maxAgeMinutes * 60_000L
        return points
            .mapNotNull { point -> parseMs(point.timestamp)?.let { it to point } }
            .filter { it.first >= cutoff }
            .sortedBy { it.first }
            .map { it.second }
    }

    fun restoreSession(
        token: String,
        savedUserId: String
    ) {
        authToken = token
        userId = savedUserId
        patientId = null
    }

    private fun restoreReadingHistory() {
        val raw = historyPrefs.getString("readings", null) ?: return
        val nowMs = System.currentTimeMillis()
        val tenMinutesAgo = nowMs - 10 * 60_000L

        try {
            val array = JSONArray(raw)

            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val fetchedAtMs = item.getLong("fetchedAtMs")

                if (fetchedAtMs >= tenMinutesAgo) {
                    recentReadings.addLast(
                        StoredReading(
                            valueMgDl = item.getInt("valueMgDl"),
                            fetchedAtMs = fetchedAtMs,
                            libreTimestamp = item.optString("libreTimestamp", "")
                        )
                    )
                }
            }

            lastLibreTimestamp = recentReadings.lastOrNull()?.libreTimestamp
            persistReadingHistory()
        } catch (e: Exception) {
            e.printStackTrace()
            recentReadings.clear()
            lastLibreTimestamp = null
            historyPrefs.edit().remove("readings").apply()
        }
    }

    private fun persistReadingHistory() {
        val array = JSONArray()

        recentReadings.forEach { reading ->
            array.put(
                JSONObject().apply {
                    put("valueMgDl", reading.valueMgDl)
                    put("fetchedAtMs", reading.fetchedAtMs)
                    put("libreTimestamp", reading.libreTimestamp)
                }
            )
        }

        historyPrefs.edit()
            .putString("readings", array.toString())
            .apply()
    }

    private fun getPatientId(): String? {

        val request = Request.Builder()
            .url("https://api.libreview.io/llu/connections")
            .addHeader("Authorization", "Bearer $authToken")
            .addHeader("Account-Id", sha256(userId!!))
            .addHeader("product", "llu.android")
            .addHeader("version", "4.17.0")
            .get()
            .build()

        client.newCall(request).execute().use { response ->

            if (!response.isSuccessful) {
                println("Connections HTTP error: ${response.code}")
                return null
            }

            val text = response.body?.string()
                ?: return null

            val json = JSONObject(text)

            val connections = json
                .getJSONArray("data")

            if (connections.length() == 0) {
                return null
            }

            return connections
                .getJSONObject(0)
                .getString("patientId")
        }
    }

    private fun sha256(input: String): String {

        val bytes = MessageDigest
            .getInstance("SHA-256")
            .digest(input.toByteArray())

        return bytes.joinToString("") {
            "%02x".format(it)
        }
    }

    private fun trendToArrow(trend: Int): String {

        return when (trend) {
            1 -> "↓"
            2 -> "↘"
            3 -> "→"
            4 -> "↗"
            5 -> "↑"
            else -> "?"
        }
    }
}